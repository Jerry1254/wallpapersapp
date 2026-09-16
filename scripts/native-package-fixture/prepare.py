#!/usr/bin/env python3
"""Generate ignored local test assets and packages using the API's real codec."""
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import zlib

workspace = Path(__file__).resolve().parents[2]
root = workspace / '.runtime/android-native-fixture'
root.mkdir(parents=True, exist_ok=True)
root.chmod(0o700)


def png(name, alpha=False, noise=False, pattern=False):
    if (root / name).exists():
        return
    width = height = 512
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    if pattern:
        rows = []
        for y in range(height):
            row = bytearray(b'\0')
            for x in range(width):
                if alpha:
                    circle = (x-256)**2+(y-256)**2<110**2
                    cross = (abs(x-256)<7 and abs(y-256)<170) or (abs(y-256)<7 and abs(x-256)<170)
                    pixel = bytes([245, 195, 65, 220 if circle or cross else 0])
                else:
                    grid = x % 64<3 or y % 64<3
                    pixel = bytes([160 if grid else 25, 185 if grid else 70, 210 if grid else 110])
                row.extend(pixel)
            rows.append(row)
        rows = b''.join(rows)
    elif noise:
        rows = b''.join(b'\0' + os.urandom(width * 3) for _ in range(height))
    else:
        pixel = bytes([50, 100, 200, 100]) if alpha else bytes([30, 80, 150])
        rows = (b'\0' + pixel * width) * height
    header = struct.pack('>IIBBBBB', width, height, 8, 6 if alpha else 2, 0, 0, 0)
    (root / name).write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))


png('static.png', noise=True); png('background.png'); png('foreground.png', alpha=True)
png('parallax-background.png', pattern=True); png('parallax-foreground.png', alpha=True, pattern=True)
config = {'formatVersion': 2, 'canvas': {'width': 512, 'height': 512}, 'motion': {'maxAngleX': 75, 'maxAngleY': 75},
          'layers': [
              {'index': 1, 'offsetXPercent': 8, 'offsetYPercent': 6, 'initialOffsetXPercent': 0, 'initialOffsetYPercent': 0, 'direction': 'follow', 'scale': 1.18, 'opacity': 1, 'blendMode': 'normal'},
              {'index': 2, 'offsetXPercent': 3, 'offsetYPercent': 2, 'initialOffsetXPercent': 0, 'initialOffsetYPercent': 0, 'direction': 'reverse', 'scale': 1, 'opacity': 1, 'blendMode': 'normal'}]}
(root / 'parallax.json').write_text(json.dumps(config))
local_ffmpeg = workspace / '.runtime/media-tools/ffmpeg'
ffmpeg = os.environ.get('QJ_FFMPEG') or (str(local_ffmpeg) if local_ffmpeg.exists() else shutil.which('ffmpeg'))
assert ffmpeg, 'Install ffmpeg or set QJ_FFMPEG'
if not (root / 'video.mp4').exists():
    subprocess.run([ffmpeg, '-v', 'error', '-nostdin', '-y', '-f', 'lavfi', '-i', 'color=c=blue:s=512x512:d=1',
                    '-r', '30', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-an', str(root / 'video.mp4')], check=True)
api = workspace / 'services/api-server'
with (root / 'prepare.log').open('w') as log:
    subprocess.run(['./mvnw', '-q', 'test-compile', 'dependency:build-classpath', '-Dmdep.includeScope=test',
                    '-Dmdep.outputFile=' + str(root / 'classpath')], cwd=api, stdout=log, stderr=log, check=True)
classpath = str(api / 'target/classes') + os.pathsep + (root / 'classpath').read_text().strip()
classes = root / 'classes'; classes.mkdir(exist_ok=True)
subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes),
                str(workspace / 'scripts/native-package-fixture/NativePackageFixture.java')], check=True)
subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', str(classes) + os.pathsep + classpath,
                'NativePackageFixture', str(root)], check=True)
print('Prepared three real media packages with the API codec; public build configuration is in .runtime/android-native-fixture/build.env')
