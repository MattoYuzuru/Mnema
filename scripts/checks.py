#!/usr/bin/env python3
"""One catalogue of Mnema quality checks: what runs locally, what runs hosted, and why.

  plan    explain the checks for a candidate (full by default; --affected for fast local feedback)
  run     run them on a committed candidate in its own detached validation worktree
  verify  confirm that a recorded run still belongs to an unchanged candidate
  clean   remove a candidate's validation worktree

The final gate before a push is always `run` without --affected on the exact commit being pushed. Edits in the
working checkout cannot change the candidate, and a candidate that changed after its run is rejected, never
re-labelled. Hosted CI keeps its parallel jobs; tests/test_verify_checks.py proves that each job line belongs to a
check here, so local and hosted lists cannot drift apart.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHA = re.compile(r"[0-9a-f]{40}")
PR_WORKFLOW = ".github/workflows/pull-request.yaml"
MAIN_WORKFLOW = ".github/workflows/deploy.yaml"
BOTH = (PR_WORKFLOW, MAIN_WORKFLOW)
SCRIPT_TESTS = ("test_ai_ops_metrics_snapshot", "test_ai_worker_topology", "test_local_full_stack_media",
                "test_ranker_evaluation", "test_require_no_skips")

CODEC_MATRIX = ("docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges "
                "--memory 3g --cpus 2 --tmpfs /tmp:rw,size=512m,mode=1777 "
                '--volume "$PWD/backend/media-worker/tests:/opt/mnema-media-worker/tests:ro" '
                '--volume "$PWD/scripts/require_no_skips.py:/opt/require_no_skips.py:ro" --entrypoint python3 '
                "mnema-media-worker:verified /opt/require_no_skips.py tests.test_worker")
CANCELLATION = "MNEMA_RUN_CANCELLATION_INTEGRATION=1 python3 -m unittest discover -s scripts/learning-security/tests -v"


@dataclass(frozen=True)
class Check:
    id: str
    guarantee: str
    inputs: tuple[str, ...]                     # regexes over repository paths; a match selects the check
    local: tuple[tuple[str, tuple[str, ...]], ...] = ()   # (cwd, argv) run in order; empty = hosted only
    hosted: tuple[tuple[str, tuple[str, ...]], ...] = ()  # (job, exact command lines of its run steps)
    workflows: tuple[str, ...] = BOTH
    hosted_only_reason: str = ""
    java: bool = False
    node: bool = False
    docker: bool = False
    reports: tuple[str, ...] = ()               # JUnit XML globs, relative to the first command's cwd
    timeout_minutes: int = 20
    needs: tuple[str, ...] = field(default=())  # checks whose outputs this one reads (same worktree)


def py(*args: str) -> tuple[str, tuple[str, ...]]:
    return ".", ("python3", *args)


CHECKS: tuple[Check, ...] = (
    Check(
        "docs", "Markdown links, anchors and document statuses",
        (r".*\.md", r"docs/.*", r"scripts/verify_docs\.py"),
        local=(py("scripts/verify_docs.py"),),
        hosted=(("frontend-checks", ("python3 scripts/verify_docs.py",)),), timeout_minutes=5),
    Check(
        "automation-policy", "Action pins, security automation, artifact policy, image pins and their tests",
        (r"\.github/.*", r"scripts/.*", r"security/.*", r".*Dockerfile", r"deploy/.*", r"backend/gradle/.*",
         r"frontend/package(-lock)?\.json"),
        local=(py("scripts/verify_github_actions_pins.py"), py("scripts/verify_security_automation_policy.py"),
               py("scripts/verify_artifact_security_policy.py"), py("scripts/verify_production_image_pins.py"),
               py("-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_verify_*.py"),
               py("-m", "unittest", *(f"scripts.tests.{name}" for name in SCRIPT_TESTS))),
        hosted=(("frontend-checks", ("python3 scripts/verify_github_actions_pins.py", "python3 scripts/verify_security_automation_policy.py",
                "python3 scripts/verify_artifact_security_policy.py", "python3 scripts/verify_production_image_pins.py",
                "python3 -m unittest discover -s scripts/tests -p 'test_verify_*.py' -v",
                "python3 -m unittest " + " ".join(f"scripts.tests.{name}" for name in SCRIPT_TESTS) + " -v")),),
        timeout_minutes=10),
    Check(
        "frontend-static", "Exact dependency install, lint and the production build without the styleguide",
        (r"frontend/.*", r"contracts/.*"),
        local=(("frontend", ("npm", "ci")), ("frontend", ("npm", "run", "lint")), ("frontend", ("npm", "run", "build")),
               ("frontend", ("node", "scripts/verify-no-styleguide.mjs"))),
        hosted=(("frontend-checks", ("npm ci", "npm run lint", "npm run build", "node scripts/verify-no-styleguide.mjs")),),
        node=True, timeout_minutes=20),
    Check(
        "delivery-contracts", "Frontend release, browser headers, link previews, release security, delivery boundary",
        (r"frontend/.*", r"deploy/.*", r"scripts/.*", r"security/.*", r"\.github/.*", r"contracts/.*"),
        local=(("scripts", ("./test-frontend-release-contract.sh",)), ("scripts", ("./test-browser-security-headers.sh",)),
               ("scripts", ("./test-link-previews.sh",)), ("scripts", ("./test-release-security-contract.sh",)),
               ("scripts", ("./test-deployment-contract.sh",)),
               py("-m", "unittest", "discover", "-s", "scripts/smoke/tests")),
        hosted=(("frontend-checks", ("./scripts/test-frontend-release-contract.sh", "./scripts/test-browser-security-headers.sh",
                "./scripts/test-link-previews.sh", "./scripts/test-release-security-contract.sh",
                "./scripts/test-deployment-contract.sh", "python3 -m unittest discover -s scripts/smoke/tests -v")),),
        docker=True, needs=("frontend-static",), timeout_minutes=20),
    Check(
        "frontend-tests", "Vitest specs with the coverage thresholds",
        (r"frontend/.*", r"contracts/.*"),
        local=(("frontend", ("npm", "run", "test")),),
        hosted=(("frontend-tests", ("npm ci", "npm run test")),),
        node=True, needs=("frontend-static",), timeout_minutes=25),
    Check(
        "backend-runtime", "VPS runtime/restore contract, media runner, worker image and codec matrix",
        (r"backend/media-worker/.*", r"backend/runtime/.*", r"deploy/.*", r"scripts/.*"),
        local=(("deploy/production", ("docker", "build", "--tag", "mnema-vps-postgres-fixture:verified", ".")),
               py("-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_vps_*.py"),
               py("scripts/require_no_skips.py", "--path", "scripts/tests", "test_media_runner",
                  "test_verify_production_dispatcher"),
               ("backend/media-worker", ("docker", "build", "--tag", "mnema-media-worker:verified", ".")),
               (".", ("sh", "-c", CODEC_MATRIX))),
        hosted=(("backend-runtime", ("docker build --tag mnema-vps-postgres-fixture:verified deploy/production",
                "python3 -m unittest discover -s scripts/tests -p 'test_vps_*.py' -v",
                "python3 scripts/require_no_skips.py --path scripts/tests test_media_runner test_verify_production_dispatcher",
                "docker build --tag mnema-media-worker:verified backend/media-worker", CODEC_MATRIX)),),
        java=True, docker=True, timeout_minutes=25),
    Check(
        "backend-security", "Identity to Learning black-box security and cancellation on real boot jars",
        (r"backend/.*", r"contracts/.*", r"scripts/learning-security/.*"),
        local=((".", ("docker", "pull", "postgres:18")),
               (".", ("./backend/gradlew", "-p", "backend", ":services:identity-account:bootJar",
                      ":services:learning:bootJar")),
               (".", ("sh", "-c", CANCELLATION)),
               py("scripts/learning-security/run.py"), py("scripts/learning-security/verify_cancellation.py")),
        hosted=(("backend-security", ("docker pull postgres:18",
                "./backend/gradlew -p backend :services:identity-account:bootJar :services:learning:bootJar",
                CANCELLATION, "python3 scripts/learning-security/run.py", "python3 scripts/learning-security/verify_cancellation.py")),),
        java=True, docker=True, timeout_minutes=25),
    Check(
        "backend-quality", "Compilation (-Werror), every discovered test, inventory and per-service coverage floors",
        (r"backend/.*", r"contracts/.*", r"scripts/backend_test_evidence\.py"),
        local=(("backend", ("./gradlew", "clean", "quality")),),
        hosted=(("backend-tests", ("./gradlew test --parallel --max-workers=2 -PtestShard=${{ matrix.shard }}/8 -PtestForks=2",
                "python3 scripts/backend_test_evidence.py pack --index ${{ matrix.shard }}")),
                ("backend-quality", ("./gradlew testInventory --parallel --max-workers=2", "python3 scripts/backend_test_evidence.py merge",
                "./gradlew --parallel --max-workers=2 compileJava jacocoTestReport jacocoRootReport "
                "coverageBaselineCheck -PcoverageShards=8 -x test")),),
        java=True, docker=True, reports=("services/*/build/test-results/test/TEST-*.xml",), timeout_minutes=45),
    Check(
        "release-images", "Each release Dockerfile builds from its real context; no unexcepted HIGH/CRITICAL finding",
        (r"backend/.*", r"frontend/.*", r"deploy/production/.*", r"security/.*", r"contracts/.*",
         r"scripts/verify_release_security_evidence\.py"),
        hosted=(("release-images", ('python3 scripts/verify_release_security_evidence.py preflight --image "$IMAGE" '
                                    '--service "$SERVICE" --report "$RUNNER_TEMP/preflight-trivy.json" '
                                    "--exceptions security/release-image-exceptions.json "
                                    "--trivy-ignore security/trivy-release-ignore",)),),
        workflows=(PR_WORKFLOW,),
        hosted_only_reason="needs a fresh Trivy database; Main CI build-and-push repeats it on the exact release digests",
        timeout_minutes=20),
)
BY_ID = {check.id: check for check in CHECKS}
QUALITY_JOBS = ("backend-tests", "backend-security", "backend-runtime", "backend-quality", "frontend-checks",
                "frontend-tests", "frontend-quality", "release-images")
AGGREGATE = "python3 - <<'PYCODE'"          # the required aggregates' own result check, owned by no catalogue entry


def workflow_runs(path: Path) -> dict[str, list[str]]:
    """Command lines of every job's run steps; continuations joined, comments and heredoc bodies dropped."""
    jobs: dict[str, list[str]] = {}
    job, block, block_indent, in_jobs = None, None, 0, False

    def commit(lines: list[str]) -> None:
        joined, heredoc = "", None
        for line in lines:
            if heredoc:
                heredoc = None if line == heredoc else heredoc
                continue
            if line.startswith("#"):
                continue
            joined = f"{joined} {line[:-1].strip()}".strip() if line.endswith("\\") else f"{joined} {line}".strip()
            if line.endswith("\\"):
                continue
            if (marker := re.search(r"<<'([A-Z]+)'$", joined)):
                heredoc = marker.group(1)
            jobs[job].append(joined)
            joined = ""

    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw == "jobs:":
            in_jobs = True
            continue
        if not in_jobs:
            continue
        if block is not None:
            if not raw.strip():
                continue
            if len(raw) - len(raw.lstrip()) > block_indent:
                block.append(raw.strip())
                continue
            commit(block)
            block = None
        if (match := re.fullmatch(r"  ([A-Za-z0-9_-]+):", raw)):
            job = match.group(1)
            jobs[job] = []
        elif job and (match := re.match(r"^(\s+)(?:- )?run:\s*(.*)$", raw)):
            if match.group(2) in ("|", "|-"):
                block, block_indent = [], len(match.group(1))
            else:
                jobs[job].append(match.group(2).strip())
    if block is not None:
        commit(block)
    return jobs


def drift() -> list[str]:
    """Differences between this catalogue and the hosted quality jobs; empty means they agree."""
    problems = []
    claimed: dict[str, set[str]] = {}
    for check in CHECKS:
        for job, lines in check.hosted:
            claimed.setdefault(job, set()).update(lines)
    for workflow in BOTH:
        runs = workflow_runs(ROOT / workflow)
        expected = {job for check in CHECKS if workflow in check.workflows for job, _ in check.hosted}
        expected |= {"backend-quality", "frontend-quality"}
        present = {job for job in runs if job in QUALITY_JOBS}
        if present != expected:
            problems.append(f"{workflow}: quality jobs {sorted(present)} differ from the catalogue {sorted(expected)}")
        for check in CHECKS:
            if workflow not in check.workflows:
                continue
            for job, lines in check.hosted:
                for line in lines:
                    if line not in runs.get(job, []):
                        problems.append(f"{workflow}: {job} lacks `{line}` ({check.id})")
        for job in present:
            for line in runs[job]:
                if line not in claimed.get(job, set()) and line != AGGREGATE:
                    problems.append(f"{workflow}: {job} runs `{line}`, which no check owns")
    return problems


def git(*args: str, cwd: Path | None = None, check: bool = True) -> str:
    result = subprocess.run(["git", *args], cwd=cwd or ROOT, capture_output=True, text=True, timeout=120)
    if check and result.returncode:
        raise SystemExit(f"git {' '.join(args[:2])} failed: {result.stderr.strip()[:300]}")
    return result.stdout


def resolve(rev: str) -> str:
    sha = git("rev-parse", "--verify", f"{rev}^{{commit}}").strip()
    if not SHA.fullmatch(sha):
        raise SystemExit(f"cannot resolve {rev} to a commit")
    return sha


def changed_paths(base: str, head: str) -> list[str] | None:
    """Both sides of renames, deletions included. None when the history cannot answer."""
    merge_base = subprocess.run(["git", "merge-base", base, head], cwd=ROOT, capture_output=True, text=True, timeout=120)
    if merge_base.returncode:
        return None
    output = git("diff", "--name-only", "--no-renames", merge_base.stdout.strip(), head)
    return sorted({line for line in output.splitlines() if line})


def plan(paths: list[str] | None, affected: bool) -> tuple[list[Check], dict[str, list[str]], str]:
    """Selected checks in run order, matching paths per check and the reason for the selection."""
    if not affected:
        return list(CHECKS), {}, "full gate (default for a final check)"
    if paths is None:
        return list(CHECKS), {}, "change set unknown (no merge base): full gate"
    if not paths:
        return list(CHECKS), {}, "no changed path found: full gate"
    paths = sorted(set(paths))
    patterns = {check.id: [re.compile(pattern) for pattern in check.inputs] for check in CHECKS}
    matches = {check.id: [path for path in paths if any(p.fullmatch(path) for p in patterns[check.id])]
               for check in CHECKS}
    unknown = [path for path in paths if not any(path in matched for matched in matches.values())]
    if unknown:
        return list(CHECKS), {}, f"{len(unknown)} path(s) owned by no check ({unknown[0]}...): full gate"
    selected = {check_id for check_id, matched in matches.items() if matched}
    for check in CHECKS:                      # a selected check pulls in the checks whose output it reads
        if check.id in selected:
            selected.update(check.needs)
    ordered = [check for check in CHECKS if check.id in selected]
    return ordered, {check.id: matches[check.id] for check in ordered}, "affected checks only (not a final gate)"


def plan_digest(checks: list[Check]) -> str:
    payload = [[check.id, [list(command) for command in check.local]] for check in checks]
    return hashlib.sha256(json.dumps(payload).encode()).hexdigest()


def describe(checks: list[Check], matches: dict[str, list[str]], reason: str, candidate: str) -> str:
    lines = [f"candidate {candidate[:12]}: {reason}"]
    for check in checks:
        where = "local+hosted" if check.local else f"hosted only ({check.hosted_only_reason})"
        hosted = f"{'+'.join(job for job, _ in check.hosted)} in {', '.join(Path(w).name for w in check.workflows)}"
        why = f"; inputs: {', '.join(matches[check.id][:3])}" if matches.get(check.id) else ""
        lines.append(f"- {check.id}: {check.guarantee} [{where}; hosted: {hosted}]{why}")
    return "\n".join(lines)


def state_dir() -> Path:
    configured = os.environ.get("MNEMA_CANDIDATE_DIR")
    return Path(configured).expanduser() if configured else Path.home() / ".local/state/agent-dev/mnema-candidates"


def candidate_paths(sha: str) -> tuple[Path, Path]:
    base = state_dir()
    return base / sha[:12], base / f"{sha[:12]}.json"


def candidate_state(worktree: Path) -> tuple[str, bool]:
    head = git("rev-parse", "HEAD", cwd=worktree).strip()
    dirty = bool(git("status", "--porcelain=v1", "--untracked-files=normal", cwd=worktree).strip())
    return head, dirty


def prepare(sha: str) -> Path:
    worktree, _ = candidate_paths(sha)
    if worktree.exists():
        head, dirty = candidate_state(worktree)
        if head != sha or dirty:
            raise SystemExit(f"validation worktree {worktree} no longer matches {sha[:12]}; run `clean` first")
        return worktree
    worktree.parent.mkdir(parents=True, exist_ok=True)
    git("worktree", "add", "--detach", str(worktree), sha)
    return worktree


def runner_command(check: Check, cwd: Path, argv: tuple[str, ...], first: bool) -> list[str]:
    helper = os.environ.get("MNEMA_AGENT_DEV", shutil.which("agent-dev") or "")
    if not helper or os.environ.get("MNEMA_CHECK_RUNNER") == "direct":
        return list(argv)
    command = [helper, "run", "--cwd", str(cwd), "--resource", "heavy", "--timeout", str(check.timeout_minutes * 60)]
    if check.java:
        command += ["--java", "25"]
    if check.node:
        command += ["--node", "24"]
    if check.docker:
        command.append("--docker")
    for pattern in check.reports if first else ():
        command += ["--reports", pattern]
    return [*command, "--", *argv]


def execute(sha: str, checks: list[Check], worktree: Path) -> dict:
    record = {"version": 1, "candidate": sha, "tree": git("rev-parse", f"{sha}^{{tree}}").strip(),
              "plan": [check.id for check in checks], "planDigest": plan_digest(checks), "worktree": str(worktree),
              "complete": False, "checks": []}
    for check in checks:
        if not check.local:
            record["checks"].append({"id": check.id, "status": "hosted-only", "reason": check.hosted_only_reason})
            continue
        entry = {"id": check.id, "status": "passed", "steps": []}
        record["checks"].append(entry)
        for index, (relative, argv) in enumerate(check.local):
            cwd = worktree / relative
            command = runner_command(check, cwd, argv, index == 0)
            started = time.monotonic()
            print(f"[{check.id}] {' '.join(argv)}", flush=True)
            try:
                result = subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                                        timeout=check.timeout_minutes * 60 + 120)
            except subprocess.TimeoutExpired:
                result = subprocess.CompletedProcess(command, 124, "", "timed out")
            step = {"command": list(argv), "cwd": relative, "exit": result.returncode,
                    "seconds": round(time.monotonic() - started, 1)}
            receipt = receipt_of(result.stdout)
            if receipt:
                step["receipt"] = receipt
            entry["steps"].append(step)
            if result.returncode:
                entry["status"] = "failed"
                tail = (result.stdout + result.stderr).strip().splitlines()[-15:]
                print("\n".join(tail), file=sys.stderr)
                return record
    head, dirty = candidate_state(worktree)
    record["complete"] = head == sha and not dirty
    if not record["complete"]:
        record["error"] = "the validation worktree changed during the run"
    return record


def receipt_of(output: str) -> str | None:
    for line in reversed(output.strip().splitlines()):
        if line.startswith("{"):
            try:
                value = json.loads(line).get("receipt")
            except (ValueError, AttributeError):
                return None
            return value if isinstance(value, str) else None
    return None


def verify(sha: str) -> list[str]:
    """Problems that make the recorded run unusable for this candidate; empty means usable."""
    worktree, summary = candidate_paths(sha)
    if not summary.exists():
        return [f"no recorded run for {sha[:12]}"]
    record = json.loads(summary.read_text())
    problems = []
    if record.get("candidate") != sha or record.get("tree") != git("rev-parse", f"{sha}^{{tree}}").strip():
        problems.append("the record belongs to another candidate")
    if record.get("planDigest") != plan_digest(list(CHECKS)) or record.get("plan") != [c.id for c in CHECKS]:
        problems.append("the record is not a run of the current full plan")
    if not record.get("complete"):
        problems.append(record.get("error") or "the run did not complete")
    if not worktree.exists():
        problems.append("the validation worktree is gone, so its receipts cannot be re-verified")
    else:
        head, dirty = candidate_state(worktree)
        if head != sha or dirty:
            problems.append("the validation worktree no longer matches the candidate")
    helper = os.environ.get("MNEMA_AGENT_DEV", shutil.which("agent-dev") or "")
    for check in record.get("checks", []):
        if check.get("status") == "hosted-only":
            continue
        if check.get("status") != "passed":
            problems.append(f"{check.get('id')} did not pass")
        for step in check.get("steps", []):
            receipt = step.get("receipt")
            if receipt and helper and worktree.exists():
                result = subprocess.run([helper, "verify", receipt, "--cwd", str(worktree / step["cwd"])],
                                        capture_output=True, text=True)
                if result.returncode:
                    problems.append(f"{check.get('id')}: receipt for `{' '.join(step['command'])}` is stale")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("plan", "run"):
        sub = commands.add_parser(name)
        sub.add_argument("--candidate", default="HEAD", help="commit to check (default HEAD)")
        sub.add_argument("--affected", action="store_true", help="only checks owning paths changed since --base")
        sub.add_argument("--base", default="origin/main")
        sub.add_argument("--json", action="store_true")
    for name in ("verify", "clean"):
        commands.add_parser(name).add_argument("--candidate", default="HEAD")
    commands.add_parser("drift", help="compare this catalogue with the hosted quality jobs")
    args = parser.parse_args()
    if args.command == "drift":
        problems = drift()
        print("\n".join(problems) if problems else "catalogue and hosted quality jobs agree")
        return 1 if problems else 0
    sha = resolve(args.candidate)

    if args.command == "clean":
        worktree, summary = candidate_paths(sha)
        if worktree.exists():
            git("worktree", "remove", "--force", str(worktree))
        summary.unlink(missing_ok=True)
        print(f"removed validation worktree for {sha[:12]}")
        return 0
    if args.command == "verify":
        problems = verify(sha)
        print(f"candidate {sha[:12]}: " + ("usable full-gate evidence" if not problems else "; ".join(problems)))
        return 0 if not problems else 3

    paths = changed_paths(args.base, sha) if args.affected else None
    checks, matches, reason = plan(paths, args.affected)
    if args.command == "plan":
        if args.json:
            print(json.dumps({"candidate": sha, "reason": reason, "checks": [c.id for c in checks],
                              "matches": matches, "planDigest": plan_digest(checks)}, indent=2))
        else:
            print(describe(checks, matches, reason, sha))
        return 0

    if git("status", "--porcelain=v1", "--untracked-files=normal").strip() and args.candidate == "HEAD":
        print("note: uncommitted changes in this checkout are not part of the candidate", file=sys.stderr)
    print(describe(checks, matches, reason, sha), flush=True)
    worktree = prepare(sha)
    record = execute(sha, checks, worktree)
    _, summary = candidate_paths(sha)
    summary.write_text(json.dumps(record, indent=2) + "\n")
    passed = record["complete"] and all(c["status"] in ("passed", "hosted-only") for c in record["checks"])
    print(f"{'PASSED' if passed else 'FAILED'}: {sha[:12]} ({len(record['checks'])} checks); record {summary}")
    if passed and not args.affected:
        print(f"before pushing, run: python3 scripts/checks.py verify --candidate {sha}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
