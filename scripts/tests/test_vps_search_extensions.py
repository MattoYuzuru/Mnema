"""Community/0 (#447): the production PostgreSQL image with init-database.sh gives the catalogue search what Learning V66 needs.

The image is the one CI builds from deploy/production (tag mnema-vps-postgres-fixture:verified, see pull-request.yaml); the cluster
is initialised by the reviewed init script exactly as on the VPS, with no network. Learning's V66 is then run as app_learning, the
production role without CREATE on the database, to prove the migration is a no-op for the extensions there and still creates the
text search configuration.
"""
import shutil
import subprocess
import time
import unittest
import uuid

from test_vps_runtime import ROOT

FIXTURE_IMAGE = 'mnema-vps-postgres-fixture:verified'
V66 = ROOT / 'backend/services/learning/src/main/resources/db/learning/migration/V66__catalog_search_dictionaries.sql'


class SearchExtensionsDockerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.docker = shutil.which('docker')
        if cls.docker is None:
            raise AssertionError('docker is required for the production image check')
        cls.name = 'mnema-search-test-' + uuid.uuid4().hex
        subprocess.run([cls.docker, 'run', '--detach', '--pull', 'never', '--name', cls.name, '--network', 'none',
            '--tmpfs', '/var/lib/postgresql:size=256m', '-e', 'POSTGRES_PASSWORD=fixture-superuser',
            '-e', 'POSTGRES_DB=mnema', '-e', 'MNEMA_IDENTITY_DB_PASSWORD=fixture-identity',
            '-e', 'MNEMA_LEARNING_DB_PASSWORD=fixture-learning', '--mount',
            'type=bind,src=' + str(ROOT / 'deploy/production/init-database.sh') + ',dst=/docker-entrypoint-initdb.d/10-mnema.sh,readonly',
            FIXTURE_IMAGE, 'postgres', '-c', 'listen_addresses=127.0.0.1', '-c', 'port=15432'],
            check=True, stdout=subprocess.DEVNULL, timeout=120)
        for _ in range(60):
            try:
                if cls.sql("SELECT count(*) FROM pg_extension WHERE extname = 'unaccent';") == '1':
                    return
            except subprocess.CalledProcessError:
                pass
            time.sleep(1)
        cls.tearDownClass()
        raise AssertionError('fixture database did not initialize')

    @classmethod
    def tearDownClass(cls):
        subprocess.run([cls.docker, 'rm', '--force', cls.name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)

    @classmethod
    def sql(cls, statement, user='postgres', single_transaction=False):
        command = [cls.docker, 'exec', '-i', '-e', 'PGPASSWORD=fixture-learning', cls.name, 'psql', '--no-password', '-h', '127.0.0.1',
                   '-U', user, '-d', 'mnema', '-p', '15432', '-At', '--set', 'ON_ERROR_STOP=1']
        if user == 'postgres':
            command = [cls.docker, 'exec', '-i', cls.name, 'psql', '--no-password', '-U', user, '-d', 'mnema', '-p', '15432',
                       '-At', '--set', 'ON_ERROR_STOP=1']
        if single_transaction:
            command.append('--single-transaction')
        return subprocess.run(command, input=statement.encode(), check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=60).stdout.decode().strip()

    def test_both_extensions_live_in_the_learning_schema(self):
        rows = self.sql("SELECT string_agg(e.extname, ',' ORDER BY e.extname) FROM pg_extension e "
                        "JOIN pg_namespace n ON n.oid = e.extnamespace WHERE n.nspname = 'app_learning';")
        self.assertEqual(rows, 'pg_trgm,unaccent')

    def test_cyrillic_trigrams_are_built_by_the_production_locale(self):
        # An empty result would mean a C locale without Cyrillic letters: a dump and restore with a new locale (owner decision).
        self.assertEqual(self.sql("SELECT cardinality(app_learning.show_trgm('Привет'));"), '7')
        self.assertEqual(self.sql("SELECT lower('ПРИВЕТ Ёж');"), 'привет ёж')

    def test_v66_runs_as_the_unprivileged_learning_role(self):
        self.assertEqual(self.sql("SELECT has_database_privilege('app_learning', 'mnema', 'CREATE');"), 'f')
        self.sql(V66.read_text(), user='app_learning', single_transaction=True)
        vector = self.sql("SELECT to_tsvector('app_learning.simple_unaccent', 'Español Ёлка')::text;", user='app_learning')
        self.assertEqual(vector, "'espanol':1 'елка':2")
        owner = self.sql("SELECT pg_get_userbyid(cfgowner) FROM pg_ts_config WHERE cfgname = 'simple_unaccent';")
        self.assertEqual(owner, 'app_learning')


if __name__ == '__main__':
    unittest.main()
