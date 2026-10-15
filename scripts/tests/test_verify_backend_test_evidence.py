"""The required aggregate must fail closed on missing, duplicated, stale or altered evidence."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import backend_test_evidence as evidence


class BackendEvidenceTest(unittest.TestCase):
    def test_partition_matches_jdk25_reference_vectors_including_utf16_names(self):
        # Generated with the Gradle expression in a standalone JDK 25 probe.
        vectors = {'app.mnema.Test': [1, 1, 2, 1, 4, 5], 'app.mnema.𐐀Case': [1, 1, 2, 1, 1, 5],
                   'app.mnema.learning.update.plan.ApplyScaleIntegrationTest': [1, 2, 2, 4, 4, 2]}
        for name, expected in vectors.items():
            with self.subTest(name=name):
                self.assertEqual([evidence.bucket(name, count) for count in range(1, 7)], expected)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'backend').mkdir()
        (self.root / 'backend/test-opt-ins.json').write_text('{}')
        self.patch = patch.object(evidence, 'revision', return_value='revision-a')
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def prepare(self, index, skipped=False, failure=False, nested=False):
        for module in evidence.MODULES:
            build = self.root / 'backend/services' / module / 'build'
            (build / 'test-results/test').mkdir(parents=True, exist_ok=True)
            (build / 'jacoco').mkdir(parents=True, exist_ok=True)
            for path in (build / 'test-results/test').glob('*.xml'):
                path.unlink()
            name = next(f'app.Case{i}' for i in range(30) if evidence.bucket(f'app.Case{i}', 2) == index)
            if nested: name += '$Nested'
            result = '<failure/>' if failure else '<skipped/>' if skipped else ''
            (build / 'test-results/test/TEST-case.xml').write_text(
                f'<testsuite name="{name}" time="1"><testcase name="works">{result}</testcase></testsuite>')
            inventory = [next(f'app.Case{i}' for i in range(30) if evidence.bucket(f'app.Case{i}', 2) == shard) for shard in (1, 2)]
            (build / 'test-inventory.json').write_text(json.dumps(inventory))
            (build / 'jacoco/test.exec').write_bytes(b'bounded synthetic evidence')
        return evidence.pack(self.root, index, 2, self.root / f'inputs/shard-{index}')

    def both(self):
        self.prepare(1)
        self.prepare(2)

    def test_merges_complete_execution_data_for_both_services(self):
        self.both()
        result = evidence.merge(self.root, self.root / 'inputs', 2)
        self.assertEqual(len(result), 2)
        for module in evidence.MODULES:
            for index in (1, 2):
                self.assertTrue((self.root / f'backend/services/{module}/build/jacoco/test-shard-{index}.exec').is_file())

    def test_missing_shard_fails_before_writing_outputs(self):
        self.prepare(1)
        with self.assertRaisesRegex(ValueError, 'Expected 2 manifests'):
            evidence.merge(self.root, self.root / 'inputs', 2)
        self.assertFalse(list(self.root.glob('**/test-shard-*.exec')))

    def test_more_than_six_shards_is_rejected_before_writing_outputs(self):
        output = self.root / 'too-many-shards'
        with self.assertRaises(ValueError):
            evidence.pack(self.root, 1, 7, output)
        self.assertFalse(output.exists())
        with self.assertRaises(ValueError):
            evidence.merge(self.root, self.root / 'inputs', 7)

    def corrupt_manifest(self, field, value):
        path = self.root / 'inputs/shard-2/manifest.json'
        data = json.loads(path.read_text()); data[field] = value
        path.write_text(json.dumps(data))

    def test_duplicate_shard_is_rejected(self):
        self.both(); self.corrupt_manifest('index', 1)
        with self.assertRaises(ValueError): evidence.merge(self.root, self.root / 'inputs', 2)

    def test_different_revision_is_rejected(self):
        self.both(); self.corrupt_manifest('revision', 'revision-b')
        with self.assertRaises(ValueError): evidence.merge(self.root, self.root / 'inputs', 2)

    def test_changed_sources_are_rejected(self):
        self.both(); self.corrupt_manifest('source', 'different-source')
        with self.assertRaises(ValueError): evidence.merge(self.root, self.root / 'inputs', 2)

    def test_tampered_execution_data_fails_before_any_output(self):
        self.both()
        (self.root / 'inputs/shard-2/learning.exec').write_bytes(b'altered')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            evidence.merge(self.root, self.root / 'inputs', 2)
        self.assertFalse(list(self.root.glob('**/test-shard-*.exec')))

    def test_failed_test_cannot_be_packaged(self):
        with self.assertRaisesRegex(ValueError, 'Failing suite'):
            self.prepare(1, failure=True)

    def test_unexpected_skip_cannot_be_packaged(self):
        with self.assertRaisesRegex(ValueError, 'Unexpected skipped'):
            self.prepare(1, skipped=True)

    def test_expected_provider_opt_in_may_be_skipped(self):
        name = next(f'app.Case{i}' for i in range(30) if evidence.bucket(f'app.Case{i}', 2) == 1)
        (self.root / 'backend/test-opt-ins.json').write_text(json.dumps({name: ['works']}))
        self.assertEqual(self.prepare(1, skipped=True)['suites'][0]['skipped'], 1)

    def test_omitted_discovered_class_is_rejected_even_when_module_still_has_reports(self):
        self.both()
        for module in evidence.MODULES:
            path = self.root / f"backend/services/{module}/build/test-inventory.json"
            expected = json.loads(path.read_text())
            expected.append(next(f'app.Extra{i}' for i in range(30) if evidence.bucket(f'app.Extra{i}', 2) == 2))
            path.write_text(json.dumps(expected))
        with self.assertRaisesRegex(ValueError, 'Incomplete .* inventory'):
            evidence.merge(self.root, self.root / 'inputs', 2)

    def test_reusing_merge_directory_is_rejected(self):
        self.both(); evidence.merge(self.root, self.root / 'inputs', 2)
        with self.assertRaisesRegex(ValueError, 'Stale merge output'):
            evidence.merge(self.root, self.root / 'inputs', 2)

    def test_nested_suites_remain_in_the_outer_class_partition(self):
        result = self.prepare(1, nested=True)
        self.assertTrue(all(suite['name'].endswith('$Nested') for suite in result['suites']))

    def test_missing_module_metadata_is_rejected(self):
        self.both()
        path = self.root / 'inputs/shard-2/manifest.json'
        data = json.loads(path.read_text()); data['suites'] = data['suites'][:1]
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'Missing module'):
            evidence.merge(self.root, self.root / 'inputs', 2)


if __name__ == '__main__':
    unittest.main()
