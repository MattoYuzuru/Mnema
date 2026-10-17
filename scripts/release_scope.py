#!/usr/bin/env python3
"""Decide whether a push to main changes what runs in production (build, approve and deploy)."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Callable, Iterable

SHA = re.compile(r"[0-9a-f]{40}")
ENVIRONMENT = "prod"
RUNTIME_PREFIXES = ("backend/", "frontend/", "contracts/", "deploy/production/", "security/")
# Inputs that the five release images and the host bundle provably never contain. Anything else under a runtime
# prefix stays runtime, so an unknown kind of file still releases. Evidence per pattern:
# - backend/Dockerfile copies only the Gradle build and services/, then runs the two bootJar tasks (no test sources);
# - backend/media-worker/Dockerfile copies only the mnema_media_worker package;
# - frontend ships the Angular build output; tsconfig.app.json compiles from src/main.ts, never *.spec.ts;
# - Markdown is documentation unless it is a packaged resource (backend src/main, the media-worker package,
#   frontend src/); angular.json copies src/assets verbatim, so nothing under it is excluded.
NON_RUNTIME = tuple(re.compile(pattern) for pattern in (
    r"backend/services/[^/]+/src/test/.+",
    r"backend/test-support/.+",
    r"backend/(?:test-durations\.tsv|test-opt-ins\.json|coverage-baseline\.json)",
    r"backend/scripts/.+",
    r"backend/media-worker/tests/.+",
    r"(?!frontend/src/assets/)frontend/src/(?:.+/)?[^/]+\.spec\.ts",
    r"(?!.*/src/main/)(?!frontend/src/)(?!backend/media-worker/mnema_media_worker/)"
    r"(?:backend|frontend|deploy/production)/.+\.md",
))
RUNTIME_FILES = frozenset({
    ".github/workflows/deploy.yaml",
    "scripts/deploy-vps.sh",
    "scripts/release_scope.py",
    "scripts/render_vps_candidate.py",
    "scripts/verify_release_security_evidence.py",
})


def is_runtime_path(path: str) -> bool:
    if path in RUNTIME_FILES:
        return True
    if any(pattern.fullmatch(path) for pattern in NON_RUNTIME):
        return False
    return path.startswith(RUNTIME_PREFIXES)


def runtime_changes(paths: Iterable[str]) -> list[str]:
    return sorted(path for path in paths if is_runtime_path(path))


RUN_URL = re.compile(r"/actions/runs/(\d+)(?:/|$)")
MAIN_CI_PATH = ".github/workflows/deploy.yaml"


def produced_by_main_ci(status: dict, gh: Callable[[str], object]) -> bool:
    """True only if the status came from a Main CI run (the ops workflow also deploys to prod)."""
    for key in ("log_url", "target_url"):
        match = RUN_URL.search(str(status.get(key) or ""))
        if match:
            run = gh(f"actions/runs/{int(match.group(1))}")
            path = run.get("path") if isinstance(run, dict) else None
            return isinstance(path, str) and path.split("@", 1)[0] == MAIN_CI_PATH
    return False


def latest_successful_sha(gh: Callable[[str], object]) -> str | None:
    """SHA of the newest Main CI deployment of the prod Environment whose latest status is success."""
    deployments = gh(f"deployments?environment={ENVIRONMENT}&per_page=20")
    for deployment in deployments if isinstance(deployments, list) else []:
        statuses = gh(f"deployments/{int(deployment['id'])}/statuses?per_page=1")
        if not (isinstance(statuses, list) and statuses and statuses[0].get("state") == "success"):
            continue
        if not produced_by_main_ci(statuses[0], gh):
            continue
        sha = deployment.get("sha")
        return sha if isinstance(sha, str) and SHA.fullmatch(sha) else None
    return None


def decide(event: str, sha: str, gh: Callable[[str], object], git: Callable[..., tuple[int, str]]) -> tuple[bool, str]:
    if event == "workflow_dispatch":
        return True, "manual dispatch releases the current main"
    if event != "push":
        return True, f"unrecognised event {event}; releasing conservatively"
    deployed = latest_successful_sha(gh)
    if deployed is None:
        return True, "no successful prod deployment is recorded"
    if deployed == sha:
        return False, "this commit is already deployed"
    exists, _ = git("cat-file", "-e", f"{deployed}^{{commit}}")
    ancestor, _ = git("merge-base", "--is-ancestor", deployed, sha)
    if exists != 0 or ancestor != 0:
        return True, "deployed commit is unknown or not an ancestor"
    # --no-renames lists both sides, so moving a file out of a runtime path still counts.
    code, output = git("diff", "--name-only", "--no-renames", deployed, sha)
    if code != 0:
        return True, "diff against the deployed commit failed"
    changed = runtime_changes(output.splitlines())
    if changed:
        shown = ", ".join(changed[:5]) + (", ..." if len(changed) > 5 else "")
        return True, f"{len(changed)} runtime path(s) changed since the deployed commit {deployed[:12]}: {shown}"
    return False, f"no runtime path changed since the deployed commit {deployed[:12]}"


def _gh(repository: str) -> Callable[[str], object]:
    def call(path: str) -> object:
        result = subprocess.run(["gh", "api", f"repos/{repository}/{path}"], capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise RuntimeError("GitHub deployment lookup failed")
        return json.loads(result.stdout)
    return call


def _git(*args: str) -> tuple[int, str]:
    result = subprocess.run(["git", *args], capture_output=True, text=True, timeout=120)
    return result.returncode, result.stdout


def changed_runtime(base: str, head: str, git: Callable[..., tuple[int, str]]) -> tuple[bool, str]:
    """Pull-request form: does base...head touch a release-image input? Unknown history means yes."""
    code, output = git("diff", "--name-only", "--no-renames", f"{base}...{head}")
    if code != 0:
        return True, "diff against the pull-request base failed"
    changed = runtime_changes(output.splitlines())
    if changed:
        return True, f"{len(changed)} runtime path(s): " + ", ".join(changed[:5]) + (", ..." if len(changed) > 5 else "")
    return False, "no release-image input changed; the image build and scan are not needed"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--changed", nargs=2, metavar=("BASE", "HEAD"),
                        help="pull-request mode: report runtime=true|false for BASE...HEAD instead of deciding a release")
    parser.add_argument("--event", default=os.environ.get("GITHUB_EVENT_NAME", ""))
    parser.add_argument("--sha", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    args = parser.parse_args()
    if args.changed:
        try:
            runtime, reason = changed_runtime(*args.changed, _git)
        except subprocess.SubprocessError:
            runtime, reason = True, "diff against the pull-request base failed"
        print(f"runtime={str(runtime).lower()} ({reason})")
        for variable, text in (("GITHUB_OUTPUT", f"runtime={str(runtime).lower()}\n"),
                               ("GITHUB_STEP_SUMMARY", f"### Release images\n\nRuntime inputs changed: "
                                                       f"**{str(runtime).lower()}** - {reason}.\n")):
            if os.environ.get(variable):
                with Path(os.environ[variable]).open("a", encoding="utf-8") as handle:
                    handle.write(text)
        return 0
    if not SHA.fullmatch(args.sha) or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        print("::error::release scope needs a full commit SHA and repository", file=sys.stderr)
        return 2
    try:
        deploy, reason = decide(args.event, args.sha, _gh(args.repository), _git)
    except (RuntimeError, ValueError, KeyError, subprocess.SubprocessError):
        deploy, reason = True, "deployment history unavailable; releasing conservatively"
    print(f"deploy={str(deploy).lower()} ({reason})")
    for variable, text in (("GITHUB_OUTPUT", f"deploy={str(deploy).lower()}\n"),
                           ("GITHUB_STEP_SUMMARY", f"### Release scope\n\nDeploy: **{str(deploy).lower()}** - {reason}.\n")):
        if os.environ.get(variable):
            with Path(os.environ[variable]).open("a", encoding="utf-8") as handle:
                handle.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
