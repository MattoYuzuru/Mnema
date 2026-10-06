#!/usr/bin/python3 -I
"""Generate first-release secrets on the RU host; never rotate/reuse fixture keys."""
import base64
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys

ROOT = Path('/etc/mnema/production')
FILES = ('compose.yaml', 'nginx.conf', 'init-database.sh')


def protected(path):
    for item in (path, *path.parents):
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('unsafe bootstrap path')


def b64uint(number):
    return base64.urlsafe_b64encode(number.to_bytes((number.bit_length() + 7) // 8, 'big')).rstrip(b'=').decode()


def signing_key():
    from cryptography.hazmat.primitives.asymmetric import rsa
    values = rsa.generate_private_key(public_exponent=65537, key_size=4096).private_numbers()
    public = values.public_numbers
    return {'keys': [{'kty': 'RSA', 'kid': 'production-20261004', 'use': 'sig', 'alg': 'RS256',
        'n': b64uint(public.n), 'e': b64uint(public.e), 'd': b64uint(values.d),
        'p': b64uint(values.p), 'q': b64uint(values.q), 'dp': b64uint(values.dmp1),
        'dq': b64uint(values.dmq1), 'qi': b64uint(values.iqmp)}]}


def create(path, content, mode=0o600, uid=0):
    with path.open('x') as handle:
        handle.write(content)
        handle.flush()
        os.fsync(handle.fileno())
    path.chmod(mode)
    os.chown(path, uid, uid)


def main():
    if sys.argv[1:] == [] or sys.argv[1:] == ['preview']:
        print('Target: mnema 135.106.175.30. First empty-DB runtime, root-only configuration.')
        print('Generate three DB passwords and RSA4096 on RU host; install reviewed Compose inputs.')
        print('Login/register/foreign AI/federated auth/media remain blocked or unconfigured.')
        print('No old data, remote bucket, provider key, container, DNS or Caddy mutation.')
        return
    if sys.argv[1:] != ['--apply'] or os.geteuid() != 0:
        raise ValueError('unsupported privileged bootstrap')
    release = Path('/etc/os-release').read_text()
    if 'ID=ubuntu\n' not in release or 'VERSION_ID="24.04"' not in release:
        raise ValueError('unexpected operating system')
    addresses = subprocess.check_output(['/usr/sbin/ip', '-4', 'address', 'show', 'scope', 'global'], text=True)
    if '135.106.175.30/' not in addresses:
        raise ValueError('unexpected target')
    protected(ROOT)
    source = Path(__file__).resolve().parent
    protected(source)
    for name in (*FILES, 'runtime.env', 'identity-signing.json'):
        if (ROOT / name).exists() or (ROOT / name).is_symlink():
            raise ValueError('existing/partial bootstrap requires administrator inspection')
    for name in FILES:
        protected(source / name)
    material = signing_key()  # Generate before any private-file mutation.
    create(ROOT / 'runtime.env', ''.join(name + '=' + secrets.token_hex(32) + '\n' for name in
        ('MNEMA_POSTGRES_PASSWORD', 'MNEMA_IDENTITY_DB_PASSWORD', 'MNEMA_LEARNING_DB_PASSWORD',
         'MNEMA_PROMO_HASH_SECRET', 'MNEMA_EXPERIMENT_SECRET')))
    create(ROOT / 'identity-signing.json', json.dumps(material), mode=0o400, uid=10001)
    for name in FILES:
        create(ROOT / name, (source / name).read_text(), mode=0o755 if name.endswith('.sh') else 0o644)
    print('Private runtime initialized; no values emitted. No database/application started.')


if __name__ == '__main__':
    try:
        os.umask(0o077)
        main()
    except (OSError, ValueError, subprocess.SubprocessError):
        print('bootstrap rejected; inspect privately before retrying', file=sys.stderr)
        sys.exit(1)
