#!/usr/bin/python3 -I
"""Bounded local/public checks; alerts contain component names only, never bodies."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request

URLS = {
    'identity_readiness': 'http://127.0.0.1:18081/api/actuator/health/readiness',
    'learning_readiness': 'http://127.0.0.1:18082/api/actuator/health/readiness',
    'frontend_readiness': 'http://127.0.0.1:18080/index.html',
    'public_https': 'https://mnema.app/',
}
BACKUP_TOOL = '/usr/local/sbin/mnema-local-backup'
OFFSITE_CONFIG = Path('/etc/mnema/production/offsite.env')
OFFSITE_SUCCESS = Path('/var/lib/mnema-release/offsite-last-success')
STALE_SECONDS = 26 * 3600
# Four URL probes (5 s each) + newest + logger stay well below the unit's TimeoutStartSec=60s.
NEWEST_TIMEOUT = 10
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root'}


def newest_complete_backup():
    """Modification time of the newest complete backup, as defined by the backup tool itself."""
    try:
        info = os.stat(BACKUP_TOOL)
        if info.st_uid != 0 or info.st_mode & 0o022:
            return None  # never execute a tool that is not root-owned and write-protected
        result = subprocess.run([BACKUP_TOOL, 'newest'], env=ENV, capture_output=True, timeout=NEWEST_TIMEOUT)
        modified = json.loads(result.stdout.decode().strip().splitlines()[-1])['modified'] if result.returncode == 0 else None
        return None if modified is None else float(modified)
    except (subprocess.SubprocessError, OSError, ValueError, KeyError, IndexError, TypeError):
        return None


def is_stale(modified, clock=time.time):
    return modified is None or clock() - modified > STALE_SECONDS


def stale_components():
    """Backup and offsite freshness; kept apart from the readiness probes in check()."""
    failed = []
    if is_stale(newest_complete_backup()):
        failed.append('backup_stale')
    if OFFSITE_CONFIG.exists():
        try:
            offsite = OFFSITE_SUCCESS.stat().st_mtime
        except OSError:
            offsite = None
        if is_stale(offsite):
            failed.append('offsite_stale')
    return failed


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
        print('Check bounded readiness/public HTTPS/disk/backup freshness; emit local journal alerts, no outbound notifications.')
        return
    if sys.argv[1:] or os.geteuid() != 0:
        raise ValueError('unsupported monitor operation')
    failed = sorted(check() + stale_components())
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
