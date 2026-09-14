#!/usr/bin/env python3
"""Local loopback fixture only: actual API codec -> Android Keystore/install interoperability.

This does not emulate production authorization or replace the API MySQL/Redis tests.
No private keys, tickets or request bodies are logged. State stays under ignored .runtime.
"""
import argparse
import base64
import datetime
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import re
import secrets
import subprocess
import tempfile
import threading
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('root', type=Path)
parser.add_argument('--port', type=int, default=8082)
args = parser.parse_args()
root = args.root.resolve()
assert root.is_dir() and '.runtime' in root.parts
tickets = {}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        if self.path != '/fixture':
            self.send_error(404)
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
            assert 0 < length <= 8192
            data = json.loads(self.rfile.read(length))
            kind = data['resourceType']
            mode = data.get('mode', 'valid')
            assert kind in ('STATIC_IMAGE', 'VIDEO', 'LAYER_PARALLAX')
            assert mode in ('valid', 'tamper', 'wrong-version', 'slow')
            pem = data['publicKeyPem']
            match = re.fullmatch(r'-----BEGIN PUBLIC KEY-----\s+([A-Za-z0-9+/=\s]+)\s+-----END PUBLIC KEY-----', pem)
            assert match
            der = base64.b64decode(re.sub(r'\s', '', match.group(1)), validate=True)
            assert len(der) <= 1024
            with tempfile.NamedTemporaryFile(dir=root, suffix='.public.pem') as public:
                public.write(pem.encode()); public.flush()
                wrapped = subprocess.run(['openssl', 'pkeyutl', '-encrypt', '-pubin', '-inkey', public.name,
                    '-pkeyopt', 'rsa_padding_mode:oaep', '-pkeyopt', 'rsa_oaep_md:sha256',
                    '-pkeyopt', 'rsa_mgf1_md:sha1'], input=(root / (kind + '.content-key')).read_bytes(),
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True).stdout
            assert len(wrapped) == 256
            descriptor = json.loads((root / (kind + '.json')).read_text())
            payload = (root / (kind + '.4dwp')).read_bytes()
            if mode == 'tamper':
                payload = payload[:-1] + bytes([payload[-1] ^ 1])
                descriptor['package']['encryptedSha256'] = hashlib.sha256(payload).hexdigest()
            if mode == 'wrong-version':
                descriptor['resourceVersion']['versionNo'] += 1
                descriptor['resourceVersion']['id'] = str(int(descriptor['resourceVersion']['id']) + 1000)
            descriptor['package'].update(encryptionKeySha256=hashlib.sha256(der).hexdigest(),
                wrappedContentKey=base64.urlsafe_b64encode(wrapped).decode().rstrip('='))
            token = secrets.token_urlsafe(32)
            with lock:
                for old in list(tickets):
                    if tickets[old][0] < time.monotonic():
                        del tickets[old]
                tickets[token] = (time.monotonic() + 90, payload, mode)
            descriptor['ticket'] = token
            descriptor['expiresAt'] = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=90)).isoformat()
            body = json.dumps(descriptor).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
        except Exception:
            self.send_error(400)

    def do_GET(self):
        if self.path != '/api/v1/delivery/files':
            self.send_error(404); return
        token = self.headers.get('Authorization', '').removeprefix('Bearer ')
        with lock:
            state = tickets.get(token)
        if state is None or state[0] < time.monotonic():
            self.send_error(401); return
        _, payload, mode = state
        self.send_response(200); self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(len(payload))); self.send_header('Cache-Control', 'no-store'); self.end_headers()
        try:
            step = 1024 if mode == 'slow' else 32768
            for at in range(0, len(payload), step):
                self.wfile.write(payload[at:at + step]); self.wfile.flush()
                if mode == 'slow':
                    time.sleep(.1)
        except (BrokenPipeError, ConnectionResetError):
            pass


print('Local native package fixture ready on loopback port ' + str(args.port), flush=True)
ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
