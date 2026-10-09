"""Production delivery stays main-only, scoped and approval-gated; no other delivery workflow exists."""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_artifact_security_policy import _job_blocks

ROOT = Path(__file__).resolve().parents[2]
FALSE = '    if: ${{ false }}'
PUBLISH = "    if: ${{ needs.release-scope.outputs.deploy == 'true' && github.ref == 'refs/heads/main' }}"
VPS = "    if: ${{ github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' }}"
# Jobs that follow the publication guard through `needs`; only the first may enter prod.
APPROVED = {'deploy-production', 'post-deploy-smoke'}
ACTIVE = {
    'deploy.yaml': {'validate-main-ref', 'release-scope', 'backend-quality', 'frontend-quality'},
    'pull-request.yaml': {'backend-quality', 'frontend-quality'},
    'dependency-review.yaml': {'dependency-review'},
}


def verify(contents):
    """Use the repository's canonical indentation, also checked by artifact policy."""
    errors = []
    if set(contents) != set(ACTIVE) | {'vps-deploy.yaml'}:
        errors.append('workflow inventory changed; review the delivery boundary')
    for filename, content in contents.items():
        jobs = _job_blocks(content)
        job_text = content.partition('jobs:\n')[2]
        if len(re.findall(r'^  [a-zA-Z0-9_-]+:$', job_text, re.M)) != len(jobs):
            errors.append(f'{filename}: duplicate job')
        expected = {}
        if filename == 'vps-deploy.yaml':
            expected = {'operate-vps': VPS}
            header = content.partition('on:\n')[2].partition('\npermissions:')[0]
            if re.findall(r'^  ([a-z_]+):', header, re.M) != ['workflow_dispatch']:
                errors.append('VPS operations must have only a manual trigger')
            required = ('    environment:\n      name: prod\n',
                        '      deployment: false\n',   # keeps reviewers/secrets, creates no Deployment record
                        '      group: mnema-vps-operations\n      cancel-in-progress: false',
                        '        run: bash scripts/deploy-vps.sh --operation "$MNEMA_OPERATION"',
                        '        type: choice\n        required: true',
                        '          - status\n          - verify\n          - rollback')
            if any(value not in content for value in required) or \
                    re.findall(r'^          - (\w+)$', content, re.M) != ['status', 'verify', 'rollback']:
                errors.append('VPS operations require protected prod, serial execution and a fixed operation entrypoint')
        if filename == 'deploy.yaml':
            expected.update({'build-and-push': PUBLISH, 'assemble-vps-candidate': PUBLISH})
            header = content.partition('on:\n')[2].partition('\npermissions:')[0]
            if re.findall(r'^  ([a-z_]+):', header, re.M) != ['push', 'workflow_dispatch'] \
                    or '    branches: [main]' not in header or 'inputs:' in header:
                errors.append('Main CI runs on pushes to main and an input-free manual dispatch only')
            build = '\n'.join(jobs.get('build-and-push', []))
            candidate = '\n'.join(jobs.get('assemble-vps-candidate', []))
            deploy = '\n'.join(jobs.get('deploy-production', []))
            smoke = '\n'.join(jobs.get('post-deploy-smoke', []))
            if '    needs:\n      - backend-quality\n      - frontend-quality\n      - release-scope' not in build:
                errors.append('publication must depend on both quality gates and the release scope')
            if '    needs:\n      - build-and-push\n      - release-scope' not in candidate:
                errors.append('candidate must depend on all image/security gates')
            if ':latest' in build or 'TAG=sha-${GITHUB_SHA::7}' in build:
                errors.append('publication requires full SHA tags without latest')
            for block in (build, candidate):
                if re.search(r'^    (environment|uses):', block, re.M):
                    errors.append('publication cannot deploy or enter an environment')
            if '    needs: assemble-vps-candidate' not in deploy or '    needs: deploy-production' not in smoke:
                errors.append('deployment must follow the assembled candidate and smoke must follow deployment')
            if ('    environment:\n      name: prod\n' not in deploy
                    or '      group: mnema-vps-production\n      cancel-in-progress: false' not in deploy
                    or 'gh attestation verify' not in deploy
                    or 'bash scripts/deploy-vps.sh "$RUNNER_TEMP/vps-candidate/vps-candidate.json"' not in deploy):
                errors.append('deployment requires prod approval, serial rollout, attestation re-check and the fixed entrypoint')
            if re.search(r'^    (environment|uses|if):', smoke, re.M):
                errors.append('post-deploy smoke cannot enter an environment or be skipped by a custom guard')
            for name in APPROVED:
                if re.search(r'^    if:', '\n'.join(jobs.get(name, [])), re.M):
                    errors.append(f'{name} must be skipped only through its needs chain')
        if set(jobs) != set(expected) | ACTIVE.get(filename, set()) | (APPROVED if filename == 'deploy.yaml' else set()):
            errors.append(f'{filename}: unexpected or missing jobs')
        for name, block in jobs.items():
            if any(re.match(r'^    uses:', line) for line in block):
                errors.append(f'{filename}/{name}: workflow callers are disabled in local-only mode')
            guards = [line for line in block if line.startswith('    if:')]
            if filename == 'deploy.yaml' and name in APPROVED:
                continue
            if name in expected:
                if guards != [expected[name]]:
                    errors.append(f'{filename}/{name}: missing reviewed job guard')
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

    def test_checked_in_workflows_keep_quality_and_production_delivery_locked(self):
        self.assertEqual([], verify(self.contents))

    def test_each_operational_guard_is_required(self):
        cases = {'deploy.yaml': {'build-and-push': PUBLISH, 'assemble-vps-candidate': PUBLISH},
                 'vps-deploy.yaml': {'operate-vps': VPS}}
        for file, jobs in cases.items():
            for job, guard in jobs.items():
                with self.subTest(file=file, job=job):
                    changed = dict(self.contents)
                    block = '\n'.join(_job_blocks(changed[file])[job])
                    changed[file] = changed[file].replace(block, block.replace(guard, '    if: ${{ always() }}'))
                    self.assertTrue(verify(changed))

    def test_new_unguarded_job_or_workflow_cannot_escape_inventory(self):
        changed = dict(self.contents)
        changed['deploy.yaml'] += '\n  unexpected:\n    runs-on: ubuntu-latest\n'
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

    def test_existing_jobs_cannot_become_workflow_callers(self):
        for file, content in self.contents.items():
            for job in _job_blocks(content):
                for prefix in ('./', '$/', 'MattoYuzuru/Mnema/'):
                    with self.subTest(file=file, job=job, prefix=prefix):
                        changed = dict(self.contents)
                        reference = f'{prefix}.github/workflows/vps-deploy.yaml'
                        if prefix == 'MattoYuzuru/Mnema/':
                            reference += '@main'
                        changed[file] = content.replace(
                            f'  {job}:\n', f'  {job}:\n    uses: {reference}\n', 1)
                        self.assertTrue(verify(changed))

    def test_publication_cannot_skip_quality_or_scope_or_enter_prod(self):
        cases = [('      - backend-quality\n      - frontend-quality\n      - release-scope', '      - backend-quality\n      - release-scope'),
                 ('      - backend-quality\n      - frontend-quality\n      - release-scope', '      - backend-quality\n      - frontend-quality'),
                 ('      - build-and-push\n      - release-scope', '      - release-scope'),
                 ('TAG=sha-${GITHUB_SHA}', 'TAG=sha-${GITHUB_SHA::7}'),
                 ('  assemble-vps-candidate:\n', '  assemble-vps-candidate:\n    environment: prod\n'),
                 ('  build-and-push:\n', '  build-and-push:\n    environment: prod\n'),
                 ('  push:\n    branches: [main]', '  push:\n    branches: [main, feature]'),
                 ('  workflow_dispatch:\n', '  workflow_dispatch:\n    inputs:\n      force:\n        type: boolean\n')]
        for before, after in cases:
            with self.subTest(after=after):
                changed = dict(self.contents)
                self.assertIn(before, changed['deploy.yaml'])
                changed['deploy.yaml'] = changed['deploy.yaml'].replace(before, after, 1)
                self.assertTrue(verify(changed))

    def test_deployment_cannot_skip_approval_serialisation_attestation_or_use_another_entrypoint(self):
        cases = [('      name: prod\n      url: https://mnema.app', '      name: unprotected\n      url: https://mnema.app'),
                 ('      group: mnema-vps-production\n      cancel-in-progress: false', '      group: mnema-vps-production\n      cancel-in-progress: true'),
                 ('gh attestation verify', 'gh attestation download'),
                 ('bash scripts/deploy-vps.sh "$RUNNER_TEMP/vps-candidate/vps-candidate.json"', 'ssh arbitrary-host'),
                 ('    needs: assemble-vps-candidate\n', '    needs: validate-main-ref\n'),
                 ('    needs: deploy-production\n', '    needs: release-scope\n')]
        for before, after in cases:
            with self.subTest(after=after):
                changed = dict(self.contents)
                self.assertIn(before, changed['deploy.yaml'])
                changed['deploy.yaml'] = changed['deploy.yaml'].replace(before, after)
                self.assertTrue(verify(changed))
        for job in APPROVED:
            with self.subTest(job=job):
                changed = dict(self.contents)
                changed['deploy.yaml'] = changed['deploy.yaml'].replace(
                    f'  {job}:\n', f'  {job}:\n    if: ${{{{ always() }}}}\n', 1)
                self.assertTrue(verify(changed))

    def test_vps_operations_cannot_run_automatically_skip_prod_or_replace_entrypoint(self):
        for before, after in [('  workflow_dispatch:', '  push:'),
                              ('      name: prod', '      name: unprotected'),
                              ('      deployment: false\n', ''),
                              ('      cancel-in-progress: false', '      cancel-in-progress: true'),
                              ('          - rollback\n', '          - rollback\n          - deploy\n'),
                              ('bash scripts/deploy-vps.sh --operation', 'ssh arbitrary-host --operation')]:
            with self.subTest(after=after):
                changed = dict(self.contents)
                changed['vps-deploy.yaml'] = changed['vps-deploy.yaml'].replace(before, after)
                self.assertTrue(verify(changed))


if __name__ == '__main__':
    unittest.main()
