"""The check catalogue: hosted jobs cannot drift from it, plans are conservative, candidates stay stable."""
from __future__ import annotations

import fnmatch
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("checks", ROOT / "scripts/checks.py")
CHECKS = importlib.util.module_from_spec(SPEC)
sys.modules["checks"] = CHECKS
SPEC.loader.exec_module(CHECKS)


class CatalogueTest(unittest.TestCase):
    def test_hosted_quality_jobs_match_the_catalogue_in_both_workflows(self):
        self.assertEqual(CHECKS.drift(), [])

    def test_a_job_line_without_an_owner_and_a_missing_line_are_both_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            for workflow in CHECKS.BOTH:
                target = Path(tmp) / workflow
                target.parent.mkdir(parents=True, exist_ok=True)
                text = (ROOT / workflow).read_text(encoding="utf-8")
                if workflow == CHECKS.PR_WORKFLOW:
                    text = text.replace("        run: npm run lint\n", "        run: npm run lint -- --quiet\n", 1)
                target.write_text(text, encoding="utf-8")
            with mock.patch.object(CHECKS, "ROOT", Path(tmp)):
                problems = CHECKS.drift()
        self.assertIn("pull-request.yaml: frontend-checks lacks `npm run lint` (frontend-static)",
                      "\n".join(problems))
        self.assertIn("runs `npm run lint -- --quiet`, which no check owns", "\n".join(problems))

    def test_every_script_suite_is_run_by_some_hosted_job(self):
        hosted = " ".join(line for check in CHECKS.CHECKS for _, lines in check.hosted for line in lines)
        hosted += (ROOT / "scripts/test-frontend-release-contract.sh").read_text(encoding="utf-8")
        for path in sorted((ROOT / "scripts/tests").glob("test_*.py")):
            with self.subTest(suite=path.name):
                self.assertTrue(fnmatch.fnmatch(path.name, "test_verify_*.py") or fnmatch.fnmatch(path.name, "test_vps_*.py")
                                or path.stem in hosted, f"{path.name} is run by no hosted job")

    def test_local_commands_exist_and_every_dependency_is_a_check(self):
        for check in CHECKS.CHECKS:
            with self.subTest(check=check.id):
                self.assertTrue(check.local or check.hosted_only_reason)
                self.assertTrue(set(check.needs) <= set(CHECKS.BY_ID))
                for cwd, argv in check.local:
                    self.assertTrue((ROOT / cwd).is_dir(), cwd)
                    if argv[0].startswith("./") or argv[0] in ("python3",) and argv[1].endswith(".py"):
                        script = ROOT / cwd / (argv[0] if argv[0].startswith("./") else argv[1])
                        self.assertTrue(script.is_file(), script)


class PlanTest(unittest.TestCase):
    def ids(self, paths):
        return [check.id for check in CHECKS.plan(paths, affected=True)[0]]

    def test_the_default_and_every_uncertain_case_is_the_full_gate(self):
        full = [check.id for check in CHECKS.CHECKS]
        self.assertEqual([c.id for c in CHECKS.plan(["docs/a.md"], affected=False)[0]], full)
        self.assertEqual(self.ids(None), full)                         # no merge base / shallow history
        self.assertEqual(self.ids([]), full)
        self.assertEqual(self.ids(["docs/a.md", "design/prototype/new.html"]), full)   # owned by no check
        self.assertEqual(self.ids(["compose.local-full-stack.yml"]), full)

    def test_affected_plans_follow_ownership_and_dependencies(self):
        self.assertEqual(self.ids(["docs/engineering/agent-runbook.md"]), ["docs"])
        self.assertEqual(self.ids(["frontend/src/app/app.spec.ts"]),
                         ["frontend-static", "delivery-contracts", "frontend-tests", "release-images"])
        backend = self.ids(["backend/services/learning/src/test/java/AppTest.java"])
        self.assertEqual(backend, ["backend-security", "backend-quality", "release-images"])
        workflow = self.ids([".github/workflows/pull-request.yaml"])
        self.assertIn("automation-policy", workflow)
        self.assertIn("frontend-static", workflow)                    # delivery contracts read the build

    def test_the_same_inputs_give_the_same_plan(self):
        paths = ["frontend/src/app/app.ts", "backend/build.gradle.kts", "docs/a.md"]
        first = CHECKS.plan(paths, affected=True)
        self.assertEqual(first, CHECKS.plan(list(reversed(paths)) + paths[:1], affected=True))
        self.assertEqual(CHECKS.plan_digest(first[0]), CHECKS.plan_digest(CHECKS.plan(paths, True)[0]))


class CandidateProtocolTest(unittest.TestCase):
    """A small repository and two quick checks; the full backend gate is not needed to test the protocol."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.repo, self.state = base / "repo", base / "state"
        self.repo.mkdir()
        self.cmd("init", "-q", "-b", "main")
        self.cmd("config", "user.email", "checks@example.invalid")
        self.cmd("config", "user.name", "checks")
        (self.repo / "value.txt").write_text("candidate\n")
        self.cmd("add", "-A")
        self.cmd("commit", "-q", "-m", "candidate")
        self.sha = self.cmd("rev-parse", "HEAD")
        # The first check simulates another session editing the working checkout while the gate runs.
        edit = f"open({str(self.repo / 'value.txt')!r}, 'w').write('edited by another session\\n')"
        read = "import sys; sys.exit(open('value.txt').read() != 'candidate\\n')"
        self.catalogue = (
            CHECKS.Check("edit", "concurrent edit", (r".*",), local=((".", (sys.executable, "-c", edit)),)),
            CHECKS.Check("read", "reads the candidate", (r".*",), local=((".", (sys.executable, "-c", read)),)),
        )
        patches = [mock.patch.object(CHECKS, "ROOT", self.repo), mock.patch.object(CHECKS, "CHECKS", self.catalogue),
                   mock.patch.dict(os.environ, {"MNEMA_CANDIDATE_DIR": str(self.state), "MNEMA_CHECK_RUNNER": "direct"})]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def cmd(self, *args):
        return subprocess.run(["git", "-C", str(self.repo), *args], check=True, capture_output=True,
                              text=True).stdout.strip()

    def run_full(self, checks=None):
        worktree = CHECKS.prepare(self.sha)
        record = CHECKS.execute(self.sha, list(checks or self.catalogue), worktree)
        CHECKS.candidate_paths(self.sha)[1].write_text(json.dumps(record))
        return record, worktree

    def test_another_sessions_edit_does_not_change_the_candidate_and_the_evidence_stays_usable(self):
        record, _ = self.run_full()
        self.assertEqual([c["status"] for c in record["checks"]], ["passed", "passed"])
        self.assertEqual((self.repo / "value.txt").read_text(), "edited by another session\n")
        self.assertEqual(CHECKS.verify(self.sha), [])

    def test_a_changed_candidate_invalidates_the_evidence(self):
        _, worktree = self.run_full()
        (worktree / "value.txt").write_text("changed after the run\n")
        self.assertIn("the validation worktree no longer matches the candidate", CHECKS.verify(self.sha))
        with self.assertRaises(SystemExit):
            CHECKS.prepare(self.sha)                                   # never reused, never silently reset

    def test_a_partial_plan_or_a_failure_is_not_full_gate_evidence(self):
        self.run_full(checks=[self.catalogue[1]])
        self.assertIn("the record is not a run of the current full plan", CHECKS.verify(self.sha))
        failing = CHECKS.Check("fail", "fails", (r".*",), local=((".", (sys.executable, "-c", "raise SystemExit(2)")),))
        with mock.patch.object(CHECKS, "CHECKS", (failing,)):
            record, _ = self.run_full(checks=[failing])
            self.assertEqual(record["checks"][0]["status"], "failed")
            problems = CHECKS.verify(self.sha)
        self.assertIn("fail did not pass", problems)
        self.assertIn("the run did not complete", problems)

    def test_evidence_belongs_to_one_candidate(self):
        self.run_full()
        (self.repo / "value.txt").write_text("next\n")
        self.cmd("commit", "-qam", "next")
        self.assertEqual(CHECKS.verify(self.cmd("rev-parse", "HEAD")), [f"no recorded run for {self.cmd('rev-parse', 'HEAD')[:12]}"])
        self.assertEqual(CHECKS.verify(self.sha), [])


if __name__ == "__main__":
    unittest.main()
