"""Same-origin creator bridge; records and durable jobs belong to the local admin API."""
import http.client
import json
import os
import secrets
import tempfile
import threading
import time
import uuid
from http.cookies import SimpleCookie
from pathlib import Path
from urllib.parse import urlsplit, quote, parse_qs, urlencode

PREFIX = '/creator-studio/api/creator'
MAX_BODY = 512 * 1024 * 1024

class BridgeError(Exception):
    def __init__(self, message, status=400, details=None):
        super().__init__(message)
        self.status = status
        self.details = details or {}

class CreatorBridge:
    def __init__(self):
        self.backend = urlsplit(os.environ.get('CREATOR_DATA_API', 'http://127.0.0.1:8080'))
        if self.backend.scheme != 'http' or self.backend.hostname not in ('127.0.0.1', 'localhost'):
            raise ValueError('CREATOR_DATA_API must point to the loopback admin API')
        self.session = None
        self.publishing = None
        self.runner = str(uuid.uuid4())
        self.active = None
        self.renderers = {}
        self.render_lock = None
        self.task_states = {}
        self.task_states_lock = threading.Lock()

    def observe_tasks(self, result):
        with self.task_states_lock:
            for task in result if isinstance(result, list) else [result]:
                if not isinstance(task, dict) or not task.get('id') or not task.get('state'):
                    continue
                previous = self.task_states.get(task['id'])
                stamp = task.get('updatedAt') or ''
                if previous and stamp < previous[0]:
                    continue
                self.task_states[task['id']] = (stamp, task['state'])

    def has_pending_generation(self):
        with self.task_states_lock:
            return any(state in {'PREPARING', 'QUEUED', 'RUNNING', 'CANCEL_REQUESTED'}
                       for _, state in self.task_states.values())

    def start(self, renderers, render_lock):
        self.renderers = renderers
        self.render_lock = render_lock
        threading.Thread(target=self.loop, daemon=True).start()

    def api(self, method, path, data=None, source=None, size=0, headers=None, target=None, session=None):
        current = session or self.session
        conn = http.client.HTTPConnection(self.backend.hostname, self.backend.port or 8080, timeout=120)
        fields = {'Accept': 'application/json'}
        if current:
            fields.update({'Cookie': current['cookie'], 'X-CSRF-Token': current['csrf']})
        fields.update(headers or {})
        body = json.dumps(data, ensure_ascii=False).encode() if data is not None else source
        if data is not None:
            fields.update({'Content-Type': 'application/json', 'Content-Length': str(len(body))})
        elif source is not None:
            fields.setdefault('Content-Type', 'application/octet-stream')
            fields['Content-Length'] = str(size)
        try:
            conn.request(method, '/api/v1/admin/' + path, body=body, headers=fields)
            response = conn.getresponse()
            if target is not None and response.status < 400:
                count = 0
                with Path(target).open('wb') as stream:
                    while chunk := response.read(1024 * 1024):
                        count += len(chunk)
                        if count > MAX_BODY:
                            raise BridgeError('任务文件超过 512 MB', 413)
                        stream.write(chunk)
                return None
            raw = response.read(12 * 1024 * 1024)
            try:
                result = json.loads(raw) if raw else {}
            except ValueError:
                raise BridgeError('本地后台返回的数据无法读取，请更新后台', 502)
            if response.status >= 400:
                if response.status == 401 and current is self.session:
                    self.session = None
                detail = result.get('error', result)
                if not isinstance(detail, dict):
                    detail = {'message': str(detail)}
                raise BridgeError(detail.get('message') or detail.get('detail') or '创作数据请求失败', response.status, detail)
            if path == 'sessions' and method == 'POST':
                return result, response.getheader('Set-Cookie', '').split(';')[0]
            if path.split('?')[0].startswith('creator/tasks') or path == 'creator/claim':
                self.observe_tasks(result)
            return result
        except (OSError, http.client.HTTPException) as error:
            raise BridgeError('本地后台无法连接，请启动本地 API', 503) from error
        finally:
            conn.close()

    def respond(self, h, data, status=200, cookie=None):
        raw = json.dumps(data, ensure_ascii=False).encode()
        h.send_response(status)
        h.send_header('Content-Type', 'application/json; charset=utf-8')
        h.send_header('Content-Length', str(len(raw)))
        h.send_header('Cache-Control', 'no-store')
        if cookie:
            h.send_header('Set-Cookie', cookie)
        h.end_headers()
        h.wfile.write(raw)
        return True

    def content(self, h, path, admin=False):
        conn = http.client.HTTPConnection(self.backend.hostname, self.backend.port or 8080, timeout=120)
        try:
            headers = {'Cookie': self.session['cookie']}
            if h.headers.get('Range'):
                headers['Range'] = h.headers['Range']
            conn.request('GET', '/api/v1/admin' + ('' if admin else '/creator') + path, headers=headers)
            response = conn.getresponse()
            if response.status >= 400 and response.status != 416:
                if response.status == 401:
                    self.session = None
                raise BridgeError('素材文件无法读取，请重新连接后台', response.status)
            h.send_response(response.status)
            for key in ('Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges', 'ETag'):
                value = response.getheader(key)
                if value:
                    h.send_header(key, value)
            h.send_header('Cache-Control', 'private, no-cache')
            h.end_headers()
            while block := response.read(1024 * 1024):
                h.wfile.write(block)
            return True
        finally:
            conn.close()

    def handle(self, h):
        path = urlsplit(h.path).path
        if not path.startswith(PREFIX + '/'):
            return False
        try:
            authority = f'127.0.0.1:{h.server.server_port}'
            if h.headers.get('Host') != authority or h.headers.get('X-Creator-Request') != '1':
                raise BridgeError('请从本地创作台操作', 403)
            if h.command != 'GET' and h.headers.get('Origin') != 'http://' + authority:
                raise BridgeError('请求来源不正确', 403)
            route = path[len(PREFIX):]
            size = int(h.headers.get('Content-Length', '0'))
            maximum = MAX_BODY if route == '/media' else (201 * 1024 * 1024 if route in ('/wallpaper/assets', '/wallpaper/parallax-packages') else 11 * 1024 * 1024)
            if not 0 <= size <= maximum:
                raise BridgeError('创作数据或文件过大', 413)
            cookies = SimpleCookie(h.headers.get('Cookie', ''))
            token = cookies.get('CREATOR_DATA_SESSION')
            authenticated = bool(self.session and token and secrets.compare_digest(token.value, self.session['token']))
            if route == '/status' and h.command == 'GET':
                if authenticated:
                    try:
                        self.api('GET', 'creator/records/meta?limit=1')
                    except BridgeError:
                        authenticated = False
                return self.respond(h, {'connected': authenticated, 'activeTaskId': self.active})
            if route == '/connect' and h.command == 'POST':
                if self.active:
                    raise BridgeError('生成任务正在处理，完成后再切换连接', 409)
                n = json.loads(h.rfile.read(size))
                value, cookie = self.api('POST', 'sessions', n)
                token = secrets.token_urlsafe(32)
                self.session = {'cookie': cookie, 'csrf': value['csrfToken'], 'token': token}
                return self.respond(h, {'connected': True}, cookie=f'CREATOR_DATA_SESSION={token}; HttpOnly; SameSite=Strict; Path={PREFIX}')
            if not authenticated:
                raise BridgeError('请先连接创作数据后台；当前修改仅保留在浏览器草稿中', 401)
            if route == '/publish-session' and h.command == 'POST':
                if self.publishing is None:
                    raise BridgeError('发布助手未启动', 503)
                self.api('GET', 'creator/records/meta?limit=1')
                try:
                    cookie = self.publishing.connect_from_creator(self.session, self.backend)
                except Exception as error:
                    raise BridgeError(str(error), getattr(error, 'status', 500)) from error
                return self.respond(h, {'connected': True}, cookie=cookie)
            if route == '/wallpaper-categories' and h.command == 'GET':
                return self.respond(h, self.api('GET', 'categories'))
            import re
            asset_content = re.fullmatch(r'/wallpaper/assets/([1-9][0-9]*)/content', route)
            if asset_content and h.command == 'GET':
                return self.content(h, '/assets/' + asset_content[1] + '/content', admin=True)
            # The wallpaper adapter has a fixed route inventory and cannot select another API.
            wallpaper_routes = {
                '/wallpaper/capabilities': ('GET', 'creator/capabilities'),
                '/wallpaper/categories': ('GET', 'categories'),
                '/wallpaper/assets': ('POST', 'assets'),
                '/wallpaper/parallax-packages': ('POST', 'parallax-packages'),
                '/wallpaper/publications': ('GET,POST', 'creator/wallpaper-publications'),
            }
            match = re.fullmatch(r'/wallpaper/(assets|products|publications)/([1-9][0-9]*)(/retry|/publication-check)?', route)
            if match:
                kind, identifier, suffix = match.groups()
                prefixes = {'assets': 'assets', 'parallax-packages': 'parallax-packages', 'products': 'wallpapers', 'publications': 'creator/wallpaper-publications'}
                allowed = 'GET' if not suffix else 'POST'
                if suffix and not ((kind == 'publications' and suffix == '/retry') or (kind == 'products' and suffix == '/publication-check')):
                    raise BridgeError('接口不存在', 404)
                wallpaper_routes[route] = (allowed, prefixes[kind] + '/' + identifier + (suffix or ''))
            if route in wallpaper_routes:
                allowed, upstream = wallpaper_routes[route]
                if h.command not in allowed.split(','):
                    raise BridgeError('请求方式不正确', 405)
                query = urlsplit(h.path).query
                if query:
                    params = parse_qs(query, keep_blank_values=True)
                    if route != '/wallpaper/publications' or h.command != 'GET' or any(k not in ('clientProjectKey', 'page', 'pageSize') or len(v) != 1 for k, v in params.items()):
                        raise BridgeError('请求参数不正确', 400)
                    upstream += '?' + urlencode({k: v[0] for k, v in params.items()})
                fields = {'Idempotency-Key': h.headers.get('Idempotency-Key', ''), 'X-Request-Id': str(uuid.uuid4())}
                if route in ('/wallpaper/assets', '/wallpaper/parallax-packages'):
                    content_type = h.headers.get('Content-Type', '')
                    if not content_type.startswith('multipart/form-data;') or 'boundary=' not in content_type or not size:
                        raise BridgeError('请选择要上传的文件', 400)
                    fields['Content-Type'] = content_type
                    with tempfile.TemporaryFile() as source:
                        remaining = size
                        while remaining:
                            chunk = h.rfile.read(min(1024 * 1024, remaining))
                            if not chunk:
                                raise BridgeError('上传中断，请使用原文件重试')
                            source.write(chunk)
                            remaining -= len(chunk)
                        source.seek(0)
                        result = self.api('POST', upstream, source=source, size=size, headers=fields)
                else:
                    body = h.rfile.read(size) if size else b''
                    result = self.api(h.command, upstream, data=json.loads(body) if body else None, headers=fields)
                return self.respond(h, result, 202 if h.command == 'POST' and '/publications' in route else 200)
            if h.command == 'GET' and re.fullmatch(r'/media/[A-Za-z0-9_-]+/content', route):
                return self.content(h, route)
            import re
            if not re.fullmatch(r'/(records/(projects|templates|favorites|works|meta)(/[A-Za-z0-9_-]+)?|media(/[A-Za-z0-9_-]+)?|tasks(/[A-Za-z0-9_-]+(/action)?)?)', route):
                raise BridgeError('接口不存在', 404)
            if route == '/media' and h.command == 'POST':
                # Bound the input stream; HTTPConnection otherwise reads until EOF.
                with tempfile.TemporaryFile() as source:
                    remaining = size
                    while remaining:
                        chunk = h.rfile.read(min(1024 * 1024, remaining))
                        if not chunk:
                            raise BridgeError('素材上传中断，请重试')
                        source.write(chunk)
                        remaining -= len(chunk)
                    source.seek(0)
                    result = self.api('POST', 'creator/media', source=source, size=size, headers={key: h.headers.get(key, '') for key in ('X-Media-Id', 'X-Filename', 'X-Media-Metadata')})
            else:
                body = h.rfile.read(size) if size else b''
                n = json.loads(body) if body else None
                headers = {'If-Match': h.headers.get('If-Match', '')} if h.command in ('PUT', 'DELETE') else {}
                query = urlsplit(h.path).query
                if query and not (h.command == 'GET' and route.startswith('/records/')):
                    raise BridgeError('请求参数不正确', 400)
                result = self.api(h.command, 'creator' + route + ('?' + query if query else ''), data=n, headers=headers)
            return self.respond(h, result)
        except BridgeError as error:
            return self.respond(h, {'error': str(error), **error.details}, error.status)
        except (ValueError, TypeError):
            return self.respond(h, {'error': '创作请求格式无效'}, 400)
        except (BrokenPipeError, ConnectionResetError):
            return True
        except OSError:
            return self.respond(h, {'error': '本地创作服务暂时不可用'}, 503)

    def loop(self):
        while True:
            if self.session:
                try:
                    task = self.api('POST', 'creator/claim', {'runnerId': self.runner})
                    if task.get('id'):
                        self.execute(task)
                        continue
                except BridgeError:
                    pass
            time.sleep(2)

    def execute(self, task):
        self.active = task['id']
        stopped = threading.Event()
        cancelled = threading.Event()
        stage = ['正在读取任务素材']
        auth = self.session
        report_lock = threading.Lock()
        def report(state='RUNNING', **extra):
            with report_lock:
                value = self.api('POST', f"creator/tasks/{task['id']}/report", {'runnerId': self.runner, 'leaseToken': task['leaseToken'], 'attempt': task['attempt'], 'state': state, 'stage': stage[0], **extra}, session=auth)
                if value.get('state') == 'CANCEL_REQUESTED':
                    cancelled.set()
                return value
        def heartbeat():
            while not stopped.wait(15):
                try:
                    if report().get('state') == 'CANCEL_REQUESTED':
                        cancelled.set()
                except BridgeError:
                    cancelled.set()
                    return
        thread = threading.Thread(target=heartbeat, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory(prefix='qingjing-creator-task-') as temporary:
                folder = Path(temporary)
                payload = folder / 'input.zip'
                self.api('GET', f"creator/media/{task['payloadMediaId']}/content", target=payload, session=auth)
                import hashlib
                media = self.api('GET', f"creator/media/{task['payloadMediaId']}", session=auth)
                digest = hashlib.sha256()
                with payload.open('rb') as stream:
                    while chunk := stream.read(1024 * 1024):
                        digest.update(chunk)
                if digest.hexdigest() != media['sha256']:
                    raise BridgeError('生成素材包损坏，请重新生成')
                stage[0] = '等待本地编码'
                acquired = False
                try:
                    while not cancelled.is_set():
                        acquired = self.render_lock.acquire(timeout=1)
                        if acquired:
                            break
                    if cancelled.is_set():
                        raise BridgeError('任务已取消')
                    stage[0] = '本地正在编码视频'
                    report()
                    if cancelled.is_set():
                        raise BridgeError('任务已取消')
                    renderer = self.renderers[task['type']]
                    output = renderer(payload.read_bytes(), folder, cancelled.is_set)
                finally:
                    if acquired:
                        self.render_lock.release()
                if cancelled.is_set():
                    raise BridgeError('任务已取消')
                stage[0] = '正在保存生成的视频'
                report()
                if cancelled.is_set():
                    raise BridgeError('任务已取消')
                with output.open('rb') as source:
                    metadata = {'type': 'video', 'taskId': task['id'], 'inputHash': task['inputHash']}
                    media = self.api('POST', 'creator/media', source=source, size=output.stat().st_size, headers={'X-Media-Id': 'task-' + task['id'] + '-' + str(task['attempt']), 'X-Filename': quote('output.mp4'), 'X-Media-Metadata': quote(json.dumps(metadata))}, session=auth)
                report('CANCELLED' if cancelled.is_set() else 'SUCCEEDED', outputMediaIds=[media['id']], progress=100, stage='视频已生成')
        except Exception as error:
            try:
                report('CANCELLED' if cancelled.is_set() else 'FAILED', error=str(error)[:1000])
            except BridgeError:
                pass
        finally:
            stopped.set()
            thread.join(timeout=2)
            self.active = None

bridge = CreatorBridge()
