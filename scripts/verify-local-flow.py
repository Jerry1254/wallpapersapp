#!/usr/bin/env python3
"""Local-only WP-P11 fixture: prepare -> redeem in H5 -> complete (restarts stack).

The local admin hash is temporarily replaced for a real login, then restored
immediately. Business content is written through APIs and verified through APIs/SQL.
"""
import argparse
import base64
import concurrent.futures
import csv
import datetime
import hashlib
import hmac
import http.client
import io
import json
import os
import re
from pathlib import Path
import secrets
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.runtime/local-flow-fixture.json'
REPORT = ROOT / '.runtime/local-flow-report.json'
ADMIN_BACKUP = ROOT / '.runtime/local-flow-admin-backup.json'
UI_LOGIN = ROOT / '.runtime/local-flow-ui-login.json'
BASE = 'http://127.0.0.1:8080'
COMPOSE = ['docker', 'compose', '--env-file', str(ROOT / '.runtime/local-api/compose.env'), '-f', str(ROOT / 'infra/local/compose.yaml')]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sql(query):
    return subprocess.run(COMPOSE + ['exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -N -B -uroot "$MYSQL_DATABASE"'],
        input=query, text=True, capture_output=True, check=True).stdout.strip()


def redis(*args):
    return subprocess.run(COMPOSE + ['exec', '-T', 'redis', 'sh', '-c',
        'REDISCLI_AUTH="$QJ_REDIS_PASSWORD" exec redis-cli --raw "$@"', 'sh', *args],
        capture_output=True, text=True, check=True).stdout.strip()


def request(path, data=None, headers=None, method=None, accepted=(200, 201, 204), raw=False, timeout=20):
    body = data if isinstance(data, bytes) else json.dumps(data, separators=(',', ':'), ensure_ascii=False).encode() if data is not None else None
    headers = dict(headers or {})
    if body is not None:
        headers.setdefault('Content-Type', 'application/json')
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = response.read()
        status = response.status
        require(status in accepted, f'{path.split("?")[0]} unexpected HTTP {status}')
        value = payload if raw else json.loads(payload) if payload else None
        return value, response.headers, status


def api(path, **kwargs):
    return request('/api/v1' + path, **kwargs)


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z').replace('.000Z', 'Z')


def sign(secret, payload):
    return base64.urlsafe_b64encode(hmac.new(secret.encode(), payload.encode(), hashlib.sha256).digest()).decode().rstrip('=')


class AdminFixture:
    def __init__(self, ui=False):
        self.ui = ui

    def __enter__(self):
        account = sql('SELECT id, username, password_hash FROM admin_account LIMIT 1;').split('\t')
        require(len(account) == 3, 'Initialize the local administrator before this fixture')
        jars = sorted((Path.home() / '.m2/repository/org/springframework/security/spring-security-crypto').glob('6.*/*.jar'))
        require(bool(jars), 'Run API Maven build before this fixture')
        source = ROOT / '.runtime/LocalFlowHash.java'
        source.write_text('import org.springframework.security.crypto.bcrypt.BCrypt;\n'
            'class LocalFlowHash { public static void main(String[] args) throws Exception { '
            'String value = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine(); '
            'System.out.print(BCrypt.hashpw(value, BCrypt.gensalt(12))); }}\n')
        password = secrets.token_urlsafe(36)
        hashed = subprocess.run(['java', '--class-path', str(jars[-1]), str(source)], input=password + '\n', text=True, capture_output=True, check=True).stdout
        source.unlink()
        fd = os.open(ADMIN_BACKUP, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as handle:
            json.dump({'id': int(account[0]), 'hash': account[2]}, handle)
        try:
            require(bool(re.fullmatch(r'\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}', hashed)), 'Invalid local hash')
            sql(f"UPDATE admin_account SET password_hash='{hashed}' WHERE id={int(account[0])};")
            session, headers, _ = api('/admin/sessions', data={'username': account[1], 'password': password})
            cookie = headers['Set-Cookie']
            require('HttpOnly' in cookie and 'SameSite=Strict' in cookie, 'Missing session cookie protection')
            token = cookie.split(';', 1)[0].split('=', 1)[1]
            self.key = 'admin:session:' + hashlib.sha256(token.encode()).hexdigest()
            self.headers = {'Cookie': cookie.split(';', 1)[0], 'X-CSRF-Token': session['csrfToken']}
            if self.ui:
                fd = os.open(UI_LOGIN, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, 'w') as handle:
                    json.dump({'username': account[1], 'password': password}, handle)
                print('Local UI login fixture ready; remove the private UI fixture file after signing in. Original hash will then be restored.', flush=True)
                deadline = time.monotonic() + 120
                while UI_LOGIN.exists() and time.monotonic() < deadline:
                    time.sleep(0.5)
                require(not UI_LOGIN.exists(), 'Local UI login fixture timed out')
        except BaseException:
            if hasattr(self, 'key'):
                redis('DEL', self.key)
            raise
        finally:
            if self.ui and UI_LOGIN.exists():
                UI_LOGIN.unlink()
            restore_admin_hash()
        require(api('/admin/sessions', headers=self.headers)[0]['admin']['id'] == account[0], 'Admin login mismatch')
        return self

    def __exit__(self, *_args):
        redis('DEL', self.key)

    def call(self, path, headers=None, **kwargs):
        return api('/admin' + path, headers={**self.headers, **(headers or {})}, **kwargs)


def restore_admin_hash():
    if ADMIN_BACKUP.exists():
        backup = json.loads(ADMIN_BACKUP.read_text())
        require(bool(re.fullmatch(r'\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}', backup['hash'])), 'Invalid local admin backup')
        sql(f"UPDATE admin_account SET password_hash='{backup['hash']}' WHERE id={int(backup['id'])};")
        restored = sql(f"SELECT password_hash FROM admin_account WHERE id={int(backup['id'])};")
        require(restored == backup['hash'], 'Local admin hash was not restored')
        ADMIN_BACKUP.unlink()


def png(width, height, alpha=False):
    def chunk(kind, content):
        return struct.pack('>I', len(content)) + kind + content + struct.pack('>I', zlib.crc32(kind + content))
    if alpha:
        pixels = b''.join(b'\0' + b''.join(bytes((160, 130, 75, 220 if width // 3 < x < width * 2 // 3 and y > height // 3 else 0)) for x in range(width)) for y in range(height))
    else:
        pixels = b''.join(b'\0' + bytes((55 + y * 80 // height, 90 + y * 65 // height, 105)) * width for y in range(height))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6 if alpha else 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


def upload(admin, purpose, content, filename='local-flow.png', mime='image/png'):
    boundary = 'qj-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\n{purpose}\r\n'
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: {mime}\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    return admin.call('/assets', data=body, headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})[0]


def prepare():
    require(not STATE.exists(), 'A fixture already exists; finish it before preparing another')
    suffix = uuid.uuid4().hex[:8]
    with AdminFixture() as admin:
        icon = upload(admin, 'CATEGORY_ICON', png(120, 120))
        cover = upload(admin, 'WALLPAPER_COVER', png(540, 1080))
        background = upload(admin, 'BACKGROUND', png(600, 1200), 'background.png')
        foreground = upload(admin, 'FOREGROUND', png(600, 1200, alpha=True), 'foreground.png')
        config = upload(admin, 'PARALLAX_CONFIG', json.dumps({'formatVersion': 2,
            'canvas': {'width': 600, 'height': 1200}, 'motion': {'maxAngleX': 75, 'maxAngleY': 75},
            'layers': [
                {'index': 1, 'offsetXPercent': 8, 'offsetYPercent': 6, 'initialOffsetXPercent': 0, 'initialOffsetYPercent': 0, 'direction': 'follow', 'scale': 1.16, 'opacity': 1, 'blendMode': 'normal'},
                {'index': 2, 'offsetXPercent': 3, 'offsetYPercent': 2, 'initialOffsetXPercent': 0, 'initialOffsetYPercent': 0, 'direction': 'reverse', 'scale': 1, 'opacity': 1, 'blendMode': 'normal'}]}).encode(), 'config.json', 'application/json')
        root = admin.call('/categories', data={'name': '全链路风景 ' + suffix, 'slug': 'flow-root-' + suffix, 'iconAssetId': icon['id'], 'sortOrder': 20})[0]
        child = admin.call('/categories', data={'parentId': root['id'], 'name': '全链路景深 ' + suffix, 'slug': 'flow-child-' + suffix, 'sortOrder': 21})[0]
        wallpaper, headers, _ = admin.call('/wallpapers', data={'title': '全链路验收景深 ' + suffix, 'slug': 'flow-wallpaper-' + suffix,
            'kind': 'PARALLAX_4D', 'rootCategoryId': root['id'], 'childCategoryId': child['id'], 'coverAssetId': cover['id'],
            'sortOrder': 1, 'featuredRank': 1, 'copyrightNote': '项目本地程序生成的验收素材'})
        wid = wallpaper['id']
        api('/public/wallpapers/' + wid, accepted=(404,))
        api('/public/assets/' + cover['id'] + '/content', accepted=(404,), raw=True)
        variant = admin.call('/wallpapers/' + wid + '/variants', data={'platform': 'ANDROID', 'resourceType': 'LAYER_PARALLAX', 'capabilityRequirements': ['GYROSCOPE']}, headers={'If-Match': headers['ETag']})[0]
        version = admin.call('/variants/' + variant['id'] + '/resource-versions', data={'versionNo': 1, 'bindings': [
            {'assetId': background['id'], 'role': 'BACKGROUND', 'ordinal': 0}, {'assetId': foreground['id'], 'role': 'FOREGROUND', 'ordinal': 0},
            {'assetId': config['id'], 'role': 'PARALLAX_CONFIG', 'ordinal': 0}]})[0]
        _, current, _ = admin.call('/wallpapers/' + wid)
        admin.call('/wallpapers/' + wid + '/publish', data={'resourceVersionIds': [version['id']]}, headers={'If-Match': current['ETag']})
        categories = api('/public/categories')[0]['items']
        require(any(item['id'] == root['id'] and any(c['id'] == child['id'] for c in item['children']) for item in categories), 'Published category missing')
        listing = api('/public/wallpapers?q=' + urllib.parse.quote('全链路验收景深'))[0]
        require(any(item['id'] == wid for item in listing['items']), 'Published search missing')
        require(api('/public/wallpapers/' + wid)[0]['cover']['assetId'] == cover['id'], 'Cover mismatch')
        require(api('/public/assets/' + cover['id'] + '/content', raw=True)[0] == png(540, 1080), 'Cover bytes mismatch')
        for asset in [background, foreground, config]:
            api('/public/assets/' + asset['id'] + '/content', accepted=(404,), raw=True)
        batch = admin.call('/code-batches', data={'name': 'WP-P11 全链路 ' + suffix, 'generatedCount': 1, 'quotaPerCode': 3}, headers={'Idempotency-Key': str(uuid.uuid4())})[0]
        bid = batch['batch']['id']
        csv_bytes = admin.call('/code-batches/' + bid + '/delivery', headers={'X-Delivery-Ticket': batch['deliveryTicket']}, raw=True)[0]
        fields = list(csv.reader(io.StringIO(csv_bytes.decode('utf-8-sig'))))[-1]
        code = next(field for field in fields if len(field.replace('-', '')) == 20)
        admin.call('/code-batches/' + bid + '/delivery-confirmation', data={})
        state = {'wallpaperId': wid, 'rootId': root['id'], 'childId': child['id'], 'coverAssetId': cover['id'], 'batchId': bid, 'versionId': version['id'], 'code': code}
        STATE.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(STATE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as handle:
            json.dump(state, handle)
        print(json.dumps({'phase': 'prepared', 'wallpaperId': wid, 'batchId': bid, 'next': 'Redeem this wallpaper once in H5, then run complete'}, ensure_ascii=False))


class Device:
    def __init__(self):
        self.credential = api('/device/registrations', data={'platform': 'H5_TEST', 'appInstallScope': 'h5-local', 'credentialType': 'H5_TEST_SECRET', 'evidenceToken': secrets.token_urlsafe(48)})[0]
        self.session()

    def session(self):
        kid = self.credential['credentialKeyId']
        challenge = api('/device/session-challenges', data={'credentialKeyId': kid})[0]
        when = timestamp()
        proof = sign(self.credential['credentialSecret'], '\n'.join(['QJ-DEVICE-SESSION-V1', kid, challenge['challengeId'], challenge['nonce'], when]))
        self.token = api('/device/sessions', data={'credentialKeyId': kid, 'challengeId': challenge['challengeId'], 'clientTimestamp': when, 'proof': proof})[0]['accessToken']

    def call(self, path, data=None, headers=None, accepted=(200, 201, 202, 422)):
        body = json.dumps(data, separators=(',', ':')).encode() if data is not None else None
        auth = {'Authorization': 'Bearer ' + self.token, **(headers or {})}
        if body is not None:
            when, nonce = timestamp(), str(uuid.uuid4())
            payload = '\n'.join(['QJ-SIGNED-REQUEST-V1', 'POST', '/api/v1/device' + path, when, nonce, hashlib.sha256(body).hexdigest()])
            auth.update({'X-Request-Timestamp': when, 'X-Request-Nonce': nonce, 'X-Request-Signature': sign(self.credential['credentialSecret'], payload)})
        return api('/device' + path, data=body, headers=auth, accepted=accepted)[0]

    def redeem(self, wid, code, key=None, accepted=(200, 201, 202, 422)):
        key = key or str(uuid.uuid4())
        result = self.call('/redemptions', {'wallpaperId': wid, 'code': code.replace('-', '')}, {'Idempotency-Key': key}, accepted)
        for _ in range(10):
            if result.get('status') != 'PROCESSING':
                return result
            time.sleep(0.1)
            result = self.call('/redemptions/' + key)
        raise RuntimeError('Redemption still processing')

    def download(self, wid):
        return self.call('/wallpapers/' + wid + '/download-tickets', {'platform': 'H5_TEST', 'supportedResourceTypes': ['LAYER_PARALLAX']})


def facts(admin, state):
    wid, bid = state['wallpaperId'], state['batchId']
    records = admin.call('/redemptions?wallpaperId=' + wid)[0]['items']
    batch = admin.call('/code-batches/' + bid)[0]
    count = int(sql(f"SELECT COUNT(*) FROM device_entitlement WHERE wallpaper_id={int(wid)} AND status='ACTIVE';"))
    require(batch['usedQuota'] == 3 and count == 3, 'Quota/entitlement invariant failed')
    require(sum(item['quotaDelta'] for item in records) == 3, 'Ledger quota sum failed')
    require(sum(item['result'] == 'GRANTED' for item in records) == 3, 'Granted ledger count failed')
    require(int(sql(f"SELECT COUNT(*) FROM (SELECT device_id FROM device_entitlement WHERE wallpaper_id={int(wid)} GROUP BY device_id HAVING COUNT(*)>1) duplicates;")) == 0, 'Duplicate device ownership')
    devices = {item['deviceId'] for item in records if item['result'] == 'GRANTED'}
    for did in devices:
        detail = admin.call('/devices/' + did)[0]
        require(any(item['wallpaper']['id'] == wid and item['status'] == 'ACTIVE' for item in detail['entitlements']), 'Admin ownership mismatch')
    return {'usedQuota': 3, 'totalQuota': 3, 'activeEntitlements': count, 'grantedRecords': 3, 'deviceIds': sorted(devices)}


def complete():
    state = json.loads(STATE.read_text())
    wid, bid, code = state['wallpaperId'], state['batchId'], state['code']
    with AdminFixture() as admin:
        initial = admin.call('/code-batches/' + bid)[0]
        require(initial['usedQuota'] == 1, 'Exactly one H5 device must redeem before completing')
        devices = [Device() for _ in range(5)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            results = list(pool.map(lambda device: device.redeem(wid, code), devices))
        require(sum(result['result'] == 'GRANTED' for result in results) == 2, 'Expected two concurrency winners')
        require(sum(result['result'] == 'CODE_EXHAUSTED' for result in results) == 3, 'Expected three exhausted rejections')
        winner = devices[next(i for i, result in enumerate(results) if result['result'] == 'GRANTED')]
        won = next(result for result in results if result['result'] == 'GRANTED')
        require(winner.redeem(wid, code, won['idempotencyKey']) == won, 'Idempotency replay changed result')
        repeat = winner.redeem(wid, code)
        require(repeat['result'] == 'ALREADY_OWNED' and repeat['quotaDelta'] == 0, 'Repeat consumed quota')
        require(winner.call('/redemptions/' + won['idempotencyKey']) == won, 'Result confirmation mismatch')
        conflict = winner.redeem(wid, 'A' * 20, won['idempotencyKey'], accepted=(409,))
        require(conflict['error']['code'] == 'IDEMPOTENCY_KEY_REUSED', 'Changed body did not conflict')
        for _ in range(2):
            descriptor = winner.download(wid)
            require(descriptor['deliveryMode'] == 'H5_PLACEHOLDER' and descriptor['wallpaperId'] == wid, 'Invalid H5 delivery')
        loser = devices[next(i for i, result in enumerate(results) if result['result'] == 'CODE_EXHAUSTED')]
        require(not loser.call('/me/entitlements')['items'], 'Rejected device gained entitlement')
        require(loser.call('/wallpapers/' + wid + '/download-tickets', {'platform': 'H5_TEST', 'supportedResourceTypes': ['LAYER_PARALLAX']}, accepted=(403,))['error']['code'] == 'ENTITLEMENT_REQUIRED', 'Missing download authorization')
        before = facts(admin, state)
        _, headers, _ = admin.call('/wallpapers/' + wid)
        admin.call('/wallpapers/' + wid + '/offline', data={'reason': 'WP-P11 下线权益验收'}, headers={'If-Match': headers['ETag']})
        api('/public/wallpapers/' + wid, accepted=(404,))
        api('/public/assets/' + state['coverAssetId'] + '/content', accepted=(404,), raw=True)
        require(any(item['wallpaper']['id'] == wid for item in winner.call('/me/entitlements')['items']), 'Offline ownership lost')
        require(winner.download(wid)['deliveryMode'] == 'H5_PLACEHOLDER', 'Offline download lost')
        offline_facts = facts(admin, state)
        _, current, _ = admin.call('/wallpapers/' + wid)
        admin.call('/wallpapers/' + wid + '/publish', data={'resourceVersionIds': [state['versionId']]}, headers={'If-Match': current['ETag']})
        api('/public/wallpapers/' + wid)
        print('Concurrency, replay, download and offline checks passed; restarting local API/MySQL/Redis.', flush=True)
        subprocess.run(COMPOSE + ['restart', 'mysql', 'redis', 'api'], check=True)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                require(request('/actuator/health/readiness', timeout=2)[0]['status'] == 'UP', 'Not ready')
                break
            except (RuntimeError, urllib.error.URLError, TimeoutError, http.client.HTTPException, ConnectionError):
                time.sleep(2)
        else:
            raise RuntimeError('Local stack did not recover within 120 seconds')
        api('/public/wallpapers/' + wid)
        require(api('/public/assets/' + state['coverAssetId'] + '/content', raw=True)[0] == png(540, 1080), 'Storage did not recover')
        # Delete only this fixture's sessions, proving MySQL facts survive Redis session loss.
        for key in redis('KEYS', 'device:session:*').splitlines():
            stored = redis('GET', key)
            if not stored:
                continue
            session = json.loads(stored)
            if str(session['deviceId']) in before['deviceIds']:
                redis('DEL', key)
        expired = winner.call('/me/entitlements', accepted=(401,))
        require(expired['error']['code'] == 'SESSION_EXPIRED', 'Session loss did not require renewal')
        winner.session()
        require(any(item['wallpaper']['id'] == wid for item in winner.call('/me/entitlements')['items']), 'Ownership did not recover')
        require(winner.download(wid)['deliveryMode'] == 'H5_PLACEHOLDER', 'Download did not recover')
        after = facts(admin, state)
        require(before == offline_facts == after, 'Facts changed across offline/restart')
        report = {'completedAt': timestamp(), 'wallpaperId': wid, 'batchId': bid, 'concurrentAttempts': 5,
            'concurrentGranted': 2, 'concurrentExhausted': 3, 'beforeRestart': before, 'afterRestart': after,
            'checks': ['real admin login/hash restored', 'draft visibility', 'two-level category', '4D cover/background/foreground/config upload', 'publish/search/detail/cover', 'private layers/config hidden',
                'H5 A redemption', 'concurrent quota limit', 'same-key replay', 'already-owned no cost', 'changed-body conflict',
                'result confirmation', 'repeat placeholder download', 'unowned download denied', 'offline entitlement retained',
                'republish', 'full stack restart', 'file bytes retained', 'session loss/renewal', 'SQL/admin/device consistency']}
        REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        STATE.unlink()
        print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['prepare', 'complete', 'login-ui'])
    args = parser.parse_args()
    require((ROOT / '.runtime/local-api/compose.env').exists(), 'Start ./scripts/local-api.sh up first')
    try:
        restore_admin_hash()
        if args.phase == 'prepare':
            prepare()
        elif args.phase == 'complete':
            complete()
        else:
            with AdminFixture(ui=True):
                print('Local UI login finished; original admin hash restored and API fixture session revoked on exit.', flush=True)
    except Exception as error:
        # Avoid printing HTTP bodies, credentials, delivery tickets or codes.
        print(f'Local acceptance failed: {type(error).__name__}: {error}', flush=True)
        raise SystemExit(1)
