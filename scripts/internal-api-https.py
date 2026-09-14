#!/usr/bin/env python3
"""Loopback-only HTTPS front for the explicitly selected local API and ADB reverse."""
import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ssl


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--certificate', required=True)
    parser.add_argument('--private-key', required=True)
    parser.add_argument('--port', type=int, default=8443)
    parser.add_argument('--api-port', type=int, default=8083)
    args = parser.parse_args()
    hop_headers = {'connection', 'keep-alive', 'proxy-authenticate',
                   'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade'}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *unused):
            pass  # Requests may contain short tickets; never log paths or headers.

        def forward(self):
            if not self.path.startswith('/api/v1/'):
                self.send_error(404)
                return
            upstream = http.client.HTTPConnection('127.0.0.1', args.api_port, timeout=30)
            try:
                limit = 256 * 1024 * 1024
                transfer = self.headers.get('Transfer-Encoding')
                if transfer:
                    if transfer.strip().lower() != 'chunked' or self.headers.get('Content-Length'):
                        self.send_error(400)
                        return
                    body = bytearray()
                    while True:
                        line = self.rfile.readline(8193)
                        if len(line) > 8192 or not line.endswith(b'\r\n'):
                            self.send_error(400)
                            return
                        size = int(line.split(b';', 1)[0].strip(), 16)
                        if size < 0 or len(body) + size > limit:
                            self.send_error(413)
                            return
                        if size == 0:
                            for _ in range(100):
                                trailer = self.rfile.readline(8193)
                                if trailer == b'\r\n':
                                    break
                                if not trailer or len(trailer) > 8192:
                                    self.send_error(400)
                                    return
                            else:
                                self.send_error(400)
                                return
                            break
                        chunk = self.rfile.read(size)
                        if len(chunk) != size or self.rfile.read(2) != b'\r\n':
                            self.send_error(400)
                            return
                        body.extend(chunk)
                    body = bytes(body)
                else:
                    length = int(self.headers.get('Content-Length', '0'))
                    if length < 0 or length > limit:
                        self.send_error(413)
                        return
                    body = self.rfile.read(length) if length else None
                headers = {k: v for k, v in self.headers.items()
                           if k.lower() not in hop_headers | {'host'}}
                headers['Host'] = f'127.0.0.1:{args.api_port}'
                if body is not None:
                    headers['Content-Length'] = str(len(body))
                upstream.request(self.command, self.path, body, headers)
                response = upstream.getresponse()
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() not in hop_headers:
                        self.send_header(key, value)
                self.send_header('Connection', 'close')
                self.end_headers()
                self.close_connection = True
                while chunk := response.read(65536):
                    self.wfile.write(chunk)
            except (OSError, ValueError, http.client.HTTPException):
                self.close_connection = True
            finally:
                upstream.close()

        do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = forward

    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.certificate, args.private_key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f'Internal HTTPS loopback :{args.port} -> local API :{args.api_port}', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
