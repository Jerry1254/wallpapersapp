"""Loopback-only publishing bridge. Business records live behind the admin API."""
import http.client
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from urllib.parse import urlsplit
from http.cookies import SimpleCookie
from .setup import REVISION

HERE = Path(__file__).resolve().parent
RUNTIME = HERE.parents[2] / '.runtime' / 'creator-publishing'
PREFIX = '/creator-studio/api/distribution'
MAX_MEDIA = 2 * 1024 * 1024 * 1024


class BridgeError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


class PublishingBridge:
    def __init__(self):
        self.lock = threading.Lock()
        self.operations = {}
        self.session = None
        self.runner = None
        self.active = None
        self.preparing = False
        self.setup_message = ''
        self.started = False
        self.backend = urlsplit(os.environ.get('CREATOR_PUBLISH_API', 'http://127.0.0.1:8080'))
        if self.backend.scheme != 'http' or self.backend.hostname not in ('127.0.0.1', 'localhost'):
            raise ValueError('CREATOR_PUBLISH_API must be a loopback HTTP API')

    def start(self):
        with self.lock:
            if self.started:
                return
            RUNTIME.mkdir(parents=True, exist_ok=True, mode=0o700)
            os.chmod(RUNTIME, 0o700)
            runner_file = RUNTIME / 'runner-id'
            if not runner_file.exists():
                runner_file.write_text(str(uuid.uuid4()), encoding='ascii')
            self.runner = runner_file.read_text().strip()
            self.started = True
            threading.Thread(target=self.loop, daemon=True).start()

    def ready(self):
        marker = RUNTIME / 'revision'
        return (RUNTIME / 'venv/bin/python').exists() and marker.exists() and marker.read_text().strip() == REVISION and not self.preparing

    def api(self, method, path, data=None, stream=None, size=0, filename=None, download=None, session=None):
        current = session or self.session
        connection = http.client.HTTPConnection(self.backend.hostname, self.backend.port or 8080, timeout=90)
        headers = {'Accept': 'application/json'}
        if current:
            headers.update({'Cookie': current['cookie'], 'X-CSRF-Token': current['csrf']})
        body = json.dumps(data, ensure_ascii=False).encode() if data is not None else None
        if body is not None:
            headers.update({'Content-Type': 'application/json', 'Content-Length': str(len(body))})
        if stream is not None:
            body = stream
            headers.update({'Content-Type': 'application/octet-stream', 'Content-Length': str(size), 'X-Filename': filename})
        try:
            connection.request(method, '/api/v1/admin/' + path, body=body, headers=headers)
            response = connection.getresponse()
            if download is not None and response.status == 200:
                total = 0
                with Path(download).open('wb') as target:
                    while True:
                        block = response.read(1024 * 1024)
                        if not block:
                            break
                        total += len(block)
                        if total > MAX_MEDIA:
                            raise BridgeError('素材超过助手支持的大小')
                        target.write(block)
                return None
            raw = response.read(4 * 1024 * 1024)
            try:
                value = json.loads(raw) if raw else {}
            except ValueError:
                raise BridgeError('后台返回异常，请确认本地 API 已更新', 502)
            if response.status >= 400:
                if response.status == 401 and current is self.session:
                    self.session = None
                raise BridgeError(value.get('message') or value.get('detail') or '后台请求失败', response.status)
            if path == 'sessions' and method == 'POST':
                return value, response.getheader('Set-Cookie', '').split(';')[0]
            return value
        except (OSError, http.client.HTTPException):
            raise BridgeError('本地后台暂时无法连接，请启动 API 后再连接', 503)
        finally:
            connection.close()

    def respond(self, h, data, status=200, cookie=None):
        encoded = json.dumps(data, ensure_ascii=False).encode()
        h.send_response(status)
        h.send_header('Content-Type', 'application/json; charset=utf-8')
        h.send_header('Content-Length', str(len(encoded)))
        h.send_header('Cache-Control', 'no-store')
        if cookie:
            h.send_header('Set-Cookie', cookie)
        h.end_headers()
        h.wfile.write(encoded)

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
            self.start()
            route = path[len(PREFIX):]
            size = int(h.headers.get('Content-Length', '0'))
            if not 0 <= size <= (MAX_MEDIA if route == '/media' else 256 * 1024):
                raise BridgeError('请求体过大', 413)
            n = {}
            if h.command != 'GET' and route != '/media':
                raw = h.rfile.read(size)
                n = json.loads(raw) if raw else {}
            cookies = SimpleCookie(h.headers.get('Cookie', ''))
            token = cookies.get('CREATOR_PUBLISH_SESSION')
            authenticated = bool(self.session and token and secrets.compare_digest(token.value, self.session['token']))
            if route == '/status' and h.command == 'GET':
                return self.respond(h, {'connected': authenticated, 'ready': self.ready(), 'runnerId': self.runner, 'preparing': self.preparing, 'message': self.setup_message, 'active': self.active}) or True
            if route == '/connect' and h.command == 'POST':
                if self.active:
                    raise BridgeError('当前正在执行任务，请结束后再切换连接', 409)
                value, cookie = self.api('POST', 'sessions', n)
                token = secrets.token_urlsafe(32)
                self.session = {'cookie': cookie, 'csrf': value['csrfToken'], 'token': token}
                return self.respond(h, {'ok': True}, cookie=f'CREATOR_PUBLISH_SESSION={token}; HttpOnly; SameSite=Strict; Path={PREFIX}') or True
            if not authenticated:
                raise BridgeError('请先连接本地管理后台', 401)
            if route == '/disconnect' and h.command == 'POST':
                if self.active:
                    raise BridgeError('任务正在执行，暂时不能断开', 409)
                self.api('DELETE', 'sessions')
                self.session = None
                return self.respond(h, {'ok': True}) or True
            if route == '/prepare' and h.command == 'POST':
                if self.preparing or self.active:
                    raise BridgeError('助手正在工作，请稍后', 409)
                self.preparing = True
                self.setup_message = '正在准备独立发布环境和浏览器…'
                threading.Thread(target=self.prepare, daemon=True).start()
                return self.respond(h, {'ok': True}) or True
            if route.startswith('/operations/') and h.command == 'GET':
                op = route.rsplit('/', 1)[-1]
                if op not in self.operations:
                    raise BridgeError('登录会话已结束', 404)
                return self.respond(h, self.operations[op]) or True
            parts = route.strip('/').split('/')
            if len(parts) == 3 and parts[0] == 'accounts' and parts[2] in ('login', 'check', 'analytics') and h.command == 'POST':
                account = next((a for a in self.api('GET', 'distribution/accounts') if a['id'] == parts[1]), None)
                if not account or account['runnerId'] != self.runner:
                    raise BridgeError('该账号需要在原来登录的电脑操作')
                with self.lock:
                    if self.active or not self.ready():
                        raise BridgeError('请先准备助手，并等待当前任务结束', 409)
                    op = str(uuid.uuid4())
                    self.active = op
                    self.operations[op] = {'status': 'running', 'message': '正在打开登录窗口，请扫码并确认账号'}
                threading.Thread(target=self.login, args=(op, account, parts[2]), daemon=True).start()
                return self.respond(h, {'operationId': op}) or True
            allowed = (route in ('/accounts', '/jobs', '/batches', '/media', '/media/reuse', '/metrics') or
                       (len(parts) == 2 and parts[0] == 'accounts') or
                       (len(parts) == 3 and parts[0] == 'jobs' and parts[2] == 'action'))
            if not allowed:
                raise BridgeError('接口不存在', 404)
            if route == '/batches' and not self.ready():
                raise BridgeError('请先准备发布助手', 409)
            if route == '/accounts' and h.command == 'POST':
                n['runnerId'] = self.runner
            if len(parts) == 2 and parts[0] == 'accounts' and h.command == 'DELETE' and self.active:
                raise BridgeError('请等待当前登录或发布任务结束后再移除账号', 409)
            if len(parts) == 2 and parts[0] == 'accounts' and h.command == 'PUT':
                n = {key: n[key] for key in ('name', 'group') if key in n}
            if route == '/media':
                if h.command != 'POST' or not size:
                    raise BridgeError('请上传素材')
                h.connection.settimeout(90)
                with tempfile.TemporaryFile(dir=RUNTIME) as body:
                    remaining = size
                    while remaining:
                        block = h.rfile.read(min(1024 * 1024, remaining))
                        if not block:
                            raise BridgeError('素材上传中断')
                        body.write(block)
                        remaining -= len(block)
                    body.seek(0)
                    value = self.api('POST', 'distribution/media', stream=body, size=size, filename=h.headers.get('X-Filename', ''))
            else:
                value = self.api(h.command, 'distribution' + route, n if h.command in ('POST', 'PUT') else None)
                if h.command == 'DELETE' and len(parts) == 2 and parts[0] == 'accounts':
                    (RUNTIME / 'sessions' / (str(uuid.UUID(parts[1])) + '.enc')).unlink(missing_ok=True)
            self.respond(h, value)
        except BridgeError as error:
            self.respond(h, {'error': str(error)}, error.status)
        except (ValueError, KeyError):
            self.respond(h, {'error': '请求参数无效'}, 400)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.respond(h, {'error': '发布助手操作失败，请重新连接后重试'}, 500)
        return True

    def prepare(self):
        try:
            with (RUNTIME / 'setup.log').open('w') as log:
                result = subprocess.run([sys.executable, str(HERE / 'setup.py')], stdout=log, stderr=log, timeout=1200)
            self.setup_message = '发布助手已准备好' if result.returncode == 0 else '准备失败，请检查网络；详情在本机 .runtime/creator-publishing/setup.log'
        except Exception:
            self.setup_message = '准备超时，请检查网络后重试'
        finally:
            self.preparing = False

    def execute(self, request, on_event):
        env = dict(os.environ, PYTHONPATH=str(RUNTIME / 'social-auto-upload'), PYTHONUNBUFFERED='1')
        env.pop('SAU_XHS_CREATOR_BASE_URL', None)
        proc = subprocess.Popen([str(RUNTIME / 'venv/bin/python'), str(HERE / 'worker.py')], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=env, start_new_session=True)
        try:
            proc.stdin.write(json.dumps(request) + '\n')
            proc.stdin.flush()
            result = None
            # Worker owns a bounded timeout; only structured events leave the process.
            for line in proc.stdout:
                if not line.startswith('CREATOR_EVENT '):
                    continue
                event = json.loads(line[len('CREATOR_EVENT '):])
                on_event(event)
                if event.get('event') in ('commit','identity'):
                    proc.stdin.write('ok\n')
                    proc.stdin.flush()
                if event.get('event') == 'result':
                    result = event
            proc.wait(timeout=10)
            if result is None:
                raise BridgeError('浏览器进程中断，请核对任务状态')
            return result
        finally:
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
            proc.stdin.close()
            proc.stdout.close()

    def login(self, op, account, mode):
        try:
            def event(value):
                if value.get('event') == 'qr':
                    self.operations[op] = {'status': 'running', 'message': '请用对应平台 App 扫码', 'qr': value['qr']}
                elif value.get('event') == 'progress':
                    self.operations[op] = {'status':'running','message':value['message']}
                elif value.get('event') == 'identity':
                    self.api('PUT','distribution/accounts/'+account['id'],{'status':'unverified','runnerId':self.runner,'identity':value['identity']})
                    self.operations[op] = {'status':'running','message':'平台身份已通过核对，正在保存本机登录信息…'}
            result = self.execute({'mode': mode, 'account': account, 'runtime': str(RUNTIME)}, event)
            if mode == 'analytics':
                if result.get('status') == 'ready':
                    self.api('PUT', 'distribution/accounts/' + account['id'] + '/metrics', {'metrics': result['metrics'], 'sourceUrl': result['sourceUrl']})
                self.operations[op] = {'status': result['status'], 'message': result['message']}
                return
            ready = result.get('status') == 'ready'
            update={'status':'ready' if ready else 'unverified' if result.get('status')=='needs_input' else 'expired','runnerId':self.runner}
            if ready:
                update['identity']=result['identity']
            self.api('PUT', 'distribution/accounts/' + account['id'], update)
            self.operations[op] = {'status': 'ready' if ready else 'failed', 'message': result.get('message', '登录未完成')}
        except Exception as error:
            self.operations[op] = {'status': 'failed', 'message': str(error)}
        finally:
            self.active = None
            # Bound transient QR/login data retained in memory.
            while len(self.operations) > 20:
                self.operations.pop(next(iter(self.operations)))

    def loop(self):
        while True:
            time.sleep(3)
            with self.lock:
                if not self.session or not self.ready() or self.active:
                    continue
                self.active = 'claiming'
            try:
                job = self.api('POST', 'distribution/claim', {'runnerId': self.runner})
                if job:
                    self.active = job['id']
                    self.run_job(job)
            except Exception:
                pass
            finally:
                self.active = None

    def run_job(self, job):
        status = {'value': 'running', 'message': '正在下载素材', 'lost': False}
        guard = threading.Lock()
        stop = threading.Event()
        def report(value, message, result_url=''):
            with guard:
                if value is None:
                    if stop.is_set():
                        return
                    value, message = status['value'], status['message']
                if value == 'running' and status['value'] == 'submitting':
                    value = 'submitting'
                self.api('POST', f'distribution/jobs/{job["id"]}/report', {'leaseToken': job['leaseToken'], 'status': value, 'message': message, 'resultUrl': result_url})
                status.update(value=value, message=message)
        def heartbeat():
            while not stop.wait(15):
                try:
                    report(None, None)
                except Exception:
                    status['lost'] = True
                    return
        threading.Thread(target=heartbeat, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=RUNTIME, prefix='job-') as folder:
                paths = {}
                for media in job['files']:
                    path = Path(folder) / (str(uuid.UUID(media['id'])) + '.' + media['extension'])
                    self.api('GET', 'distribution/media/' + media['id'], download=path)
                    paths[media['id']] = str(path)
                def event(value):
                    if status['lost']:
                        raise BridgeError('与后台连接中断，任务已停止')
                    kind = value.get('event')
                    if kind == 'commit':
                        report('submitting', '正在向平台提交；请勿重复发布')
                    elif kind == 'progress':
                        report(status['value'], value['message'])
                result = self.execute({'mode': 'publish', 'runtime': str(RUNTIME), 'job': job, 'paths': paths}, event)
                stop.set()
                report(result['status'], result['message'], result.get('url', ''))
        except Exception as error:
            stop.set()
            try:
                report('uncertain' if status['value'] == 'submitting' else 'failed', str(error)[:500])
            except Exception:
                pass
        finally:
            stop.set()


bridge = PublishingBridge()
