"""Keep dormant operations locked and publication behind the reviewed main-only opt-in."""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_artifact_security_policy import _job_blocks

ROOT = Path(__file__).resolve().parents[2]
FALSE = '    if: ${{ false }}'
PUBLISH = "    if: ${{ github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && inputs.publish_production_candidate == true }}"
VPS = "    if: ${{ github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' }}"
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
    if set(contents) != set(DORMANT) | set(ACTIVE) | {'vps-deploy.yaml'}:
        errors.append('workflow inventory changed; review no-infrastructure boundary')
    for filename, content in contents.items():
        jobs = _job_blocks(content)
        job_text = content.partition('jobs:\n')[2]
        if len(re.findall(r'^  [a-zA-Z0-9_-]+:$', job_text, re.M)) != len(jobs):
            errors.append(f'{filename}: duplicate job')
        expected = dict(DORMANT.get(filename, {}))
        if filename == 'vps-deploy.yaml':
            expected = {'deploy-vps': VPS}
            header = content.partition('on:\n')[2].partition('\npermissions:')[0]
            if re.findall(r'^  ([a-z_]+):', header, re.M) != ['workflow_dispatch']:
                errors.append('VPS deployment must have only a manual trigger')
            required = ('    environment:\n      name: prod\n',
                        '      group: mnema-vps-production\n      cancel-in-progress: false',
                        '        run: bash scripts/deploy-vps.sh',
                        '        type: string\n        required: true')
            if any(value not in content for value in required):
                errors.append('VPS deployment requires protected prod, serial rollout and fixed entrypoint')
        if filename == 'deploy.yaml':
            expected.update({'build-and-push': PUBLISH, 'render-release': FALSE,
                             'assemble-vps-candidate': PUBLISH})
            required_input = ('  workflow_dispatch:\n    inputs:\n      publish_production_candidate:\n'
                              '        description: Build and verify four immutable VPS images; no deployment\n'
                              '        type: boolean\n        required: true\n        default: false')
            if required_input not in content:
                errors.append('publication requires an explicit boolean input defaulting to false')
            build = '\n'.join(jobs.get('build-and-push', []))
            candidate = '\n'.join(jobs.get('assemble-vps-candidate', []))
            if '    needs:\n      - backend-quality\n      - frontend-quality' not in build:
                errors.append('publication must depend on both quality gates')
            if '    needs: build-and-push' not in candidate:
                errors.append('candidate must depend on all image/security gates')
            if ':latest' in build or 'TAG=sha-${GITHUB_SHA::7}' in build:
                errors.append('publication requires full SHA tags without latest')
            for block in (build, candidate):
                if re.search(r'^    (environment|uses):', block, re.M):
                    errors.append('publication cannot deploy or enter an environment')
        if set(jobs) != set(expected) | ACTIVE.get(filename, set()):
            errors.append(f'{filename}: unexpected or missing jobs')
        if filename in DORMANT:
            header = content.partition('on:\n')[2]
            header = re.split(r'^\S', header, maxsplit=1, flags=re.M)[0]
            triggers = re.findall(r'^  ([a-z_]+):', header, re.M)
            if triggers != ['workflow_call']:
                errors.append(f'{filename}: operational blueprint cannot have a standalone trigger')
            if any(line.strip() and not line.lstrip().startswith('#')
                   and line != '  workflow_call:' for line in header.splitlines()):
                errors.append(f'{filename}: dormant workflow_call must have no configuration')
        for name, block in jobs.items():
            if any(re.match(r'^    uses:', line) for line in block):
                errors.append(f'{filename}/{name}: workflow callers are disabled in local-only mode')
            guards = [line for line in block if line.startswith('    if:')]
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

    def test_checked_in_workflows_keep_quality_and_dormant_operations_locked(self):
        self.assertEqual([], verify(self.contents))

    def test_each_operational_guard_is_required(self):
        cases = {**DORMANT, 'deploy.yaml': {'build-and-push': PUBLISH, 'render-release': FALSE,
                                         'assemble-vps-candidate': PUBLISH},
                 'vps-deploy.yaml': {'deploy-vps': VPS}}
        for file, jobs in cases.items():
            for job, guard in jobs.items():
                with self.subTest(file=file, job=job):
                    changed = dict(self.contents)
                    block = '\n'.join(_job_blocks(changed[file])[job])
                    changed[file] = changed[file].replace(block, block.replace(guard, '    if: ${{ always() }}'))
                    self.assertTrue(verify(changed))

    def test_automatic_trigger_cannot_return(self):
        for file in DORMANT:
            for trigger in ('workflow_run', 'push', 'schedule', 'workflow_dispatch', 'repository_dispatch'):
                with self.subTest(file=file, trigger=trigger):
                    changed = dict(self.contents)
                    changed[file] = changed[file].replace('  workflow_call:', f'  {trigger}:', 1)
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

    def test_existing_jobs_cannot_become_workflow_callers(self):
        for file, content in self.contents.items():
            for job in _job_blocks(content):
                for prefix in ('./', '$/', 'MattoYuzuru/Mnema/'):
                    with self.subTest(file=file, job=job, prefix=prefix):
                        changed = dict(self.contents)
                        reference = f'{prefix}.github/workflows/staging-deploy.yaml'
                        if prefix == 'MattoYuzuru/Mnema/':
                            reference += '@main'
                        changed[file] = content.replace(
                            f'  {job}:\n', f'  {job}:\n    uses: {reference}\n', 1)
                        self.assertTrue(verify(changed))

    def test_dormant_definitions_cannot_gain_inputs_or_secrets(self):
        for file in DORMANT:
            for config in ('    inputs: {}', '    secrets: {}'):
                with self.subTest(file=file, config=config):
                    changed = dict(self.contents)
                    changed[file] = changed[file].replace(
                        '  workflow_call:', f'  workflow_call:\n{config}', 1)
                    self.assertTrue(verify(changed))

    def test_publication_cannot_default_on_skip_quality_or_enter_prod(self):
        cases = [('        default: false', '        default: true'),
                 ('      - backend-quality\n      - frontend-quality', '      - backend-quality'),
                 ('    needs: build-and-push', '    needs: validate-main-ref'),
                 ('TAG=sha-${GITHUB_SHA}', 'TAG=sha-${GITHUB_SHA::7}'),
                 ('  assemble-vps-candidate:\n', '  assemble-vps-candidate:\n    environment: prod\n')]
        for before, after in cases:
            with self.subTest(after=after):
                changed = dict(self.contents)
                changed['deploy.yaml'] = changed['deploy.yaml'].replace(before, after)
                self.assertTrue(verify(changed))

    def test_vps_cannot_run_automatically_skip_prod_or_replace_entrypoint(self):
        for before, after in [('  workflow_dispatch:', '  push:'),
                              ('      name: prod', '      name: unprotected'),
                              ('      cancel-in-progress: false', '      cancel-in-progress: true'),
                              ('bash scripts/deploy-vps.sh', 'ssh arbitrary-host')]:
            with self.subTest(after=after):
                changed = dict(self.contents)
                changed['vps-deploy.yaml'] = changed['vps-deploy.yaml'].replace(before, after)
                self.assertTrue(verify(changed))


if __name__ == '__main__':
    unittest.main()
