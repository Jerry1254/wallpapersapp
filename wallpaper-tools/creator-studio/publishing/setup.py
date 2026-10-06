"""Install a private publishing runtime; invoked explicitly from the workbench."""
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

REVISION = '0012d2c355f88f683cc38dde2a2db209e14091bc'
ROOT = Path(__file__).resolve().parents[3]
RUNTIME = ROOT / '.runtime' / 'creator-publishing'


def prepare():
    os.umask(0o077)
    RUNTIME.mkdir(parents=True, exist_ok=True, mode=0o700)
    (RUNTIME / 'revision').unlink(missing_ok=True)
    # uv manages an isolated 3.12 runtime on hosts that only have system Python.
    uv = shutil.which('uv')
    if not uv:
        bootstrap = RUNTIME / 'bootstrap'
        if not (bootstrap / 'bin/python').exists():
            subprocess.run([sys.executable, '-m', 'venv', str(bootstrap)], check=True)
        subprocess.run([str(bootstrap / 'bin/python'), '-m', 'pip', 'install', 'uv==0.8.22'], check=True)
        uv = str(bootstrap / 'bin/uv')
    env = dict(os.environ, UV_PYTHON_INSTALL_DIR=str(RUNTIME / 'python'), UV_CACHE_DIR=str(RUNTIME / 'cache'))
    venv = RUNTIME / 'venv'
    if not (venv / 'bin/python').exists():
        subprocess.run([uv, 'venv', '--python', '3.12', str(venv)], check=True, env=env)
    python = str(venv / 'bin/python')
    subprocess.run([uv, 'pip', 'install', '--python', python, '-r', str(Path(__file__).with_name('requirements.txt'))], check=True, env=env)
    source = RUNTIME / 'social-auto-upload'
    with urllib.request.urlopen(f'https://codeload.github.com/dreammis/social-auto-upload/zip/{REVISION}', timeout=90) as response:
        data = response.read(40 * 1024 * 1024)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        prefix = f'social-auto-upload-{REVISION}/'
        source.mkdir(exist_ok=True)
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.startswith(prefix):
                continue
            relative = Path(entry.filename[len(prefix):])
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Invalid dependency archive')
            # Only import the uploader and its helpers, never execute repository scripts.
            if relative.parts[0] not in ('uploader', 'utils', 'myUtils') and relative.name not in ('LICENSE',):
                continue
            target = source / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
    (source / 'conf.py').write_text('from pathlib import Path\nBASE_DIR = Path(__file__).resolve().parent\nDEBUG_MODE = False\nLOCAL_CHROME_HEADLESS = True\nLOCAL_CHROME_PATH = ""\n', encoding='utf-8')
    subprocess.run([python, '-m', 'patchright', 'install', 'chromium'], check=True, env=env)
    (RUNTIME / 'revision').write_text(REVISION, encoding='ascii')


if __name__ == '__main__':
    prepare()
