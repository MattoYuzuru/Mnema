#!/usr/bin/python3 -I
"""Root media runner: one throw-away container per media job; only root writes a verdict.

FFmpeg parses hostile input, so the code that runs it is treated as compromised and is never given a
lifetime beyond one job. This small trusted service owns the whole job life cycle:

  1. find `<work>/spool/media-*` job directories that Learning (UID 10001) prepared and marked with
     `submitted`; read `request.json` (O_NOFOLLOW, size-capped, exact schema) and copy `source` (O_NOFOLLOW,
     regular file, declared size) into a root-made scratch directory under `<work>/.runner/`, which only
     root can enter;
  2. run the media-worker image from the release recorded as current, in a fresh container with no network,
     a read-only root, no capability, UID 10002, bounded pids/memory/cpus and only two mounts: the scratch
     `in/` (read-only) and `out/` (owned by 10002). The container is killed on timeout, on Learning's `cancel`
     marker, when the job directory disappears, when `out/` grows past its cap, and when the runner stops;
  3. once the container is gone, validate `out/` as root (regular files only, an exact allowlist of names, size
     and count caps), copy the files into Learning's `output/` (owned by Learning) and only then atomically write
     `status.json` (root-owned). Learning accepts a verdict only from a root-owned file;
  4. remove the scratch tree as root, so even directories with no permissions cannot persist.

Nothing in the work directory is trusted beyond Learning's request schema, and every access inside it is relative
to an opened directory with O_NOFOLLOW. The runner refuses to run jobs unless the work directory is the root of its
own filesystem owned by root. It is installed by the administrator (`/usr/local/sbin/mnema-media-runner`) and
run by `mnema-media-runner.service`; the `--image`/`--volume` options exist for the development stack only.
"""

import argparse
import errno
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import threading
import time
import uuid

GIB = 1024 ** 3
MIB = 1024 ** 2
JOB_NAME = re.compile(r'media-[A-Za-z0-9_-]{1,64}')
CODE = re.compile(r'[a-z][a-z0-9_]{0,63}')
IMAGE_REF = re.compile(r'ghcr\.io/mattoyuzuru/mnema/media-worker@sha256:[0-9a-f]{64}')
DEV_IMAGE_REF = re.compile(r'[a-zA-Z0-9][a-zA-Z0-9._:/@-]{0,255}')
VOLUME_NAME = re.compile(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}')
SHA = re.compile(r'[0-9a-f]{40}')
HEX64 = re.compile(r'[0-9a-f]{64}')
CONTAINER_PREFIX = 'mnema-media-job-'
# Source ceilings of the worker policy (policy.py); the runner never copies more than the request declares.
CEILINGS = {'image': 64 * MIB, 'audio': 512 * MIB, 'video': 4 * GIB}
DURATION_CEILINGS = {'audio': 3_600_000, 'video': 300_000}
REQUEST_KEYS = {'formatVersion', 'assetId', 'generation', 'kind', 'expectedByteLength', 'expectedSha256', 'maxDurationMs'}
# The only files a job may hand back: the manifest and the variants of the result schema, by exact name.
RESULT_FILE = 'result.json'
VARIANT_FILES = frozenset({
    'image_webp_2048_v1.webp', 'image_webp_320_v1.webp', 'image_gif_2048_v1.gif', 'image_gif_poster_webp_320_v1.webp',
    'audio_aac_m4a_v1.m4a', 'video_h264_aac_sdr_1080_v1.mp4', 'video_poster_webp_960_v1.webp'})
MAX_RESULT_BYTES = 64 * 1024
MAX_VARIANT_BYTES = GIB
MAX_TOTAL_OUTPUT_BYTES = GIB
MAX_OUTPUT_ENTRIES = 4
STDERR_CAP = 64 * 1024
OPEN_DIR = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC


class NoRelease(Exception):
    """No recorded release carries a media-worker image: leave the queue alone."""


class BadRequest(Exception):
    pass


class JobFailure(Exception):
    """A retryable failure with a stable code."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


class Config:
    def __init__(self, **options):
        self.work_dir = Path('/var/lib/mnema/media-work')
        self.spool = 'spool'
        self.runner_dir = '.runner'
        self.state_dir = Path('/var/lib/mnema-media-runner')
        self.docker = '/usr/bin/docker'
        self.release_root = Path('/etc/mnema/production')
        self.release_state = Path('/var/lib/mnema-release')
        self.trusted_uid = 0          # owner required of the work directory, the release manifests and our state
        self.learning_uid = 10001
        self.learning_gid = 10001
        self.worker_uid = 10002
        self.worker_gid = 10002
        self.image = None             # development only: a fixed image reference instead of the recorded release
        self.volume = None            # development only: Docker volume holding the work directory (no host paths)
        self.require_mount = True
        self.job_timeout = 30 * 60
        self.poll = 0.5
        self.out_cap = 2 * GIB
        self.out_entries_cap = 256
        self.parallel = 1
        for name, value in options.items():
            if not hasattr(self, name):
                raise TypeError('unknown option ' + name)
            setattr(self, name, value)


def log(message):
    print(message, file=sys.stderr, flush=True)


# ---- descriptor-relative access to the (Learning-writable) spool -------------------------------------------------

def open_dir(name, dir_fd=None):
    return os.open(name, OPEN_DIR, dir_fd=dir_fd)


def lstat_at(dir_fd, name):
    try:
        return os.stat(name, dir_fd=dir_fd, follow_symlinks=False)
    except FileNotFoundError:
        return None


def read_small(dir_fd, name, cap):
    """Bytes of a regular file opened without following links; refuses anything longer than the cap."""
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=dir_fd)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > cap:
            raise BadRequest('not a small regular file')
        data = b''
        while len(data) <= cap:
            chunk = os.read(fd, cap + 1 - len(data))
            if not chunk:
                return data
            data += chunk
        raise BadRequest('too large')
    finally:
        os.close(fd)


def parse_request(raw):
    """The exact request schema Learning writes (and the worker CLI accepts); returns the normalized dict."""
    try:
        data = json.loads(raw.decode('utf-8'))
    except (ValueError, UnicodeDecodeError):
        raise BadRequest('not json') from None
    if not isinstance(data, dict) or set(data) != REQUEST_KEYS or data['formatVersion'] != 1 \
            or type(data['formatVersion']) is not int:
        raise BadRequest('schema')
    kind = data['kind']
    try:
        asset = uuid.UUID(data['assetId']) if isinstance(data['assetId'], str) else None
    except ValueError:
        asset = None
    if asset is None or asset.version != 4 or str(asset) != data['assetId']:
        raise BadRequest('asset')
    if kind not in CEILINGS or type(data['generation']) is not int or data['generation'] < 0:
        raise BadRequest('kind or generation')
    size = data['expectedByteLength']
    if type(size) is not int or not 0 < size <= CEILINGS[kind]:
        raise BadRequest('size')
    if not isinstance(data['expectedSha256'], str) or not HEX64.fullmatch(data['expectedSha256']):
        raise BadRequest('digest')
    duration = data['maxDurationMs']
    if kind == 'image':
        if duration is not None:
            raise BadRequest('duration')
    elif type(duration) is not int or not 0 < duration <= DURATION_CEILINGS[kind]:
        raise BadRequest('duration')
    return {key: data[key] for key in ('formatVersion', 'assetId', 'generation', 'kind', 'expectedByteLength',
                                        'expectedSha256', 'maxDurationMs')}


def tool(*candidates):
    return next((path for path in candidates if os.path.exists(path)), candidates[0])


def remove_tree(path, timeout=300):
    """Remove a scratch tree the container may have filled with arbitrarily deep or unreadable directories.

    Python's rmtree is recursive and dies with RecursionError on a deep tree, so the work is done by `rm -rf`, which
    walks iteratively and never follows links; `path` is always made by the runner itself under its root-only scratch
    parent, never chosen by the container. Root enters any directory (CAP_DAC_OVERRIDE/CAP_FOWNER); only when not
    root (development, tests) is the tree first made accessible. Never raises; returns whether the tree is gone."""
    try:
        if os.geteuid() != 0:
            subprocess.run([tool('/bin/chmod', '/usr/bin/chmod'), '-R', 'u+rwx', '--', str(path)], cwd='/',
                           stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout)
        command = [tool('/usr/bin/rm', '/bin/rm'), '-rf']
        if sys.platform == 'linux':
            command.append('--one-file-system')
        subprocess.run(command + ['--', str(path)], cwd='/', stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as error:
        log('removing %s failed: %s' % (path, type(error).__name__))
    return not os.path.lexists(path)


class Runner:
    def __init__(self, config, stop=None, clock=time.monotonic):
        self.config = config
        self.stop = stop or threading.Event()
        self.clock = clock
        self.in_flight = set()
        self.lock = threading.Lock()
        self._last_beat = None
        self.stuck = {}   # container name -> scratch path of a job whose container could not be confirmed gone

    # ---- environment checks -----------------------------------------------------------------------------------

    def mount_ok(self):
        """The work directory is the root of its own filesystem, owned by root and writable by no one else."""
        config = self.config
        try:
            info = os.lstat(config.work_dir)
            parent = os.lstat(config.work_dir.parent)
        except OSError:
            return False
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != config.trusted_uid or info.st_mode & 0o022:
            return False
        return not config.require_mount or info.st_dev != parent.st_dev

    def current_image(self):
        """The media-worker image of the release the dispatcher recorded as current; nothing else is ever run."""
        config = self.config
        if config.image is not None:
            if not DEV_IMAGE_REF.fullmatch(config.image):
                raise NoRelease()
            return config.image
        try:
            sha = json.loads(self.trusted_text(config.release_state / 'current.json'))['sha']
            if not isinstance(sha, str) or not SHA.fullmatch(sha):
                raise NoRelease()
            image = json.loads(self.trusted_text(config.release_root / 'releases' / (sha + '.json')))['images']['media-worker']
        except (OSError, ValueError, KeyError, TypeError):
            raise NoRelease() from None
        if not isinstance(image, str) or not IMAGE_REF.fullmatch(image):
            raise NoRelease()
        return image

    def trusted_text(self, path):
        """A file the dispatcher wrote: refused unless owned by the trusted uid and not writable by anyone else."""
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != self.config.trusted_uid or info.st_mode & 0o022 \
                    or info.st_size > 16 * 1024:
                raise OSError('untrusted release record')
            return os.read(fd, 16 * 1024 + 1).decode('utf-8')
        finally:
            os.close(fd)

    def beat(self):
        now = self.clock()
        if self._last_beat is not None and now - self._last_beat < 5:
            return
        self._last_beat = now
        try:
            fd = os.open(self.config.state_dir / 'heartbeat', os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
            try:
                os.write(fd, str(time.time()).encode())
            finally:
                os.close(fd)
        except OSError:
            pass

    # ---- docker -----------------------------------------------------------------------------------------------

    def docker(self, arguments, timeout=30):
        try:
            return subprocess.run([self.config.docker, *arguments], stdin=subprocess.DEVNULL, capture_output=True,
                                  timeout=timeout)
        except (OSError, subprocess.SubprocessError):
            return None

    def leftover_containers(self):
        """Names of job containers that exist, or None when Docker cannot be asked (never mistaken for "none")."""
        result = self.docker(['ps', '--all', '--quiet', '--no-trunc', '--filter', 'name=^' + CONTAINER_PREFIX])
        if result is None or result.returncode:
            return None
        return result.stdout.decode().split()

    def remove_container(self, name):
        """Gone for certain, or False; never raises."""
        try:
            self.docker(['kill', name], timeout=20)
            self.docker(['rm', '--force', name], timeout=60)
            remaining = self.docker(['ps', '--all', '--quiet', '--filter', 'name=^/?' + name + '$'])
            return remaining is not None and remaining.returncode == 0 and not remaining.stdout.strip()
        except Exception as error:
            log('removing container %s failed: %s' % (name, type(error).__name__))
            return False

    def retry_stuck(self):
        """Containers that could not be confirmed gone keep their scratch; kill and remove them again every loop."""
        for container, scratch in list(self.stuck.items()):
            if self.remove_container(container):
                self.stuck.pop(container, None)
                self.discard_scratch(scratch)

    def discard_scratch(self, scratch):
        try:
            if not remove_tree(scratch):
                log('scratch %s could not be removed' % scratch)
        except Exception as error:
            log('scratch %s could not be removed: %s' % (scratch, type(error).__name__))

    def startup_sweep(self):
        """A previous run may have died mid-job: no job container and no scratch directory survives a restart.
        Scratch is touched only once Docker confirmed that no job container exists; an unanswered question skips it."""
        containers = self.leftover_containers()
        if containers is None:
            log('startup sweep skipped: Docker did not answer')
            return
        for container in containers:
            self.remove_container(container)
        if self.leftover_containers() != []:
            log('startup sweep: job containers remain, scratch is kept')
            return
        runner_root = self.config.work_dir / self.config.runner_dir
        try:
            if not (self.mount_ok() and runner_root.is_dir() and not runner_root.is_symlink()):
                return
            entries = list(runner_root.iterdir())
        except OSError as error:
            log('startup sweep could not list the scratch directory: %s' % type(error).__name__)
            return
        for entry in entries:
            self.discard_scratch(entry)

    # ---- scratch ----------------------------------------------------------------------------------------------

    def runner_fd(self):
        """The root-only scratch parent, created on first use; refused unless it is a root-owned 0700 directory."""
        config = self.config
        wfd = open_dir(config.work_dir)
        try:
            if lstat_at(wfd, config.runner_dir) is None:
                os.mkdir(config.runner_dir, 0o700, dir_fd=wfd)
            fd = open_dir(config.runner_dir, dir_fd=wfd)
        finally:
            os.close(wfd)
        info = os.fstat(fd)
        if info.st_uid != config.trusted_uid or info.st_mode & 0o077:
            os.close(fd)
            raise JobFailure('worker_failed')
        return fd

    def mount_arguments(self, token):
        """Bind mounts of the two scratch directories; the development stack uses subpaths of its volume instead."""
        config = self.config
        base = '%s/%s' % (config.runner_dir, token)
        if config.volume is not None:
            return ['--mount', 'type=volume,source=%s,target=/job/in,readonly,volume-subpath=%s/in' % (config.volume, base),
                    '--mount', 'type=volume,source=%s,target=/job/out,volume-subpath=%s/out' % (config.volume, base)]
        return ['-v', '%s/in:/job/in:ro' % (config.work_dir / base), '-v', '%s/out:/job/out' % (config.work_dir / base)]

    # ---- discovery and processing -----------------------------------------------------------------------------

    def discover(self):
        """Names of submitted jobs without a verdict, oldest first. Only Learning-owned directories are considered."""
        config = self.config
        try:
            sfd = open_dir(config.work_dir / config.spool)
        except OSError:
            return []
        found = []
        try:
            with os.scandir(sfd) as entries:
                names = [entry.name for entry in entries]
            for entry_name in names:
                if not JOB_NAME.fullmatch(entry_name):
                    continue
                info = lstat_at(sfd, entry_name)
                if info is None or not stat.S_ISDIR(info.st_mode) or info.st_uid != config.learning_uid:
                    continue
                try:
                    jfd = open_dir(entry_name, dir_fd=sfd)
                except OSError:
                    continue
                try:
                    submitted = lstat_at(jfd, 'submitted')
                    if submitted is not None and stat.S_ISREG(submitted.st_mode) and lstat_at(jfd, 'status.json') is None \
                            and lstat_at(jfd, 'cancel') is None:
                        found.append((submitted.st_mtime_ns, entry_name))
                finally:
                    os.close(jfd)
        finally:
            os.close(sfd)
        return [name for _, name in sorted(found)]

    def run_forever(self):
        self.startup_sweep()
        pool = []
        while not self.stop.is_set():
            self.beat()
            pool = [thread for thread in pool if thread.is_alive()]
            self.retry_stuck()
            if not self.mount_ok():
                self.stop.wait(self.config.poll * 4)
                continue
            if len(pool) < self.config.parallel:
                for name in self.discover():
                    with self.lock:
                        if name in self.in_flight:
                            continue
                        self.in_flight.add(name)
                    thread = threading.Thread(target=self.guarded, args=(name,), daemon=True)
                    thread.start()
                    pool.append(thread)
                    break
            self.stop.wait(self.config.poll)
        for thread in pool:
            thread.join(60)

    def guarded(self, name):
        try:
            self.process(name)
        except Exception as error:   # one job must never stop the service
            log('job %s failed: %s' % (name, type(error).__name__))
        finally:
            with self.lock:
                self.in_flight.discard(name)

    def process(self, name):
        config = self.config
        try:
            image = self.current_image()
        except NoRelease:
            return
        sfd = open_dir(config.work_dir / config.spool)
        try:
            jfd = open_dir(name, dir_fd=sfd)
        except OSError:
            os.close(sfd)
            return
        try:
            job_info = os.fstat(jfd)
            if job_info.st_uid != config.learning_uid or lstat_at(jfd, 'status.json') is not None:
                return
            try:
                request = parse_request(read_small(jfd, 'request.json', 4096))
            except (BadRequest, OSError):
                self.publish(jfd, 2, 'invalid_manifest')
                return
            self.mark(jfd, 'claimed')
            token = uuid.uuid4().hex[:24]
            container = CONTAINER_PREFIX + token
            rfd = None
            scratch = None
            try:
                rfd = self.runner_fd()
                os.mkdir(token, 0o700, dir_fd=rfd)
                scratch = config.work_dir / config.runner_dir / token
                code = self.stage(jfd, rfd, token, request)
                if code is not None:
                    self.publish(jfd, 3, code)
                    return
                outcome = self.execute(image, container, token, sfd, name, job_info, jfd, rfd)
                self.finish(jfd, rfd, token, outcome, container)
            except JobFailure as failure:
                self.publish(jfd, 3, failure.code)
            except Exception as error:
                log('job %s: %s' % (name, type(error).__name__))
                self.publish(jfd, 3, 'worker_failed')
            finally:
                try:
                    # Scratch is only touched while no container can still be using it.
                    if scratch is not None and container not in self.stuck:
                        self.discard_scratch(scratch)
                finally:
                    if rfd is not None:
                        os.close(rfd)
        finally:
            os.close(jfd)
            os.close(sfd)

    def mark(self, jfd, name):
        try:
            fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644, dir_fd=jfd)
            os.fchmod(fd, 0o644)
            os.close(fd)
        except OSError:
            pass

    def stage(self, jfd, rfd, token, request):
        """Root-made copy of the source and a normalized request in the scratch; returns a failure code or None."""
        config = self.config
        sdfd = open_dir(token, dir_fd=rfd)
        try:
            os.mkdir('in', 0o755, dir_fd=sdfd)
            os.mkdir('out', 0o700, dir_fd=sdfd)
            ofd = open_dir('out', dir_fd=sdfd)
            try:
                os.fchown(ofd, config.worker_uid, config.worker_gid)
                os.fchmod(ofd, 0o700)
            finally:
                os.close(ofd)
            ifd = open_dir('in', dir_fd=sdfd)
            try:
                os.fchmod(ifd, 0o755)
                try:
                    source = os.open('source', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=jfd)
                except OSError:
                    return 'source_unreadable'
                try:
                    info = os.fstat(source)
                    if not stat.S_ISREG(info.st_mode) or info.st_size != request['expectedByteLength']:
                        return 'source_unreadable'
                    target = os.open('source', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644, dir_fd=ifd)
                    try:
                        os.fchmod(target, 0o644)   # the container's user (10002) must be able to read it
                        remaining = info.st_size
                        while remaining:
                            chunk = os.read(source, min(MIB, remaining))
                            if not chunk:
                                return 'source_unreadable'
                            os.write(target, chunk)
                            remaining -= len(chunk)
                    finally:
                        os.close(target)
                finally:
                    os.close(source)
                body = json.dumps(request, separators=(',', ':')).encode()
                fd = os.open('request.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644, dir_fd=ifd)
                try:
                    os.fchmod(fd, 0o644)
                    os.write(fd, body)
                finally:
                    os.close(fd)
            finally:
                os.close(ifd)
        finally:
            os.close(sdfd)
        return None

    def execute(self, image, container, token, sfd, name, job_info, jfd, rfd):
        """Run the job container to completion or interruption; whatever happens, kill and remove it before returning or
        raising. Returns (returncode, stderr, interruption, gone). A container that cannot be confirmed gone is
        remembered with its scratch and retried every loop."""
        config = self.config
        command = [config.docker, 'run', '--rm', '--name', container, '--network', 'none', '--read-only',
                   '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                   '--user', '%d:%d' % (config.worker_uid, config.worker_gid), '--pids-limit', '64',
                   '--memory', '3g', '--memory-swap', '3g', '--cpus', '2',
                   '--tmpfs', '/tmp:rw,size=64m,noexec,nosuid', '--pull', 'never', *self.mount_arguments(token),
                   image, '--manifest', '/job/in/request.json', '--source', '/job/in/source', '--output', '/job/out']
        process = None
        reader = None
        try:
            try:
                process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                           stderr=subprocess.PIPE, start_new_session=True)
            except OSError:
                raise JobFailure('worker_failed') from None
            captured = bytearray()
            overflow = threading.Event()

            def drain():
                try:
                    while chunk := process.stderr.read1(8192):
                        if len(captured) + len(chunk) > STDERR_CAP:
                            overflow.set()
                            return
                        captured.extend(chunk)
                except (OSError, ValueError):
                    overflow.set()

            reader = threading.Thread(target=drain, daemon=True)
            reader.start()
            started = self.clock()
            next_check = started + 2
            interrupted = None
            while process.poll() is None:
                self.beat()
                now = self.clock()
                if self.stop.is_set():
                    interrupted = 'runner_stopped'
                elif self.job_gone(sfd, name, job_info):
                    interrupted = 'job_removed'
                elif lstat_at(jfd, 'cancel') is not None:
                    interrupted = 'cancelled'
                elif now - started > config.job_timeout:
                    interrupted = 'worker_timeout'
                elif overflow.is_set():
                    interrupted = 'worker_log_overflow'
                elif now >= next_check:
                    next_check = now + 2
                    if self.output_too_big(rfd, token):
                        interrupted = 'disk_limit'
                if interrupted:
                    self.docker(['kill', container], timeout=20)
                    break
                try:
                    process.wait(config.poll)
                except subprocess.TimeoutExpired:
                    pass
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError):
                    pass
            process.wait()
            reader.join(5)
            result = (process.returncode, bytes(captured), interrupted)
        finally:
            if process is not None:
                if process.poll() is None:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except (ProcessLookupError, PermissionError):
                        pass
                    process.wait()
                if process.stderr is not None:
                    process.stderr.close()
            gone = self.remove_container(container)
            if not gone:
                self.stuck[container] = config.work_dir / config.runner_dir / token
        return (*result, gone)

    def job_gone(self, sfd, name, job_info):
        info = lstat_at(sfd, name)
        return info is None or (info.st_ino, info.st_dev) != (job_info.st_ino, job_info.st_dev)

    def output_too_big(self, rfd, token):
        """Bounded look at the (untrusted) output directory through directory descriptors, by lstat only. A legitimate
        result is flat, so any subdirectory counts as too much, as do too many entries or bytes."""
        try:
            tfd = open_dir(token, dir_fd=rfd)
            try:
                ofd = open_dir('out', dir_fd=tfd)
            finally:
                os.close(tfd)
        except OSError:
            return False
        total = entries = 0
        try:
            with os.scandir(ofd) as scan:
                for entry in scan:
                    entries += 1
                    if entries > self.config.out_entries_cap:
                        return True
                    info = entry.stat(follow_symlinks=False)
                    if stat.S_ISDIR(info.st_mode):
                        return True
                    total += info.st_size
                    if total > self.config.out_cap:
                        return True
        except OSError:
            return False
        finally:
            os.close(ofd)
        return False

    def finish(self, jfd, rfd, token, outcome, container):
        returncode, stderr, interrupted, gone = outcome
        if not gone:
            # Something of the job may still be alive: nothing it produced is touched, the scratch stays unread.
            self.publish(jfd, 3, 'container_cleanup_failed')
            return
        if interrupted:
            self.publish(jfd, 3, interrupted)
            return
        exit_code, code = self.outcome(returncode, stderr)
        if exit_code == 0:
            try:
                self.hand_back(jfd, rfd, token)
            except (JobFailure, OSError):
                self.publish(jfd, 3, 'output_invalid')
                return
        self.publish(jfd, exit_code, code)

    @staticmethod
    def outcome(returncode, stderr):
        if returncode == 0:
            return 0, None
        last = stderr.strip().splitlines()[-1:]
        try:
            report = json.loads(last[0]) if last else {}
        except ValueError:
            report = {}
        code = report.get('code') if isinstance(report, dict) else None
        if not isinstance(code, str) or not CODE.fullmatch(code):
            code = None
        if returncode == 2 and code is not None and report.get('status') == 'rejected':
            return 2, code
        return 3, code if returncode == 3 and report.get('status') == 'retryable' else 'worker_failed'

    def hand_back(self, jfd, rfd, token):
        """Validate `out/` as root and copy exactly the allowed files into Learning's `output/`."""
        config = self.config
        sdfd = open_dir(token, dir_fd=rfd)
        try:
            ofd = open_dir('out', dir_fd=sdfd)
        finally:
            os.close(sdfd)
        try:
            with os.scandir(ofd) as scan:
                names = sorted(entry.name for entry in scan)
            if RESULT_FILE not in names or len(names) > MAX_OUTPUT_ENTRIES \
                    or any(n != RESULT_FILE and n not in VARIANT_FILES for n in names):
                raise JobFailure('output_invalid')
            sizes = {}
            for entry in names:
                info = lstat_at(ofd, entry)
                cap = MAX_RESULT_BYTES if entry == RESULT_FILE else MAX_VARIANT_BYTES
                if info is None or not stat.S_ISREG(info.st_mode) or info.st_size > cap:
                    raise JobFailure('output_invalid')
                sizes[entry] = info.st_size
            if sum(size for entry, size in sizes.items() if entry != RESULT_FILE) > MAX_TOTAL_OUTPUT_BYTES:
                raise JobFailure('output_invalid')
            target = open_dir('output', dir_fd=jfd)
            try:
                info = os.fstat(target)
                with os.scandir(target) as scan:
                    occupied = any(True for _ in scan)
                if info.st_uid != config.learning_uid or occupied:
                    raise JobFailure('output_invalid')
                for entry in names:
                    self.copy_file(ofd, entry, target, sizes[entry])
            finally:
                os.close(target)
        finally:
            os.close(ofd)

    def copy_file(self, source_dir, name, target_dir, size):
        config = self.config
        source = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=source_dir)
        try:
            info = os.fstat(source)
            if not stat.S_ISREG(info.st_mode) or info.st_size != size:
                raise JobFailure('output_invalid')
            target = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=target_dir)
            try:
                remaining = size
                while remaining:
                    chunk = os.read(source, min(MIB, remaining))
                    if not chunk:
                        raise JobFailure('output_invalid')
                    os.write(target, chunk)
                    remaining -= len(chunk)
                if os.read(source, 1):
                    raise JobFailure('output_invalid')   # grew while being copied: the container is gone, so this is a lie
                os.fchown(target, config.learning_uid, config.learning_gid)
            finally:
                os.close(target)
        finally:
            os.close(source)

    def publish(self, jfd, exit_code, code):
        """The verdict: written last, atomically, by root. Learning ignores a status that root did not write. The
        temporary file has a random name and is created exclusively without following links, so nothing pre-planted
        in the job directory (a FIFO, a link, a hard link) can be written through or block the runner."""
        document = json.dumps({'formatVersion': 1, 'exitCode': exit_code, 'code': code}, separators=(',', ':')).encode()
        for _ in range(8):
            temporary = '.status-%s.tmp' % uuid.uuid4().hex
            try:
                fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC,
                             0o644, dir_fd=jfd)
            except FileExistsError:
                continue
            except OSError:
                return
            try:
                try:
                    info = os.fstat(fd)
                    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
                        raise OSError('unexpected file')
                    os.fchmod(fd, 0o644)   # root-owned, readable by Learning
                    os.write(fd, document)
                    os.fsync(fd)
                finally:
                    os.close(fd)
                os.rename(temporary, 'status.json', src_dir_fd=jfd, dst_dir_fd=jfd)
            except OSError:
                try:
                    os.unlink(temporary, dir_fd=jfd)
                except OSError:
                    pass
            return


def build_config(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-dir', type=Path)
    parser.add_argument('--state-dir', type=Path)
    parser.add_argument('--docker')
    parser.add_argument('--parallel', type=int, default=1)
    parser.add_argument('--job-timeout', type=int, default=30 * 60)
    parser.add_argument('--image', help='development only: fixed worker image instead of the recorded release')
    parser.add_argument('--volume', help='development only: Docker volume that holds the work directory')
    parser.add_argument('--trusted-uid', type=int, default=0)
    args = parser.parse_args(argv)
    options = {'parallel': max(1, min(args.parallel, 4)), 'job_timeout': max(60, min(args.job_timeout, 30 * 60)),
               'trusted_uid': args.trusted_uid}
    for name in ('work_dir', 'state_dir', 'docker', 'image', 'volume'):
        value = getattr(args, name)
        if value is not None:
            options[name] = value
    if args.volume is not None and not VOLUME_NAME.fullmatch(args.volume):
        parser.error('invalid volume name')
    return Config(**options)


def main(argv=None):
    if os.geteuid() != 0:
        log('the media runner must run as root')
        return 1
    config = build_config(sys.argv[1:] if argv is None else argv)
    config.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.umask(0o022)   # files handed to the container and to Learning must be readable; private things set their mode
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    Runner(config, stop).run_forever()
    return 0


if __name__ == '__main__':
    sys.exit(main())
