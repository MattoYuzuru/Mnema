"""Real pinned PostgreSQL dump/restore with role and row reconciliation."""
import contextlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

from test_vps_runtime import load, ROOT

BACKUP = load('vps_backup', 'deploy/production/local-backup.py')
FIXTURE_IMAGE = 'mnema-vps-postgres-fixture:verified'


class BackupTest(unittest.TestCase):
    def test_only_exact_admin_bound_postgres_digest_is_accepted(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(BACKUP, 'protected'):
            path = Path(temporary) / 'postgres-image'
            with patch.object(BACKUP, 'IMAGE_PIN', path):
                for value in ('postgres:latest', 'ghcr.io/other/postgres@sha256:' + 'a' * 64,
                              'ghcr.io/mattoyuzuru/mnema/postgres@sha256:' + 'A' * 64):
                    path.write_text(value)
                    with self.assertRaises(ValueError): BACKUP.database_image()
                value = 'ghcr.io/mattoyuzuru/mnema/postgres@sha256:' + 'a' * 64
                path.write_text(value + '\n')
                self.assertEqual(BACKUP.database_image(), value)

    def test_dump_restores_rows_and_role_isolation_in_a_network_none_fixture(self):
        name = 'mnema-backup-test-' + uuid.uuid4().hex
        docker = shutil.which('docker')
        real_run = subprocess.run
        def local_run(command, **kwargs):
            command = list(command)
            if command[0] == '/usr/bin/docker': command[0] = docker
            return real_run(command, **kwargs)
        env = {**BACKUP.ENV, 'DOCKER_HOST': os.environ.get('DOCKER_HOST', 'unix:///var/run/docker.sock')}
        with tempfile.TemporaryDirectory() as temporary, patch.object(BACKUP, 'SOURCE', name), \
             patch.object(BACKUP, 'DIRECTORY', Path(temporary)), \
             patch.object(BACKUP, 'protected'), patch.object(BACKUP, 'ENV', env), \
             patch.object(BACKUP, 'database_image', return_value=FIXTURE_IMAGE), \
             patch.object(BACKUP.subprocess, 'run', side_effect=local_run):
            old_umask = os.umask(0o077)
            started = False
            try:
                BACKUP.docker(['run', '--detach', '--pull', 'never', '--name', name, '--network', 'none',
                    '--tmpfs', '/var/lib/postgresql:size=256m', '-e', 'POSTGRES_PASSWORD=fixture-superuser',
                    '-e', 'POSTGRES_DB=mnema', '-e', 'MNEMA_IDENTITY_DB_PASSWORD=fixture-identity',
                    '-e', 'MNEMA_LEARNING_DB_PASSWORD=fixture-learning', '--mount',
                    'type=bind,src=' + str(ROOT / 'deploy/production/init-database.sh') + ',dst=/docker-entrypoint-initdb.d/10-mnema.sh,readonly',
                    FIXTURE_IMAGE, 'postgres', '-c', 'listen_addresses=127.0.0.1', '-c', 'port=15432'], stdout=subprocess.DEVNULL)
                started = True
                for _ in range(60):
                    try:
                        if BACKUP.sql(name, "SELECT count(*) FROM pg_roles WHERE rolname='app_learning';") == '1': break
                    except subprocess.CalledProcessError:
                        pass
                    time.sleep(1)
                else: self.fail('fixture database did not initialize')
                BACKUP.sql(name, "CREATE TABLE app_identity.restore_fixture(id integer PRIMARY KEY, value text); INSERT INTO app_identity.restore_fixture VALUES (1,'ordinary fixture'),(2,'second row');")
                output = io.StringIO()
                with contextlib.redirect_stdout(output): BACKUP.backup(rehearse=True)
                self.assertIn('"restoreVerified": true', output.getvalue())
                self.assertIn('"rows": 2', output.getvalue())
                self.assertNotIn('ordinary fixture', output.getvalue())
                dumps = list(Path(temporary).glob('*.dump'))
                self.assertEqual(len(dumps), 1)
                self.assertEqual(dumps[0].stat().st_mode & 0o777, 0o600)
                self.assertEqual(BACKUP.sql(name, 'SELECT count(*) FROM app_identity.restore_fixture;'), '2')
                with patch.object(BACKUP, 'fingerprint', side_effect=[{}, {'changed': [1, 'x']}]), \
                     self.assertRaises(ValueError):
                    BACKUP.backup(rehearse=True)
                self.assertEqual(len(list(Path(temporary).glob('*.dump'))), 2)
            finally:
                os.umask(old_umask)
                if started: BACKUP.docker(['rm', '--force', name], stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    unittest.main()
