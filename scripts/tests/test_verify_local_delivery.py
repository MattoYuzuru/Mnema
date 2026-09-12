"""Fail closed if the reviewed no-infrastructure workflow boundary changes."""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_artifact_security_policy import _job_blocks

ROOT = Path(__file__).resolve().parents[2]
FALSE = '    if: ${{ false }}'
DORMANT = {
    'staging-deploy.yaml': {'validate-main-ci': FALSE, 'deploy-staging': FALSE},
    'production-deploy.yaml': {
        'validate-staging-deploy': FALSE,
        'preview-production': "    if: ${{ false && (needs.validate-staging-deploy.outputs.production_eligible == 'true') }}",
        'deploy-production': "    if: ${{ false && (needs.preview-production.outputs.has_release_changes == 'true') }}",
    },
    'database-recovery.yaml': {'database-recovery': FALSE},
    'staging-rollback-drill.yaml': {'rollback-drill': FALSE},
}
ACTIVE = {
    'deploy.yaml': {'validate-main-ref', 'backend-quality', 'frontend-quality'},
    'pull-request.yaml': {'backend-quality', 'frontend-quality'},
    'dependency-review.yaml': {'dependency-review'},
}


def verify(contents):
    """Use the repository's canonical indentation, also checked by artifact policy."""
    errors = []
    if set(contents) != set(DORMANT) | set(ACTIVE):
        errors.append('workflow inventory changed; review no-infrastructure boundary')
    for filename, content in contents.items():
        jobs = _job_blocks(content)
        job_text = content.partition('jobs:\n')[2]
        if len(re.findall(r'^  [a-zA-Z0-9_-]+:$', job_text, re.M)) != len(jobs):
            errors.append(f'{filename}: duplicate job')
        expected = dict(DORMANT.get(filename, {}))
        if filename == 'deploy.yaml':
            expected.update({'build-and-push': FALSE, 'render-release': FALSE})
        if set(jobs) != set(expected) | ACTIVE.get(filename, set()):
            errors.append(f'{filename}: unexpected or missing jobs')
        if filename in DORMANT:
            header = content.partition('on:\n')[2]
            header = re.split(r'^\S', header, maxsplit=1, flags=re.M)[0]
            triggers = re.findall(r'^  ([a-z_]+):', header, re.M)
            if triggers != ['workflow_dispatch']:
                errors.append(f'{filename}: operational trigger must be manual-only')
        for name, block in jobs.items():
            guards = [line for line in block if line.startswith('    if:')]
            if name in expected:
                if guards != [expected[name]]:
                    errors.append(f'{filename}/{name}: missing literal false job guard')
            elif name in ACTIVE.get(filename, set()):
                if guards:
                    errors.append(f'{filename}/{name}: quality job must stay enabled')
                if any(re.match(r'^    (environment|uses):', line) for line in block):
                    errors.append(f'{filename}/{name}: quality job cannot enter environment or delegate')
                if any('secrets.' in line or re.match(r'^      [a-z-]+: write$', line) for line in block):
                    errors.append(f'{filename}/{name}: quality job cannot publish or read secrets')
    return errors


class LocalDeliveryContractTest(unittest.TestCase):
    def setUp(self):
        self.contents = {p.name: p.read_text() for p in (ROOT / '.github/workflows').iterdir()
                         if p.suffix in {'.yml', '.yaml'}}

    def test_checked_in_workflows_keep_quality_and_disable_every_operational_job(self):
        self.assertEqual([], verify(self.contents))

    def test_each_operational_guard_is_required(self):
        cases = {**DORMANT, 'deploy.yaml': {'build-and-push': FALSE, 'render-release': FALSE}}
        for file, jobs in cases.items():
            for job, guard in jobs.items():
                with self.subTest(file=file, job=job):
                    changed = dict(self.contents)
                    block = '\n'.join(_job_blocks(changed[file])[job])
                    changed[file] = changed[file].replace(block, block.replace(guard, '    if: ${{ always() }}'))
                    self.assertTrue(verify(changed))

    def test_automatic_trigger_cannot_return(self):
        for file in DORMANT:
            for trigger in ('workflow_run', 'push', 'schedule', 'workflow_call'):
                with self.subTest(file=file, trigger=trigger):
                    changed = dict(self.contents)
                    changed[file] = changed[file].replace('  workflow_dispatch:', f'  {trigger}:', 1)
                    self.assertTrue(verify(changed))

    def test_new_unguarded_job_or_workflow_cannot_escape_inventory(self):
        changed = dict(self.contents)
        changed['staging-deploy.yaml'] += '\n  unexpected:\n    runs-on: ubuntu-latest\n'
        self.assertTrue(verify(changed))
        changed = dict(self.contents, **{'new-deploy.yml': 'on: push\njobs:\n'})
        self.assertTrue(verify(changed))

    def test_quality_jobs_cannot_be_skipped_or_gain_environment_access(self):
        for file, jobs in ACTIVE.items():
            for job in jobs:
                for insertion in (FALSE, '    environment: prod', '    permissions:\n      packages: write'):
                    with self.subTest(file=file, job=job, insertion=insertion):
                        changed = dict(self.contents)
                        changed[file] = changed[file].replace(f'  {job}:\n', f'  {job}:\n{insertion}\n', 1)
                        self.assertTrue(verify(changed))

    def test_variable_based_opt_in_does_not_replace_literal_lock(self):
        changed = dict(self.contents)
        changed['staging-deploy.yaml'] = changed['staging-deploy.yaml'].replace(FALSE, "    if: ${{ vars.ENABLE_DEPLOY == 'true' }}")
        self.assertTrue(verify(changed))


if __name__ == '__main__':
    unittest.main()
