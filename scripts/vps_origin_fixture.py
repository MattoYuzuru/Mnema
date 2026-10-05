#!/usr/bin/env python3
"""Exercise reviewed Caddy routing on temporary loopback listeners, never production."""
import argparse
import http.client
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time


def verify(text):
    hits = []
    failure = {'enabled': False}
    class Upstream(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            hits.append(self.path)
            if failure['enabled']:
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Set-Cookie', 'session=fixture; Secure')
            self.end_headers()
            self.wfile.write(json.dumps({'cookie': self.headers.get('Cookie'),
                'forwarded': self.headers.get('X-Forwarded-For')}).encode())
        def log_message(self, *args): pass
    with tempfile.TemporaryDirectory(prefix='mnema-caddy-fixture-') as temporary, \
         http.server.ThreadingHTTPServer(('127.0.0.1', 0), Upstream) as upstream:
        threading.Thread(target=upstream.serve_forever, daemon=True).start()
        root = Path(temporary)
        source = root / 'Caddyfile'
        source.write_text(text)
        config = json.loads(subprocess.run(['caddy', 'adapt', '--config', str(source), '--adapter', 'caddyfile'],
            check=True, capture_output=True, text=True).stdout)
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        config['admin'] = {'disabled': True}
        config['apps'].pop('tls', None)
        servers = config['apps']['http']['servers']
        if len(servers) != 1: raise ValueError('fixture requires one reviewed HTTP server')
        for server in servers.values():
            server['listen'] = ['127.0.0.1:' + str(port)]
            server['automatic_https'] = {'disable': True}
            server.pop('tls_connection_policies', None)
        def rewrite(value):
            if isinstance(value, dict):
                if value.get('handler') == 'reverse_proxy':
                    value['upstreams'] = [{'dial': '127.0.0.1:' + str(upstream.server_port)}]
                for child in value.values(): rewrite(child)
            elif isinstance(value, list):
                for child in value: rewrite(child)
        rewrite(config)
        fixture = root / 'fixture.json'
        fixture.write_text(json.dumps(config))
        env = {**os.environ, 'XDG_DATA_HOME': temporary, 'XDG_CONFIG_HOME': temporary}
        log = (root / 'caddy.log').open('wb')
        process = subprocess.Popen(['caddy', 'run', '--config', str(fixture)], env=env,
            stdout=log, stderr=log)
        def request(host, path):
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=3)
            try:
                connection.request('GET', path, headers={'Host': host, 'Cookie': 'session=fixture',
                    'X-Forwarded-For': '203.0.113.99'})
                response = connection.getresponse()
                return response.status, dict(response.getheaders()), response.read()
            finally:
                connection.close()
        try:
            for _ in range(50):
                if process.poll() is not None: raise ValueError('fixture Caddy exited')
                try:
                    if request('mnema.app', '/')[0] == 200: break
                except OSError: pass
                time.sleep(0.1)
            else: raise ValueError('fixture Caddy did not become ready')
            paths = ('/api/actuator', '/api/actuator/health/readiness', '/actuator/health',
                '/api/internal/secret', '/internal/private', '/metrics', '/admin',
                '/api/%61ctuator/health', '/api/actuator%2Fhealth')
            for host in ('mnema.app', 'auth.mnema.app'):
                for path in paths:
                    before = len(hits)
                    if request(host, path)[0] != 404 or len(hits) != before:
                        raise ValueError('private route reached upstream: ' + host + path)
            status, headers, body = request('mnema.app', '/api/materials')
            value = json.loads(body)
            if status != 200 or value['cookie'] is not None or 'Set-Cookie' in headers or headers.get('Cache-Control') != 'no-store':
                raise ValueError('Learning cookie/cache boundary differs')
            status, headers, body = request('auth.mnema.app', '/api/accounts')
            value = json.loads(body)
            if status != 200 or value['cookie'] != 'session=fixture' or value['forwarded'] != '127.0.0.1' or headers.get('Cache-Control') != 'no-store':
                raise ValueError('Identity session/forwarded/cache boundary differs')
            status, headers, _ = request('www.mnema.app', '/fixture')
            if status != 308 or headers.get('Location') != 'https://mnema.app/fixture':
                raise ValueError('canonical redirect differs')
            failure['enabled'] = True
            marker = 'mnema-fixture-oauth-secret'
            if request('mnema.app', '/auth/callback?code=' + marker + '&state=fixture')[0] != 502:
                raise ValueError('upstream failure fixture did not fail')
            time.sleep(0.1)
            logged = (root / 'caddy.log').read_text()
            if marker in logged or 'http.log.error' not in logged:
                raise ValueError('request context leaked or error log fixture was not exercised')
            print(json.dumps({'privateRequests': len(paths) * 2, 'privateUpstreamHits': 0,
                'learningCookieIsolation': True, 'identityForwardedAddress': True,
                'canonicalRedirect': True, 'errorLogTokenFree': True}))
        finally:
            process.terminate()
            process.wait(timeout=10)
            log.close()
            upstream.shutdown()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=Path(__file__).resolve().parents[1] / 'deploy/production/Caddyfile')
    verify(parser.parse_args().config.read_text())
