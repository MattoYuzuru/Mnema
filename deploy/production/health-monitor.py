#!/usr/bin/python3 -I
"""Bounded local/public checks; alerts contain component names only, never bodies."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

URLS = {
    'identity_readiness': 'http://127.0.0.1:18081/api/actuator/health/readiness',
    'learning_readiness': 'http://127.0.0.1:18082/api/actuator/health/readiness',
    'frontend_readiness': 'http://127.0.0.1:18080/index.html',
    'public_https': 'https://mnema.app/',
}


def check():
    failed = []
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for name, url in URLS.items():
        try:
            with opener.open(url, timeout=5) as response:
                if response.status != 200:
                    failed.append(name)
        except Exception:
            failed.append(name)
    if shutil.disk_usage('/').free < 10 * 1024 ** 3:
        failed.append('disk_reserve')
    if Path('/var/lib/mnema-release/pending.json').exists():
        failed.append('rollout_reconciliation')
    return sorted(failed)


def main():
    if sys.argv[1:] == ['preview']:
        print('Check bounded readiness/public HTTPS/disk; emit local journal alerts, no outbound notifications.')
        return
    if sys.argv[1:] or os.geteuid() != 0:
        raise ValueError('unsupported monitor operation')
    failed = check()
    event = {'service': 'mnema-health', 'status': 'failed' if failed else 'healthy', 'components': failed}
    subprocess.run(['/usr/bin/logger', '--tag', 'mnema-health', '--priority',
        'daemon.err' if failed else 'daemon.info', json.dumps(event)], check=True, timeout=5)
    print(json.dumps(event))
    if failed:
        sys.exit(1)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError):
        print('health monitor failed without sensitive diagnostics', file=sys.stderr)
        sys.exit(1)
