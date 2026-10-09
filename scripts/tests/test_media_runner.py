"""The trusted root media runner against a fake `docker` binary: nothing here starts a container."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('media_runner', ROOT / 'deploy/production/mnema-media-runner.py')
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

SHA = 'a' * 40
IMAGE = 'ghcr.io/mattoyuzuru/mnema/media-worker@sha256:' + 'b' * 64
ASSET = '3cbb01c5-a3c0-467d-8e87-ac3a4e7e19ba'
UID, GID = os.getuid(), os.getgid()

FAKE_DOCKER = r'''#!%(python)s
import json, os, pathlib, sys, time
fake = pathlib.Path(os.environ['FAKE_DIR'])
args = sys.argv[1:]
containers = fake / 'containers'
containers.mkdir(exist_ok=True)
(fake / 'killed').mkdir(exist_ok=True)
def record(**data):
    with open(fake / 'log.jsonl', 'a') as log:
        log.write(json.dumps(data) + '\n')
def scenario():
    path = fake / 'scenario.json'
    return json.loads(path.read_text()) if path.exists() else {}
command = args[0] if args else ''
if command == 'run':
    name = args[args.index('--name') + 1]
    mounts = {}
    for index, argument in enumerate(args):
        if argument == '-v':
            source, target = args[index + 1].split(':')[:2]
            mounts[target] = source
    plan = scenario()
    (containers / name).write_text('1')
    inside = mounts['/job/in']
    request = pathlib.Path(inside, 'request.json')
    record(op='run', argv=args, files=sorted(os.listdir(inside)),
           request=json.loads(request.read_text()) if request.exists() else None,
           source=len(pathlib.Path(inside, 'source').read_bytes()) if os.path.exists(os.path.join(inside, 'source')) else None,
           out_mode=oct(os.stat(mounts['/job/out']).st_mode & 0o7777))
    out = mounts['/job/out']
    for filename, spec in plan.get('files', {}).items():
        path = os.path.join(out, filename)
        if 'symlink' in spec:
            os.symlink(spec['symlink'], path)
        elif spec.get('fifo'):
            os.mkfifo(path)
        elif spec.get('deep'):
            os.mkdir(path)
            os.chdir(path)
            for _ in range(spec['deep']):
                os.mkdir('d')
                os.chdir('d')
            os.chdir('/')
        elif spec.get('dir'):
            os.mkdir(path)
            os.mkdir(os.path.join(path, 'nested'))
            pathlib.Path(path, 'nested', 'f').write_text('x')
            os.chmod(os.path.join(path, 'nested'), 0)
            os.chmod(path, spec.get('mode', 0))
        else:
            pathlib.Path(path).write_bytes(spec['text'].encode() if 'text' in spec else b'x' * spec.get('size', 3))
    if plan.get('write_while_hanging'):
        pathlib.Path(out, 'big').write_bytes(b'x' * plan['write_while_hanging'])
    if plan.get('hang'):
        while not (fake / 'killed' / name).exists():
            time.sleep(0.02)
        if not plan.get('zombie'):
            (containers / name).unlink(missing_ok=True)
        sys.exit(137)
    sys.stderr.write(plan.get('stderr', ''))
    if not plan.get('zombie'):
        (containers / name).unlink(missing_ok=True)
    sys.exit(plan.get('exit', 0))
elif command == 'kill':
    record(op='kill', name=args[-1])
    (fake / 'killed' / args[-1]).write_text('1')
elif command == 'rm':
    record(op='rm', name=args[-1])
    if scenario().get('rm_fails'):
        sys.exit(1)
    (containers / args[-1]).unlink(missing_ok=True)
elif command == 'ps':
    record(op='ps')
    if scenario().get('docker_broken'):
        sys.exit(1)
    wanted = [a for a in args if a.startswith('name=')][0][5:]
    exact = wanted.endswith('$')
    pattern = wanted.lstrip('^').removeprefix('/?').rstrip('$')
    for entry in sorted(containers.iterdir()):
        if (entry.name == pattern) if exact else entry.name.startswith(pattern):
            print(entry.name)
else:
    sys.exit(2)
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


class RunnerCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.cleanup)
        self.base = Path(self.temp.name)
        self.work = self.base / 'work'
        self.spool = self.work / 'spool'
        self.spool.mkdir(parents=True)
        self.fake = self.base / 'fake'
        self.fake.mkdir()
        self.docker = self.base / 'docker'
        self.docker.write_text(FAKE_DOCKER % {'python': sys.executable})
        self.docker.chmod(0o755)
        self.state = self.base / 'state'
        self.state.mkdir(mode=0o700)
        self.release_state = self.base / 'release-state'
        self.release_root = self.base / 'etc'
        (self.release_root / 'releases').mkdir(parents=True)
        self.release_state.mkdir()
        self.environment = patch.dict(os.environ, {'FAKE_DIR': str(self.fake)})
        self.environment.start()
        self.record_release({'frontend': 'x', 'identity-account': 'x', 'learning': 'x', 'postgres': 'x', 'media-worker': IMAGE})
        self.runner = self.make_runner()

    def cleanup(self):
        self.environment.stop()
        for path, _, _ in os.walk(self.base):
            try:
                os.chmod(path, 0o755)
            except OSError:
                pass
        self.temp.cleanup()

    def make_runner(self, **options):
        defaults = dict(work_dir=self.work, state_dir=self.state, docker=str(self.docker), release_root=self.release_root,
                        release_state=self.release_state, trusted_uid=UID, learning_uid=UID, learning_gid=GID,
                        worker_uid=UID, worker_gid=GID, require_mount=False, poll=0.02, job_timeout=30)
        defaults.update(options)
        return RUNNER.Runner(RUNNER.Config(**defaults), threading.Event())

    def record_release(self, images, sha=SHA, mode=0o644):
        for path, content in ((self.release_state / 'current.json', {'sha': sha}),
                              (self.release_root / 'releases' / (sha + '.json'), {'sha': sha, 'images': images})):
            path.write_text(json.dumps(content))
            path.chmod(mode)

    def scenario(self, **plan):
        (self.fake / 'scenario.json').write_text(json.dumps(plan))

    def log(self):
        path = self.fake / 'log.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def runs(self):
        return [entry for entry in self.log() if entry['op'] == 'run']

    def request(self, kind='image', data=b'source-bytes', **changes):
        body = {'formatVersion': 1, 'assetId': ASSET, 'generation': 4, 'kind': kind, 'expectedByteLength': len(data),
                'expectedSha256': sha(data), 'maxDurationMs': None if kind == 'image' else 60000}
        body.update(changes)
        return body

    def job(self, name='media-1', kind='image', data=b'source-bytes', request=None, submitted=True, source=True):
        job = self.spool / name
        job.mkdir(mode=0o700)
        if source:
            (job / 'source').write_bytes(data)
        raw = request if isinstance(request, (bytes, str)) else json.dumps(request or self.request(kind, data))
        (job / 'request.json').write_text(raw if isinstance(raw, str) else raw.decode())
        (job / 'output').mkdir(mode=0o700)
        if submitted:
            (job / 'submitted').write_text('')
        return job

    def status(self, job):
        path = job / 'status.json'
        return json.loads(path.read_text()) if path.exists() else None

    def good_files(self, kind='image'):
        return {'result.json': {'text': '{"formatVersion":1}'}, 'image_webp_2048_v1.webp': {'size': 10},
                'image_webp_320_v1.webp': {'size': 5}}

    def scratch_entries(self):
        runner_root = self.work / '.runner'
        return sorted(p.name for p in runner_root.iterdir()) if runner_root.exists() else []


class HappyPathTests(RunnerCase):
    def test_a_job_runs_in_a_locked_down_container_from_the_recorded_release_and_ends_with_a_root_verdict(self):
        job = self.job(data=b'0123456789')
        self.scenario(files=self.good_files())
        self.runner.process('media-1')

        self.assertEqual(self.status(job), {'formatVersion': 1, 'exitCode': 0, 'code': None})
        self.assertTrue((job / 'claimed').exists())
        self.assertEqual(sorted(p.name for p in (job / 'output').iterdir()),
                         ['image_webp_2048_v1.webp', 'image_webp_320_v1.webp', 'result.json'])
        self.assertEqual((job / 'output' / 'image_webp_320_v1.webp').read_bytes(), b'x' * 5)
        self.assertEqual(stat.S_IMODE((job / 'status.json').stat().st_mode), 0o644)
        self.assertFalse((job / 'status.json.tmp').exists())
        # one container, from exactly the recorded image, with every restriction and only the two mounts
        [run] = self.runs()
        argv = run['argv']
        name = argv[argv.index('--name') + 1]
        self.assertTrue(name.startswith('mnema-media-job-'))
        for pair in (['--network', 'none'], ['--cap-drop', 'ALL'], ['--security-opt', 'no-new-privileges'],
                     ['--pids-limit', '64'], ['--memory', '3g'], ['--memory-swap', '3g'], ['--cpus', '2'],
                     ['--pull', 'never'], ['--user', '%d:%d' % (UID, GID)]):
            self.assertEqual(argv[argv.index(pair[0]) + 1], pair[1], pair[0])
        self.assertIn('--read-only', argv)
        self.assertIn('--rm', argv)
        self.assertEqual(argv[argv.index(IMAGE):], [IMAGE, '--manifest', '/job/in/request.json', '--source',
                                                    '/job/in/source', '--output', '/job/out'])
        mounts = [argv[i + 1] for i, a in enumerate(argv) if a == '-v']
        self.assertEqual(len(mounts), 2)
        self.assertTrue(mounts[0].endswith('/in:/job/in:ro') and mounts[1].endswith('/out:/job/out'))
        self.assertEqual(run['files'], ['request.json', 'source'])
        self.assertEqual(run['source'], 10)
        self.assertEqual(run['out_mode'], '0o700')
        # nothing of the job survives: no container, no scratch
        self.assertEqual(list((self.fake / 'containers').iterdir()), [])
        self.assertEqual(self.scratch_entries(), [])
        self.assertEqual(stat.S_IMODE((self.work / '.runner').stat().st_mode), 0o700)

    def test_the_container_gets_a_normalized_request_not_learnings_bytes(self):
        job = self.job(request=json.dumps(self.request(), indent=4) + '\n')
        self.scenario(files=self.good_files())
        self.runner.process('media-1')
        [run] = self.runs()
        self.assertEqual(run['request'], self.request())
        self.assertEqual(self.status(job)['exitCode'], 0)

    def test_the_verdict_is_written_last_and_never_before_the_outputs(self):
        job = self.job()
        self.scenario(files=self.good_files())
        seen = []
        real = RUNNER.Runner.publish

        def spy(runner, jfd, exit_code, code):
            seen.append((exit_code, sorted(p.name for p in (job / 'output').iterdir()), (job / 'status.json').exists()))
            return real(runner, jfd, exit_code, code)

        with patch.object(RUNNER.Runner, 'publish', spy):
            self.runner.process('media-1')
        self.assertEqual(seen, [(0, ['image_webp_2048_v1.webp', 'image_webp_320_v1.webp', 'result.json'], False)])

    def test_exit_codes_map_to_the_spool_verdicts(self):
        cases = [
            ({'exit': 2, 'stderr': '{"status":"rejected","code":"unsupported_audio"}\n'}, (2, 'unsupported_audio')),
            ({'exit': 2, 'stderr': '{"status":"rejected","code":"Bad Code"}\n'}, (3, 'worker_failed')),
            ({'exit': 2, 'stderr': 'not json'}, (3, 'worker_failed')),
            ({'exit': 3, 'stderr': '{"status":"retryable","code":"codec_timeout"}\n'}, (3, 'codec_timeout')),
            ({'exit': 1}, (3, 'worker_failed')),
            ({'exit': 125}, (3, 'worker_failed')),
            ({'exit': 137}, (3, 'worker_failed')),
        ]
        for index, (plan, expected) in enumerate(cases):
            with self.subTest(plan=plan):
                job = self.job('media-%d' % index)
                self.scenario(**plan)
                self.runner.process('media-%d' % index)
                status = self.status(job)
                self.assertEqual((status['exitCode'], status['code']), expected)
                self.assertEqual(list((job / 'output').iterdir()), [])     # a failed job hands nothing back


class RequestAndSourceTests(RunnerCase):
    def test_a_request_outside_the_schema_is_rejected_without_starting_anything(self):
        base = self.request()
        bad = {
            'extra key': {**base, 'extra': 1}, 'missing key': {k: v for k, v in base.items() if k != 'generation'},
            'version': {**base, 'formatVersion': 2}, 'asset not v4': {**base, 'assetId': '3cbb01c5-a3c0-167d-8e87-ac3a4e7e19ba'},
            'asset not canonical': {**base, 'assetId': ASSET.upper()}, 'kind': {**base, 'kind': 'document'},
            'zero size': {**base, 'expectedByteLength': 0}, 'huge size': {**base, 'expectedByteLength': 65 * 1024 * 1024},
            'digest': {**base, 'expectedSha256': 'A' * 64}, 'image with duration': {**base, 'maxDurationMs': 5},
            'video without duration': {**self.request('video'), 'maxDurationMs': None},
            'video too long': {**self.request('video'), 'maxDurationMs': 300001},
            'negative generation': {**base, 'generation': -1}, 'bool generation': {**base, 'generation': True},
        }
        for index, (label, request) in enumerate(bad.items()):
            with self.subTest(label):
                job = self.job('media-%d' % index, request=request)
                self.runner.process('media-%d' % index)
                self.assertEqual(self.status(job), {'formatVersion': 1, 'exitCode': 2, 'code': 'invalid_manifest'})
        for index, raw in enumerate(('not json', '[]', '{"formatVersion":1}', 'x' * 5000), start=100):
            with self.subTest(raw=raw[:10]):
                job = self.job('media-%d' % index, request=raw)
                self.runner.process('media-%d' % index)
                self.assertEqual(self.status(job)['code'], 'invalid_manifest')
        self.assertEqual(self.runs(), [])

    def test_a_request_or_source_that_is_a_link_or_a_fifo_is_never_followed(self):
        outside = self.base / 'outside'
        outside.write_text(json.dumps(self.request()))
        job = self.job('media-1')
        (job / 'request.json').unlink()
        (job / 'request.json').symlink_to(outside)
        self.runner.process('media-1')
        self.assertEqual(self.status(job)['code'], 'invalid_manifest')

        job = self.job('media-2')
        (job / 'source').unlink()
        (job / 'source').symlink_to(self.base / 'work')
        self.runner.process('media-2')
        self.assertEqual(self.status(job), {'formatVersion': 1, 'exitCode': 3, 'code': 'source_unreadable'})

        job = self.job('media-3', source=False)
        os.mkfifo(job / 'source')
        self.runner.process('media-3')                                 # must not block on the pipe
        self.assertEqual(self.status(job)['code'], 'source_unreadable')

        job = self.job('media-4', source=False)                         # absent
        self.runner.process('media-4')
        self.assertEqual(self.status(job)['code'], 'source_unreadable')
        self.assertEqual(self.runs(), [])
        self.assertEqual(self.scratch_entries(), [])

    def test_a_source_that_is_not_the_declared_size_is_refused(self):
        job = self.job(request=self.request(data=b'0123456789'), data=b'01234')
        self.runner.process('media-1')
        self.assertEqual(self.status(job)['code'], 'source_unreadable')

    def test_a_job_directory_not_owned_by_learning_or_without_the_marker_is_not_touched(self):
        job = self.job()
        runner = self.make_runner(learning_uid=UID + 1)
        self.assertEqual(runner.discover(), [])
        runner.process('media-1')
        self.assertIsNone(self.status(job))
        self.assertFalse((job / 'claimed').exists())
        pending = self.job('media-2', submitted=False)
        done = self.job('media-3')
        (done / 'status.json').write_text('{}')
        (self.spool / 'media-link').symlink_to(job)
        (self.spool / 'not-a-job').mkdir()
        (self.spool / 'not-a-job' / 'submitted').write_text('')
        self.assertEqual(self.runner.discover(), ['media-1'])
        self.assertIsNone(self.status(pending))

    def test_discovery_is_oldest_submission_first(self):
        first, second = self.job('media-b'), self.job('media-a')
        os.utime(first / 'submitted', ns=(1_000_000_000, 1_000_000_000))
        os.utime(second / 'submitted', ns=(2_000_000_000, 2_000_000_000))
        self.assertEqual(self.runner.discover(), ['media-b', 'media-a'])


class ReleaseTests(RunnerCase):
    def test_the_image_comes_only_from_the_recorded_release_manifest(self):
        self.assertEqual(self.runner.current_image(), IMAGE)
        for label, mutate in (
                ('no record', lambda: (self.release_state / 'current.json').unlink()),
                ('legacy release', lambda: self.record_release({'frontend': 'x', 'learning': 'x'})),
                ('foreign namespace', lambda: self.record_release({'media-worker': 'ghcr.io/other/mnema/media-worker@sha256:' + 'b' * 64})),
                ('tag, not digest', lambda: self.record_release({'media-worker': 'ghcr.io/mattoyuzuru/mnema/media-worker:latest'})),
                ('other service', lambda: self.record_release({'media-worker': 'ghcr.io/mattoyuzuru/mnema/learning@sha256:' + 'b' * 64})),
                ('writable by others', lambda: self.record_release({'media-worker': IMAGE}, mode=0o666)),
                ('path in the sha', lambda: (self.release_state / 'current.json').write_text('{"sha": "../../etc/passwd"}')),
                ('garbage', lambda: (self.release_state / 'current.json').write_text('garbage'))):
            with self.subTest(label):
                self.record_release({'media-worker': IMAGE})
                mutate()
                with self.assertRaises(RUNNER.NoRelease):
                    self.runner.current_image()

    def test_the_release_files_must_belong_to_the_trusted_owner_and_not_be_links(self):
        runner = self.make_runner(trusted_uid=UID + 1)
        with self.assertRaises(RUNNER.NoRelease):
            runner.current_image()
        self.record_release({'media-worker': IMAGE})
        real = self.release_state / 'real.json'
        (self.release_state / 'current.json').rename(real)
        (self.release_state / 'current.json').symlink_to(real)
        with self.assertRaises(RUNNER.NoRelease):
            self.runner.current_image()

    def test_without_a_usable_release_a_submitted_job_is_left_alone(self):
        (self.release_state / 'current.json').unlink()
        job = self.job()
        self.runner.process('media-1')
        self.assertIsNone(self.status(job))
        self.assertFalse((job / 'claimed').exists())
        self.assertEqual(self.runs(), [])

    def test_a_development_image_override_replaces_the_manifest_but_must_still_be_a_plain_reference(self):
        self.assertEqual(self.make_runner(image='mnema-media-worker:local').current_image(), 'mnema-media-worker:local')
        with self.assertRaises(RUNNER.NoRelease):
            self.make_runner(image='--privileged evil').current_image()


class InterruptionTests(RunnerCase):
    def test_a_job_that_overruns_is_killed_and_leaves_nothing_behind(self):
        job = self.job()
        self.scenario(hang=True)
        runner = self.make_runner(job_timeout=0.4)
        runner.process('media-1')
        self.assertEqual(self.status(job), {'formatVersion': 1, 'exitCode': 3, 'code': 'worker_timeout'})
        self.assertIn('kill', [entry['op'] for entry in self.log()])
        self.assertEqual(list((self.fake / 'containers').iterdir()), [])
        self.assertEqual(self.scratch_entries(), [])
        self.assertEqual(list((job / 'output').iterdir()), [])

    def test_a_cancel_marker_kills_the_container(self):
        job = self.job()
        self.scenario(hang=True)
        threading.Timer(0.4, lambda: (job / 'cancel').write_text('')).start()
        self.runner.process('media-1')
        self.assertEqual(self.status(job)['code'], 'cancelled')
        self.assertEqual(list((self.fake / 'containers').iterdir()), [])

    def test_a_job_directory_that_disappears_kills_the_container_without_a_verdict(self):
        import shutil
        job = self.job()
        self.scenario(hang=True)
        threading.Timer(0.4, lambda: shutil.rmtree(job)).start()
        self.runner.process('media-1')
        self.assertFalse(job.exists())
        self.assertEqual(list((self.fake / 'containers').iterdir()), [])
        self.assertEqual(self.scratch_entries(), [])

    def test_stopping_the_runner_kills_the_running_job(self):
        job = self.job()
        self.scenario(hang=True)
        threading.Timer(0.4, self.runner.stop.set).start()
        self.runner.process('media-1')
        self.assertEqual(self.status(job)['code'], 'runner_stopped')

    def test_a_job_whose_output_grows_without_bound_is_killed(self):
        job = self.job()
        self.scenario(hang=True, write_while_hanging=5000)
        runner = self.make_runner(out_cap=1000)
        runner.process('media-1')
        self.assertEqual(self.status(job)['code'], 'disk_limit')

    def test_a_container_that_cannot_be_removed_gets_no_outputs_copied(self):
        job = self.job()
        self.scenario(files=self.good_files(), zombie=True, rm_fails=True)
        self.runner.process('media-1')
        self.assertEqual(self.status(job), {'formatVersion': 1, 'exitCode': 3, 'code': 'container_cleanup_failed'})
        self.assertEqual(list((job / 'output').iterdir()), [])


class OutputValidationTests(RunnerCase):
    def assert_refused(self, files, label):
        job = self.job()
        self.scenario(files=files)
        self.runner.process('media-1')
        self.assertEqual(self.status(job), {'formatVersion': 1, 'exitCode': 3, 'code': 'output_invalid'}, label)
        self.assertEqual(list((job / 'output').iterdir()), [], label)     # all or nothing
        self.assertEqual(self.scratch_entries(), [], label)

    def test_anything_but_the_exact_result_files_is_refused(self):
        secret = self.base / 'secret'
        secret.write_text('s')
        good = self.good_files()
        cases = {
            'symlinked manifest': {**good, 'result.json': {'symlink': str(secret)}},
            'symlinked variant': {**good, 'image_webp_320_v1.webp': {'symlink': str(secret)}},
            'fifo variant': {**good, 'image_webp_320_v1.webp': {'fifo': True}},
            'unexpected name': {**good, 'evil.sh': {'size': 3}},
            'partial file': {**good, '.image_webp_2048_v1.webp.part': {'size': 3}},
            'directory in place of a file': {**good, 'image_webp_320_v1.webp': {'dir': True}},
            'no manifest': {k: v for k, v in good.items() if k != 'result.json'},
            'oversized manifest': {**good, 'result.json': {'size': RUNNER.MAX_RESULT_BYTES + 1}},
            'too many files': {**good, 'image_gif_2048_v1.gif': {'size': 3}, 'audio_aac_m4a_v1.m4a': {'size': 3}},
        }
        for label, files in cases.items():
            with self.subTest(label):
                (self.spool / 'media-1').exists() and __import__('shutil').rmtree(self.spool / 'media-1')
                self.assert_refused(files, label)

    def test_an_oversized_variant_and_an_excess_total_are_refused(self):
        with patch.object(RUNNER, 'MAX_VARIANT_BYTES', 8):
            self.assert_refused({**self.good_files(), 'image_webp_2048_v1.webp': {'size': 9}}, 'oversized variant')
        (self.spool / 'media-1').exists() and __import__('shutil').rmtree(self.spool / 'media-1')
        with patch.object(RUNNER, 'MAX_TOTAL_OUTPUT_BYTES', 12):
            self.assert_refused(self.good_files(), 'total over the cap')

    def test_an_output_directory_that_is_not_empty_or_not_ours_is_refused(self):
        job = self.job()
        (job / 'output' / 'leftover').write_text('x')
        self.scenario(files=self.good_files())
        self.runner.process('media-1')
        self.assertEqual(self.status(job)['code'], 'output_invalid')
        self.assertEqual(sorted(p.name for p in (job / 'output').iterdir()), ['leftover'])


class CleanupAndSweepTests(RunnerCase):
    def test_scratch_with_directories_nobody_can_enter_is_removed(self):
        job = self.job()
        self.scenario(files={**self.good_files(), 'locked': {'dir': True, 'mode': 0}})     # left by a hostile container
        self.runner.process('media-1')
        self.assertEqual(self.status(job)['code'], 'output_invalid')
        self.assertEqual(self.scratch_entries(), [])
        tree = self.base / 'tree'
        (tree / 'a' / 'b').mkdir(parents=True)
        (tree / 'a' / 'b' / 'f').write_text('x')
        os.chmod(tree / 'a' / 'b', 0)
        os.chmod(tree / 'a', 0)
        RUNNER.remove_tree(tree)
        self.assertFalse(tree.exists())

    def test_removing_a_tree_never_follows_a_link_out_of_it(self):
        outside = self.base / 'outside'
        outside.mkdir()
        (outside / 'precious').write_text('keep')
        tree = self.base / 'tree'
        tree.mkdir()
        (tree / 'escape').symlink_to(outside)
        RUNNER.remove_tree(tree)
        self.assertFalse(tree.exists())
        self.assertEqual((outside / 'precious').read_text(), 'keep')

    def test_startup_removes_leftover_job_containers_and_scratch_but_nothing_else(self):
        (self.fake / 'containers').mkdir()
        for name in ('mnema-media-job-old1', 'mnema-media-job-old2', 'mnema-prod-learning-1'):
            (self.fake / 'containers' / name).write_text('1')
        leftover = self.work / '.runner' / 'dead'
        (leftover / 'out' / 'locked').mkdir(parents=True)
        (leftover / 'out' / 'locked' / 'f').write_text('x')
        os.chmod(leftover / 'out' / 'locked', 0)
        self.runner.startup_sweep()
        self.assertEqual(sorted(p.name for p in (self.fake / 'containers').iterdir()), ['mnema-prod-learning-1'])
        self.assertEqual(self.scratch_entries(), [])

    def test_a_job_that_raises_never_stops_the_service(self):
        self.job()
        with patch.object(RUNNER.Runner, 'process', side_effect=RuntimeError('boom')):
            self.runner.guarded('media-1')
        self.assertEqual(self.runner.in_flight, set())


def open_descriptors():
    return len(os.listdir('/dev/fd'))


class HardeningTests(RunnerCase):
    """Round 5: deep trees, containers that will not die, startup without Docker, and pre-planted files."""

    def make_deep_tree(self, root, depth):
        Path(root).mkdir()
        import subprocess
        code = 'import os, sys\nos.chdir(sys.argv[1])\nfor _ in range(int(sys.argv[2])):\n    os.mkdir("d")\n    os.chdir("d")'
        subprocess.run([sys.executable, '-c', code, str(root), str(depth)], check=True, timeout=300)

    def test_a_tree_nested_two_thousand_deep_is_removed_without_recursion_errors(self):
        tree = self.base / 'deep'
        self.make_deep_tree(tree, 2000)
        self.assertTrue(RUNNER.remove_tree(tree))
        self.assertFalse(os.path.lexists(tree))

    def test_a_container_that_leaves_a_deep_tree_does_not_leak_scratch_or_descriptors(self):
        job = self.job()
        self.scenario(files={**self.good_files(), 'deep': {'deep': 2000}})
        before = open_descriptors()
        self.runner.process('media-1')
        # a subdirectory is never a result: refused after the container ended, or cut short by the output watch
        self.assertIn(self.status(job)['code'], ('output_invalid', 'disk_limit'))
        self.assertEqual(self.scratch_entries(), [])
        self.assertEqual(open_descriptors(), before)

    def test_a_scratch_removal_that_raises_still_closes_every_descriptor(self):
        job = self.job()
        self.scenario(files=self.good_files())
        before = open_descriptors()
        with patch.object(RUNNER, 'remove_tree', side_effect=RuntimeError('boom')):
            self.runner.process('media-1')
        self.assertEqual(self.status(job)['exitCode'], 0)
        self.assertEqual(open_descriptors(), before)

    def test_a_container_that_cannot_be_killed_keeps_its_scratch_and_is_retried_until_it_is_gone(self):
        job = self.job()
        self.scenario(files=self.good_files(), zombie=True, rm_fails=True)
        self.runner.process('media-1')
        self.assertEqual(self.status(job)['code'], 'container_cleanup_failed')
        [container] = list(self.runner.stuck)
        scratch = self.runner.stuck[container]
        self.assertTrue(scratch.exists())                           # nothing is deleted under a container that may still run
        self.assertEqual(len(self.scratch_entries()), 1)
        self.runner.retry_stuck()                                    # Docker still refuses: still kept, still remembered
        self.assertIn(container, self.runner.stuck)
        self.assertTrue(scratch.exists())
        self.scenario(zombie=True)                                   # Docker recovers
        self.runner.retry_stuck()
        self.assertEqual(self.runner.stuck, {})
        self.assertFalse(scratch.exists())
        self.assertEqual(list((self.fake / 'containers').iterdir()), [])
        kills = [entry for entry in self.log() if entry['op'] == 'kill']
        self.assertGreaterEqual(len(kills), 3)                       # tried again on every attempt

    def test_the_loop_retries_stuck_containers(self):
        self.job()
        self.scenario(files=self.good_files(), zombie=True, rm_fails=True)
        self.runner.process('media-1')
        self.scenario(zombie=True)
        thread = threading.Thread(target=self.runner.run_forever)
        thread.start()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and self.runner.stuck:
            time.sleep(0.05)
        self.runner.stop.set()
        thread.join(10)
        self.assertEqual(self.runner.stuck, {})
        self.assertEqual(self.scratch_entries(), [])

    def test_an_exception_while_the_container_runs_still_kills_and_removes_it(self):
        job = self.job()
        self.scenario(hang=True)
        calls = []

        def explode(runner, sfd, name, info):
            calls.append(1)
            if len(calls) > 3:
                raise RuntimeError('boom')
            return False

        with patch.object(RUNNER.Runner, 'job_gone', explode):
            self.runner.process('media-1')
        self.assertEqual(self.status(job)['code'], 'worker_failed')
        self.assertEqual(list((self.fake / 'containers').iterdir()), [])
        self.assertEqual(self.scratch_entries(), [])

    def test_startup_without_a_docker_answer_leaves_scratch_alone_and_never_raises(self):
        leftover = self.work / '.runner' / 'maybe-running'
        (leftover / 'out').mkdir(parents=True)
        for label, runner in (('docker missing', self.make_runner(docker=str(self.base / 'no-such-docker'))),
                              ('docker failing', self.runner)):
            with self.subTest(label):
                if label == 'docker failing':
                    self.scenario(docker_broken=True)
                self.assertIsNone(runner.leftover_containers())
                runner.startup_sweep()
                self.assertTrue(leftover.exists())                     # "I could not ask" is not "there are none"

    def test_startup_keeps_scratch_while_a_job_container_cannot_be_removed(self):
        (self.fake / 'containers').mkdir()
        (self.fake / 'containers' / 'mnema-media-job-stuck').write_text('1')
        self.scenario(rm_fails=True)
        leftover = self.work / '.runner' / 'dead'
        leftover.mkdir(parents=True)
        self.runner.startup_sweep()
        self.assertTrue(leftover.exists())

    def test_startup_survives_a_scratch_removal_that_fails_or_raises(self):
        leftover = self.work / '.runner' / 'dead'
        leftover.mkdir(parents=True)
        for failure in (RuntimeError('boom'), None):
            with self.subTest(failure=failure), patch.object(RUNNER, 'remove_tree', side_effect=failure, return_value=False):
                self.runner.startup_sweep()
        self.assertTrue(leftover.exists())

    def test_the_verdict_never_goes_through_something_planted_in_the_job_directory(self):
        job = self.job()
        victim = self.base / 'victim'
        victim.write_text('keep')
        os.mkfifo(job / 'status.json.tmp')                             # the old fixed name: a FIFO would have blocked the runner
        os.link(victim, job / 'status.json')                           # a hard link where the verdict goes
        self.runner.publish(os.open(job, os.O_RDONLY | os.O_DIRECTORY), 0, None)
        self.assertEqual(victim.read_text(), 'keep')
        self.assertEqual(self.status(job), {'formatVersion': 1, 'exitCode': 0, 'code': None})
        self.assertTrue(stat.S_ISFIFO((job / 'status.json.tmp').lstat().st_mode))     # untouched
        self.assertEqual([p.name for p in job.iterdir() if p.name.startswith('.status-')], [])
        job2 = self.job('media-2')
        os.link(victim, job2 / 'status.json.tmp')
        self.runner.publish(os.open(job2, os.O_RDONLY | os.O_DIRECTORY), 3, 'x')
        self.assertEqual(victim.read_text(), 'keep')
        self.assertEqual(self.status(job2)['code'], 'x')

    def test_a_job_that_was_already_cancelled_is_not_started(self):
        job = self.job()
        (job / 'cancel').write_text('')
        self.assertEqual(self.runner.discover(), [])

    def test_the_output_watch_uses_descriptors_counts_links_as_links_and_treats_a_subdirectory_as_too_much(self):
        big = self.base / 'big'
        big.write_bytes(b'x' * 100_000)
        job = self.job()
        self.scenario(hang=True, files={'link': {'symlink': str(big)}})
        runner = self.make_runner(out_cap=1000, job_timeout=0.6)
        runner.process('media-1')
        self.assertEqual(self.status(job)['code'], 'worker_timeout')            # the link's own size is tiny: not "disk_limit"
        shutil_job = self.job('media-2')
        self.scenario(hang=True, files={'sub': {'dir': True, 'mode': 0o755}})
        self.make_runner(job_timeout=30).process('media-2')
        self.assertEqual(self.status(shutil_job)['code'], 'disk_limit')


class MountAndLoopTests(RunnerCase):
    def test_jobs_run_only_when_the_work_directory_is_its_own_filesystem_owned_by_root(self):
        strict = self.make_runner(require_mount=True)
        self.assertFalse(strict.mount_ok())                            # same device as its parent: just a directory
        self.assertTrue(self.make_runner().mount_ok())                 # (development stacks mount a volume there)
        self.assertFalse(self.make_runner(trusted_uid=UID + 1).mount_ok())
        self.work.chmod(0o775)
        self.assertFalse(self.make_runner().mount_ok())
        self.work.chmod(0o755)
        real = os.lstat

        def different_device(path):
            info = real(path)
            if str(path) == str(self.work):
                return types_ns(st_mode=info.st_mode, st_uid=info.st_uid, st_dev=info.st_dev + 1)
            return info

        with patch.object(RUNNER.os, 'lstat', side_effect=different_device):
            self.assertTrue(strict.mount_ok())

    def test_the_loop_refuses_to_run_jobs_without_the_mount_and_processes_them_with_it(self):
        job = self.job()
        self.scenario(files=self.good_files())
        refusing = self.make_runner(require_mount=True)
        thread = threading.Thread(target=refusing.run_forever)
        thread.start()
        time.sleep(0.5)
        refusing.stop.set()
        thread.join(10)
        self.assertIsNone(self.status(job))
        self.assertEqual(self.runs(), [])

        second = self.job('media-2')
        runner = self.make_runner()
        thread = threading.Thread(target=runner.run_forever)
        thread.start()
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and not (self.status(job) and self.status(second)):
            time.sleep(0.05)
        runner.stop.set()
        thread.join(10)
        self.assertEqual([self.status(job)['exitCode'], self.status(second)['exitCode']], [0, 0])
        self.assertTrue((self.state / 'heartbeat').exists())
        self.assertEqual(stat.S_IMODE((self.state / 'heartbeat').stat().st_mode), 0o600)

    def test_the_development_volume_mode_uses_subpaths_of_the_volume_and_no_host_paths(self):
        runner = self.make_runner(volume='mnema-dev_local_media_work')
        arguments = runner.mount_arguments('tok')
        self.assertEqual(arguments, [
            '--mount', 'type=volume,source=mnema-dev_local_media_work,target=/job/in,readonly,volume-subpath=.runner/tok/in',
            '--mount', 'type=volume,source=mnema-dev_local_media_work,target=/job/out,volume-subpath=.runner/tok/out'])

    def test_the_service_refuses_to_start_unless_it_is_root(self):
        if os.geteuid() == 0:
            self.skipTest('running as root')
        self.assertEqual(RUNNER.main([]), 1)


def types_ns(**fields):
    import types
    return types.SimpleNamespace(**fields)


if __name__ == '__main__':
    unittest.main()
