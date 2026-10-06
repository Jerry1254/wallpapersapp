#!/usr/bin/env python3
"""Start or check the existing LOCAL_DEV creator environment without rebuilding or resetting data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import signal
import socket
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import webbrowser

ROOT = Path(__file__).resolve().parent.parent
RUNTIME = ROOT / '.runtime'
URL = 'http://127.0.0.1:8176/creator-studio/'
SOURCES = ['wallpaper-tools/creator-studio/render-server.py', 'wallpaper-tools/creator-studio/creator-bridge.py', 'wallpaper-tools/creator-studio/publishing/bridge.py']
IDENTITY = {'git-commit':'QJ_DEPLOYMENT_GIT_COMMIT','artifact-sha256':'QJ_DEPLOYMENT_ARTIFACT_SHA256','source-sha256':'QJ_DEPLOYMENT_SOURCE_SHA256','contract-version':'QJ_API_CONTRACT_VERSION','flyway-version':'QJ_FLYWAY_VERSION'}


def code_hash():
    digest = hashlib.sha256()
    for name in SOURCES:
        digest.update((ROOT / name).read_bytes())
    return digest.hexdigest()


def read(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={'X-Creator-Request': '1'}), timeout=3) as response:
            return json.load(response)
    except (OSError, ValueError):
        return None


def configuration():
    envfile = RUNTIME / 'local-api/compose.env'
    override = RUNTIME / 'local-api/creator-compose.json'
    if not envfile.is_file() or not override.is_file():
        raise ValueError('本地后台配置尚未准备好，请先完成本机环境初始化。此入口不会创建或重置管理员和数据。')
    values = {}
    for line in envfile.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        parts = shlex.split(value)
        values[key.strip()] = parts[0] if parts else ''
    expected = {'QJ_MYSQL_DATABASE': 'wallpaper_app', 'QJ_API_HOST_PORT': '8080', 'QJ_MYSQL_HOST_PORT': '3307', 'QJ_REDIS_HOST_PORT': '6380'}
    if any(values.get(key) != fallback for key, fallback in expected.items()):
        raise ValueError('当前配置不是创作台的 LOCAL_DEV 环境，已停止启动。')
    api = json.loads(override.read_text())['services']['api']
    if api['environment'].get('QJ_ENVIRONMENT_ID') != 'LOCAL_DEV' or api['environment'].get('QJ_DEPLOYMENT_STAGE') != 'LOCAL' or not api['image'].startswith('qingjing-wallpaper-local-api:creator-'):
        raise ValueError('后台启动配置不属于本地创作台，已停止启动。')
    if not all(api['environment'].get(key) for key in IDENTITY.values()):
        raise ValueError('本地后台配置缺少完整版本信息，请先更新后台。')
    return envfile, override, api


def valid_api(info, api):
    app = (info or {}).get('app', {})
    return app.get('name') == 'qingjing-wallpaper-api' and app.get('environment-id') == 'LOCAL_DEV' and app.get('deployment-stage') == 'LOCAL' and all(app.get(key) == api['environment'][envkey] for key,envkey in IDENTITY.items())


def wait_for(check, description):
    for _ in range(90):
        if check():
            return
        time.sleep(2)
    raise ValueError(description + '未就绪，请检查本地运行日志。')


def restart_server():
    status = read(URL + 'api/local-status')
    if not status or status.get('name') != 'qingjing-creator-studio':
        raise ValueError('无法确认当前创作服务进程，已保留现有服务。')
    if status.get('rendering'):
        raise ValueError('创作台仍在生成视频，请完成后再重启。')
    for route in ['creator/status', 'distribution/status']:
        value = read(URL + 'api/' + route)
        if value is None or any(value.get(key) for key in ['activeTaskId', 'active', 'preparing']):
            raise ValueError('创作台仍有生成、登录或发布操作，请完成后再重启。')
    pid = int(status['pid'])
    args = subprocess.check_output(['ps', '-p', str(pid), '-o', 'args='], text=True)
    if str(ROOT / SOURCES[0]) not in args:
        raise ValueError('运行进程不属于当前项目，已保留现有服务。')
    os.kill(pid, signal.SIGTERM)
    wait_for(lambda: read(URL + 'api/local-status') is None, '旧服务退出')


def start_server():
    ffmpeg = RUNTIME / 'media-tools/ffmpeg'
    ffprobe = RUNTIME / 'media-tools/ffprobe'
    if not (ffmpeg.is_file() and ffprobe.is_file()) and not (shutil.which('ffmpeg') and shutil.which('ffprobe')):
        raise ValueError('本机视频工具尚未准备好，未启动创作台。')
    environment = os.environ.copy()
    environment.update(PATH=(str(ffmpeg.parent) + os.pathsep if ffmpeg.is_file() and ffprobe.is_file() else '') + environment.get('PATH', ''), CREATOR_DATA_API='http://127.0.0.1:8080', CREATOR_PUBLISH_API='http://127.0.0.1:8080')
    runtime_python = RUNTIME / 'creator-publishing/venv/bin/python'
    python = str(runtime_python) if runtime_python.is_file() else sys.executable
    with (RUNTIME / 'creator-studio-server.log').open('a') as log:
        process = subprocess.Popen([python, str(ROOT / SOURCES[0]), '--port', '8176'], cwd=ROOT, env=environment, stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
    (RUNTIME / 'creator-studio-server.pid').write_text(str(process.pid))
    wait_for(lambda: (read(URL + 'api/local-status') or {}).get('codeHash') == code_hash(), '创作台')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true', help='只检查，不启动或修改服务')
    parser.add_argument('--restart', action='store_true', help='没有活动任务时重启创作台；后台数据保留')
    parser.add_argument('--no-open', action='store_true')
    args = parser.parse_args()
    envfile, override, api = configuration()
    info = read('http://127.0.0.1:8080/actuator/info')
    ready = read('http://127.0.0.1:8080/actuator/health/readiness')
    server = read(URL + 'api/local-status')
    if args.check:
        ok = valid_api(info, api) and (ready or {}).get('status') == 'UP' and (server or {}).get('codeHash') == code_hash()
        print('本地创作台与后台已就绪。' if ok else '部分本地服务未就绪，或创作服务需要更新。')
        return 0 if ok else 1
    if info and not valid_api(info, api):
        raise ValueError('8080 端口上的后台与本地创作台版本不一致，请先更新本地后台。')
    if not valid_api(info, api) or (ready or {}).get('status') != 'UP':
        print('正在启动本地数据库和后台，保留现有数据…', flush=True)
        subprocess.run(['docker', 'compose', '--env-file', str(envfile), '-f', str(ROOT / 'infra/local/compose.yaml'), '-f', str(override), 'up', '-d', '--no-build', 'mysql', 'redis', 'api'], check=True)
        wait_for(lambda: valid_api(read('http://127.0.0.1:8080/actuator/info'), api) and (read('http://127.0.0.1:8080/actuator/health/readiness') or {}).get('status') == 'UP', '本地后台')
    if server and args.restart:
        restart_server()
        server = None
    if server and server.get('codeHash') != code_hash():
        raise ValueError('创作服务需要更新；完成当前操作后运行本入口并加 --restart。')
    if not server:
        with socket.socket() as probe:
            probe.settimeout(1)
            if probe.connect_ex(('127.0.0.1', 8176)) == 0:
                raise ValueError('8176 端口已有旧版或其他服务。请先完成当前操作并更新创作服务；现有进程未被改动。')
        start_server()
    print('创作台已就绪：' + URL + '?v=0.13.0')
    print('进入网页后连接一次本地管理后台；平台账号需要本人扫码。')
    if not args.no_open:
        webbrowser.open(URL + '?v=0.13.0')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print('启动未完成：' + str(error), file=sys.stderr)
        sys.exit(1)
