"""Local creator workbench and durable FFmpeg job runner.

Start with: python3 wallpaper-tools/creator-studio/render-server.py
Temporary encoding inputs are removed after each task; durable files use the admin storage adapter.
"""
import argparse
import hashlib
import ctypes
import functools
import sys
import io
import json
import math
import os
from pathlib import Path
import select
import shutil
import socket
import subprocess
import tempfile
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import zipfile
from publishing.bridge import bridge as publishing_bridge
import importlib.util
_creator_spec = importlib.util.spec_from_file_location("creator_bridge", Path(__file__).with_name("creator-bridge.py"))
_creator_module = importlib.util.module_from_spec(_creator_spec)
_creator_spec.loader.exec_module(_creator_module)
creator_bridge = _creator_module.bridge
creator_bridge.publishing = publishing_bridge
SERVER_CODE_HASH = hashlib.sha256(b''.join(path.read_bytes() for path in [Path(__file__), Path(__file__).with_name('creator-bridge.py'), Path(__file__).parent / 'publishing/bridge.py'])).hexdigest()


ROOT = Path(__file__).resolve().parent
FPS = 30
MAX_UPLOAD = 512 * 1024 * 1024
MAX_EXPANDED = 1024 * 1024 * 1024
RENDER_LOCK = threading.Lock()
DEMO_FILES = ['background.jpg', 'buildings.png', 'character.png', 'light.png', 'debris.png']


def executable(name):
    configured = os.environ.get('CREATOR_' + name.upper())
    found = configured or shutil.which(name) or str(Path.home() / '.homebrew/bin' / name)
    if not Path(found).is_file():
        raise ValueError('本地视频导出需要安装 FFmpeg')
    return found


class ExportCancelled(Exception):
    pass


def run(args, timeout=180, cancelled=None):
    deadline = time.monotonic() + timeout
    with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
        try:
            while True:
                if cancelled and cancelled():
                    raise ExportCancelled()
                if time.monotonic() >= deadline:
                    raise ValueError('视频导出超时，请缩短片段后重试')
                try:
                    stdout, _ = process.communicate(timeout=.25)
                    break
                except subprocess.TimeoutExpired:
                    continue
        except BaseException:
            process.kill()
            process.communicate()
            raise
        if process.returncode:
            raise ValueError('视频无法编码，请确认素材能够正常播放')
        return stdout


def number(value, low, high, label):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError(label + '无效')
    return value


def read_job(payload, folder):
    try:
        archive = zipfile.ZipFile(io.BytesIO(payload))
        if len(archive.infolist()) > 205 or sum(info.file_size for info in archive.infolist()) > MAX_EXPANDED:
            raise ValueError('素材包过大，请分批导出')
        manifest_info = archive.getinfo('timeline.json')
        if manifest_info.file_size > 1024 * 1024:
            raise ValueError('时间轴数据过大')
        job = json.loads(archive.read(manifest_info))
        if job.get('version') != 1 or job.get('fps') != FPS:
            raise ValueError('时间轴版本不兼容，请刷新页面')
        profile = job['profile']
        for key in ['width', 'height']:
            value = number(profile.get(key), 64, 8192, '成片尺寸')
            if int(value) != value or value % 2:
                raise ValueError('MP4 的宽高需为偶数')
        if profile['width'] * profile['height'] > 33554432:
            raise ValueError('成片总像素不能超过 3200 万')
        number(profile.get('scale'), 1, 4, '画面缩放')
        for key in ['x', 'y']:
            number(profile.get(key), -50, 50, '画面位置')
        sources = job['sources']
        clips = job['clips']
        if not isinstance(sources, list) or not 1 <= len(sources) <= 200 or not isinstance(clips, list) or not 1 <= len(clips) <= 200:
            raise ValueError('时间轴需包含 1–200 个片段')
        paths = []
        for index, source in enumerate(sources):
            if source.get('demo') is True:
                paths.append(None)
                continue
            name = source['path']
            if name not in {f'media/{index}{ext}' for ext in ['.mp4', '.mov', '.webm', '.png', '.jpg', '.jpeg', '.webp']}:
                raise ValueError('素材文件路径无效')
            info = archive.getinfo(name)
            if not 0 < info.file_size <= MAX_UPLOAD:
                raise ValueError('素材文件大小无效')
            target = folder / f'source-{index}{Path(name).suffix}'
            with archive.open(info) as incoming, target.open('wb') as outgoing:
                shutil.copyfileobj(incoming, outgoing)
            paths.append(target)
        elapsed = 0
        for clip in clips:
            source = clip.get('source')
            if isinstance(source, bool) or not isinstance(source, int) or not 0 <= source < len(paths):
                raise ValueError('找不到时间轴素材')
            if clip.get('kind') not in ['video', 'image']:
                raise ValueError('时间轴只支持图片和视频')
            start = number(clip.get('start'), 0, 86400, '片段入点')
            end = number(clip.get('end'), start + 1 / FPS - 1e-8, 86400, '片段出点')
            speed = number(clip.get('speed'), .01, 64, '片段速度')
            elapsed += (end - start) / speed
        if elapsed > 600:
            raise ValueError('当前本地导出支持 10 分钟以内的成片')
        return job, paths
    except (KeyError, TypeError, AttributeError, json.JSONDecodeError, zipfile.BadZipFile, NotImplementedError) as error:
        raise ValueError('导出素材包不完整，请重新打开导出窗口') from error
    finally:
        if 'archive' in locals():
            archive.close()


def inspect_media(path, cancelled=None):
    data = json.loads(run([executable('ffprobe'), '-v', 'error', '-protocol_whitelist', 'file,pipe', '-select_streams', 'v:0',
                           '-show_entries', 'stream=width,height,duration:format=duration', '-of', 'json', str(path)], 20, cancelled))
    if not data.get('streams'):
        raise ValueError('素材中没有可用画面')
    return data


def encode_clip(clip, source, path, profile, frames, output, cancelled=None):
    w, h = profile['width'], profile['height']
    duration = frames / FPS
    scale = profile['scale']
    pan_x, pan_y = profile['x'] * w / 100, profile['y'] * h / 100
    args = [executable('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-filter_complex_threads', '1']
    filters = [f'color=c=0x17191d:s={w}x{h}:r={FPS}:d={duration:.10f}[base]']
    if source.get('demo'):
        # Match the layered sample used by the canvas preview, including its source-time motion.
        t = '0' if clip['kind'] == 'image' else f"({clip['start']:.10f}+t*{clip['speed']:.10f})"
        fit = max(w / 2048, h / 2048) * scale
        size = math.ceil(2048 * fit * 1.18 / 2) * 2
        previous = 'base'
        for index, name in enumerate(DEMO_FILES):
            args += ['-framerate', str(FPS), '-threads', '1', '-i', str(ROOT / 'assets' / name)]
            x_motion = [ -2, 1, 4, 1, 7 ][index] / 100 * 2048 * fit
            y_motion = (2 if index == 2 else 1) / 100 * 2048 * fit
            x = f'({w}-{size})/2+{pan_x:.8f}+sin({t}*2*PI/5)*{x_motion:.8f}'
            y = f'({h}-{size})/2+{pan_y:.8f}+cos({t}*2*PI/5)*{y_motion:.8f}'
            filters.append(f'[{index}:v]scale={size}:{size},setsar=1,format=rgba,loop=loop=-1:size=1:start=0,setpts=N/({FPS}*TB)[layer{index}]')
            if index == 3:
                filters += [f'color=c=black:s={w}x{h}:r={FPS}:d={duration:.10f}[lightbase]',
                            f'[lightbase][layer3]overlay=x=\'{x}\':y=\'{y}\':shortest=1:format=auto[light]',
                            f'[{previous}][light]blend=all_mode=screen:all_opacity=0.25[scene{index}]']
            else:
                filters.append(f'[{previous}][layer{index}]overlay=x=\'{x}\':y=\'{y}\':shortest=1:format=auto[scene{index}]')
            previous = f'scene{index}'
        filters.append(f'[{previous}]scale=out_range=tv,format=yuv420p,setparams=range=limited[out]')
    else:
        metadata = inspect_media(path, cancelled)
        if clip['kind'] == 'image':
            args += ['-framerate', str(FPS), '-threads', '1', '-protocol_whitelist', 'file,pipe', '-i', str(path)]
            timing = 'setpts=PTS-STARTPTS'
        else:
            source_duration = float(metadata['streams'][0].get('duration') or metadata.get('format', {}).get('duration') or 0)
            if source_duration and clip['end'] > source_duration + 1 / FPS:
                raise ValueError('片段超出了原视频长度，请重新调整时间轴')
            args += ['-threads', '2', '-protocol_whitelist', 'file,pipe', '-i', str(path)]
            timing = f"trim=start={clip['start']:.10f}:end={clip['end']:.10f},setpts=(PTS-STARTPTS)/{clip['speed']:.10f}"
        fit = f'max({w}/iw,{h}/ih)*{scale:.10f}'
        tail = f'loop=loop=-1:size=1:start=0,setpts=N/({FPS}*TB)' if clip['kind'] == 'image' else f'tpad=stop_mode=clone:stop_duration={duration:.10f}'
        filters += [f"[0:v]{timing},fps={FPS},scale=w='ceil(iw*{fit}/2)*2':h='ceil(ih*{fit}/2)*2',setsar=1,{tail}[media]",
                    f"[base][media]overlay=x='(W-w)/2+{pan_x:.8f}':y='(H-h)/2+{pan_y:.8f}':shortest=1:format=auto,scale=out_range=tv,format=yuv420p,setparams=range=limited[out]"]
    # Concat copies the first clip's color metadata. Normalize JPEG, video and
    # demo frames to limited range so later clips keep their original colors.
    args += ['-filter_complex', ';'.join(filters), '-map', '[out]', '-an', '-map_metadata', '-1',
             '-c:v', 'libx264', '-preset', 'fast', '-crf', '18', '-threads', '2', '-color_range', 'tv', '-r', str(FPS),
             '-frames:v', str(frames), '-fps_mode', 'cfr', '-video_track_timescale', '15360',
             '-movflags', '+faststart', str(output)]
    run(args, cancelled=cancelled)


def render(payload, folder, cancelled=None):
    job, paths = read_job(payload, folder)
    elapsed, emitted = 0, 0
    parts = []
    for index, clip in enumerate(job['clips']):
        if cancelled and cancelled():
            raise ExportCancelled()
        elapsed += (clip['end'] - clip['start']) / clip['speed']
        boundary = math.floor(elapsed * FPS + .5)
        if index == len(job['clips']) - 1:
            boundary = max(1, boundary)
        frames = boundary - emitted
        if frames <= 0:
            continue
        part = folder / f'clip-{index}.mp4'
        encode_clip(clip, job['sources'][clip['source']], paths[clip['source']], job['profile'], frames, part, cancelled)
        parts.append(part)
        emitted = boundary
    output = folder / 'timeline.mp4'
    if len(parts) == 1:
        parts[0].rename(output)
    else:
        listing = folder / 'concat.txt'
        listing.write_text(''.join(f"file '{part.name}'\n" for part in parts), encoding='utf-8')
        run([executable('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y', '-f', 'concat', '-safe', '1',
             '-i', str(listing), '-map', '0:v:0', '-an', '-c', 'copy', '-movflags', '+faststart', str(output)], cancelled=cancelled)
    return output


def render_content(payload, folder, cancelled=None):
    """Mux directly encoded canvas video, or encode archived frames, and mix composition audio."""
    try:
        archive = zipfile.ZipFile(io.BytesIO(payload))
        entries = archive.infolist()
        if len(entries) > 2000 or sum(info.file_size for info in entries) > MAX_EXPANDED:
            raise ValueError('内容数据过大，请缩短视频')
        if archive.getinfo('content.json').file_size > 1024 * 1024:
            raise ValueError('内容配置过大')
        job = json.loads(archive.read('content.json'))
        if job.get('version') != 1:
            raise ValueError('内容版本不兼容，请刷新页面')
        fps = number(job.get('fps'), 24, 60, '内容帧率')
        if fps not in [24, 25, 30, 50, 60]:
            raise ValueError('内容帧率无效')
        fps = int(fps)
        width = number(job.get('width'), 64, 4096, '内容宽度')
        height = number(job.get('height'), 64, 4096, '内容高度')
        frames = number(job.get('frames'), 1, 30 * fps, '内容帧数')
        if any(int(v) != v for v in [width, height, frames]) or width % 2 or height % 2:
            raise ValueError('内容尺寸和帧数无效')
        quality = job.get('quality', 'standard')
        if quality not in ['standard', 'high', 'custom']:
            raise ValueError('输出画质无效')
        bitrate = number(job.get('bitrate', 8), .5, 100, '视频码率')
        video = job.get('video')
        direct = video is not None
        if direct:
            if not isinstance(video, dict) or video.get('path') != 'video.h264' or video.get('codec') != 'h264':
                raise ValueError('视频编码数据无效')
            info = archive.getinfo('video.h264')
            if not 0 < info.file_size <= MAX_UPLOAD:
                raise ValueError('视频编码文件大小无效')
            encoded = folder / 'video.h264'
            encoded.write_bytes(archive.read(info))
            metadata = json.loads(run([executable('ffprobe'), '-v', 'error', '-f', 'h264', '-select_streams', 'v:0',
                                       '-show_entries', 'stream=codec_name,width,height,has_b_frames,pix_fmt',
                                       '-of', 'json', str(encoded)], cancelled=cancelled))
            streams = metadata.get('streams', [])
            if not streams or streams[0].get('codec_name') != 'h264' or streams[0].get('width') != width or streams[0].get('height') != height:
                raise ValueError('视频编码尺寸不匹配')
            if streams[0].get('has_b_frames', 0) or streams[0].get('pix_fmt') not in ['yuv420p', 'yuvj420p']:
                raise ValueError('当前视频编码格式需要切换兼容生成方式')
        else:
            for index in range(int(frames)):
                if cancelled and cancelled():
                    raise ExportCancelled()
                name = f'frames/{index:05d}.jpg'
                info = archive.getinfo(name)
                if not 0 < info.file_size < 16 * 1024 * 1024:
                    raise ValueError('视频画面文件无效')
                (folder / f'{index:05d}.jpg').write_bytes(archive.read(info))
        audio = job.get('audio', [])
        if not isinstance(audio, list) or len(audio) > 100:
            raise ValueError('声音片段数量无效')
        args = [executable('ffmpeg'), '-hide_banner', '-loglevel', 'error', '-y']
        if direct:
            args += ['-fflags', '+genpts', '-r', str(fps), '-f', 'h264', '-i', str(encoded)]
        else:
            args += ['-framerate', str(fps), '-i', str(folder / '%05d.jpg')]
        filters = []
        mixed_audio = []
        total = frames / fps
        for index, clip in enumerate(audio):
            path = clip['path']
            extensions = ['mp4', 'mov', 'webm'] if clip.get('videoSource') is True else ['mp3', 'm4a', 'wav', 'aac', 'ogg', 'flac', 'mp4', 'webm']
            if path not in {f'audio/{index}.{ext}' for ext in extensions}:
                raise ValueError('音乐路径无效')
            start = number(clip.get('start'), 0, total, '音乐开始时间')
            duration = number(clip.get('duration'), 1e-7, total, '音乐持续时间')
            source_in = number(clip.get('sourceIn'), 0, 36000, '音乐源起点')
            loop_in = number(clip.get('loopIn', source_in), 0, source_in, '音乐循环起点')
            speed = number(clip.get('speed'), .25, 4, '音乐速度')
            volume = number(clip.get('volume'), 0, 1, '音乐音量')
            fade_in = number(clip.get('fadeIn'), 0, 5, '音乐淡入')
            fade_out = number(clip.get('fadeOut'), 0, 5, '音乐淡出')
            info = archive.getinfo(path)
            if not 0 < info.file_size <= MAX_UPLOAD:
                raise ValueError('音乐文件大小无效')
            target = folder / f'audio-{index}{Path(path).suffix}'
            target.write_bytes(archive.read(info))
            metadata = json.loads(run([executable('ffprobe'), '-v', 'error', '-select_streams', 'a:0',
                                       '-show_entries', 'stream=sample_rate:format=duration', '-of', 'json', str(target)], cancelled=cancelled))
            if not metadata.get('streams'):
                if clip.get('videoSource') is True:
                    continue
                raise ValueError('音频素材中没有可读取的声音')
            sample_rate = int(metadata['streams'][0]['sample_rate'])
            source_duration = float(metadata['format']['duration'])
            if loop_in >= source_duration:
                raise ValueError('音乐起点超过素材时长')
            args += ['-i', str(target)]
            head = f'atrim=start={loop_in},asetpts=PTS-STARTPTS'
            if clip.get('fill') == 'loop':
                head += f',aloop=loop=-1:size={max(1, round((source_duration-loop_in)*sample_rate))}'
            head += f',atrim=start={source_in-loop_in},asetpts=PTS-STARTPTS'
            tempo = []
            while speed > 2:
                tempo.append('atempo=2')
                speed /= 2
            while speed < .5:
                tempo.append('atempo=0.5')
                speed *= 2
            tempo.append(f'atempo={speed}')
            filters.append(f'[{len(mixed_audio) + 1}:a]{head},{",".join(tempo)},apad,atrim=duration={duration},'
                           f'asetpts=PTS-STARTPTS,volume={volume},afade=t=in:d={min(fade_in, duration)},'
                           f'afade=t=out:st={max(0, duration - fade_out)}:d={min(fade_out, duration)},'
                           f'adelay={round(start * 1000)}:all=1[a{index}]')
            mixed_audio.append(f'[a{index}]')
        if mixed_audio:
            filters.append(''.join(mixed_audio) +
                           f'amix=inputs={len(mixed_audio)}:normalize=0,apad,atrim=duration={total}[music]')
            args += ['-filter_complex', ';'.join(filters), '-map', '0:v:0', '-map', '[music]', '-c:a', 'aac']
        else:
            args += ['-an']
        output = folder / 'content.mp4'
        if direct:
            # A copied video can reach its frame limit before filtered audio is
            # emitted. Bound both streams with the output duration instead.
            args += ['-c:v', 'copy']
        else:
            args += ['-vf', f'scale={int(width)}:{int(height)},setsar=1', '-frames:v', str(int(frames)),
                     '-r', str(fps), '-fps_mode', 'cfr', '-c:v', 'libx264', '-preset', 'veryfast']
            if quality == 'custom':
                bits = round(bitrate * 1000000)
                args += ['-b:v', str(bits), '-maxrate', str(bits), '-bufsize', str(bits * 2)]
            else:
                args += ['-crf', '17' if quality == 'high' else '20']
            args += ['-pix_fmt', 'yuv420p']
        args += ['-movflags', '+faststart', '-t', str(total), str(output)]
        run(args, timeout=600, cancelled=cancelled)
        return output
    except (KeyError, TypeError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        raise ValueError('内容素材包不完整，请重新生成') from error


@functools.lru_cache(maxsize=1)
def system_fonts():
    """Read installed family names without exporting font files."""
    fallback = ['Arial', 'Helvetica Neue', 'Times New Roman', 'Menlo', 'PingFang SC', 'Songti SC', 'Hiragino Sans GB']
    if sys.platform != 'darwin':
        return fallback
    cf = ctypes.CDLL('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
    ct = ctypes.CDLL('/System/Library/Frameworks/CoreText.framework/CoreText')
    ct.CTFontManagerCopyAvailableFontFamilyNames.restype = ctypes.c_void_p
    cf.CFArrayGetCount.argtypes = [ctypes.c_void_p]
    cf.CFArrayGetCount.restype = ctypes.c_long
    cf.CFArrayGetValueAtIndex.argtypes = [ctypes.c_void_p, ctypes.c_long]
    cf.CFArrayGetValueAtIndex.restype = ctypes.c_void_p
    cf.CFStringGetCString.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_long, ctypes.c_uint32]
    cf.CFStringGetCString.restype = ctypes.c_bool
    cf.CFRelease.argtypes = [ctypes.c_void_p]
    families = ct.CTFontManagerCopyAvailableFontFamilyNames()
    names = set()
    try:
        for i in range(cf.CFArrayGetCount(families)):
            value = cf.CFArrayGetValueAtIndex(families, i)
            buffer = ctypes.create_string_buffer(4096)
            if cf.CFStringGetCString(value, buffer, len(buffer), 0x08000100):
                name = buffer.value.decode('utf-8')
                if not name.startswith('.'):
                    names.add(name)
    finally:
        cf.CFRelease(families)
    return sorted(names, key=str.casefold) or fallback


@functools.lru_cache(maxsize=1)
def chinese_fonts():
    """Group families that have native Chinese glyphs at the top of the picker."""
    if sys.platform != 'darwin':
        return ['PingFang SC', 'Songti SC', 'Hiragino Sans GB']
    cf = ctypes.CDLL('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
    ct = ctypes.CDLL('/System/Library/Frameworks/CoreText.framework/CoreText')
    cf.CFStringCreateWithCString.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_uint32]
    cf.CFStringCreateWithCString.restype = ctypes.c_void_p
    ct.CTFontCreateWithName.argtypes = [ctypes.c_void_p, ctypes.c_double, ctypes.c_void_p]
    ct.CTFontCreateWithName.restype = ctypes.c_void_p
    ct.CTFontCopyCharacterSet.argtypes = [ctypes.c_void_p]
    ct.CTFontCopyCharacterSet.restype = ctypes.c_void_p
    cf.CFCharacterSetIsLongCharacterMember.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
    cf.CFCharacterSetIsLongCharacterMember.restype = ctypes.c_bool
    cf.CFRelease.argtypes = [ctypes.c_void_p]
    result = []
    for name in system_fonts():
        value = cf.CFStringCreateWithCString(None, name.encode('utf-8'), 0x08000100)
        font = ct.CTFontCreateWithName(value, 12, None)
        charset = ct.CTFontCopyCharacterSet(font)
        try:
            if all(cf.CFCharacterSetIsLongCharacterMember(charset, char) for char in [0x4e2d, 0x6587]):
                result.append(name)
        finally:
            for handle in [charset, font, value]:
                if handle:
                    cf.CFRelease(handle)
    return result


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT.parent), **kwargs)

    def end_headers(self):
        self.send_header('Cache-Control', 'no-cache')
        super().end_headers()

    def error(self, message, status=400):
        data = json.dumps({'error': message}, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_PUT(self):
        if not creator_bridge.handle(self) and not publishing_bridge.handle(self):
            self.error('接口不存在', 404)

    def do_DELETE(self):
        if not creator_bridge.handle(self) and not publishing_bridge.handle(self):
            self.error('接口不存在', 404)

    def do_GET(self):
        if urlsplit(self.path).path == '/creator-studio/api/local-status':
            if self.headers.get('Host') != f'127.0.0.1:{self.server.server_port}' or self.headers.get('X-Creator-Request') != '1':
                return self.error('请从本地创作台操作', 403)
            data = json.dumps({'name': 'qingjing-creator-studio', 'version': '0.13.0', 'pid': os.getpid(), 'codeHash': SERVER_CODE_HASH, 'rendering': RENDER_LOCK.locked() or creator_bridge.has_pending_generation()}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if creator_bridge.handle(self) or publishing_bridge.handle(self):
            return
        if urlsplit(self.path).path != '/creator-studio/api/fonts':
            return super().do_GET()
        if self.headers.get('Host') != f'127.0.0.1:{self.server.server_port}':
            return self.error('请从本地创作台读取字体', 403)
        try:
            data = json.dumps({'families': system_fonts(), 'chineseFamilies': chinese_fonts()}, ensure_ascii=False).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception:
            self.error('系统字体暂时无法读取', 503)

    def do_POST(self):
        if creator_bridge.handle(self) or publishing_bridge.handle(self):
            return
        endpoint = urlsplit(self.path).path
        if endpoint not in ['/creator-studio/api/render', '/creator-studio/api/render-content']:
            return self.error('接口不存在', 404)
        authority = f'127.0.0.1:{self.server.server_port}'
        if self.headers.get('Host') != authority or self.headers.get('Origin') != 'http://' + authority or self.headers.get('X-Creator-Export') != '1':
            return self.error('请从本地创作台导出', 403)
        try:
            size = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            return self.error('素材包大小无效')
        if not 0 < size <= MAX_UPLOAD:
            return self.error('素材包超过 512 MB，请分批导出', 413)
        if not RENDER_LOCK.acquire(blocking=False):
            return self.error('另一项视频正在导出，请稍后再试', 429)
        try:
            self.connection.settimeout(60)
            payload = self.rfile.read(size)
            if len(payload) != size:
                return self.error('素材上传未完成，请重试')
            with tempfile.TemporaryDirectory(prefix='qingjing-export-') as temporary:
                def disconnected():
                    readable, _, _ = select.select([self.connection], [], [], 0)
                    return bool(readable) and not self.connection.recv(1, socket.MSG_PEEK)
                renderer = render_content if endpoint.endswith('render-content') else render
                output = renderer(payload, Path(temporary), disconnected)
                self.send_response(200)
                self.send_header('Content-Type', 'video/mp4')
                self.send_header('Content-Length', str(output.stat().st_size))
                self.end_headers()
                with output.open('rb') as source:
                    shutil.copyfileobj(source, self.wfile)
        except ValueError as error:
            self.error(str(error), 422)
        except (ExportCancelled, BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        except Exception:
            self.error('本地视频导出失败，请重试', 500)
        finally:
            RENDER_LOCK.release()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8176)
    arguments = parser.parse_args()
    executable('ffmpeg')
    executable('ffprobe')
    creator_bridge.start({'WALLPAPER_RENDER': render, 'CONTENT_RENDER': render_content}, RENDER_LOCK)
    print(f'倾境创作台：http://127.0.0.1:{arguments.port}/creator-studio/', flush=True)
    ThreadingHTTPServer(('127.0.0.1', arguments.port), Handler).serve_forever()
