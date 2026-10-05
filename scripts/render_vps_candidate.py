#!/usr/bin/env python3
"""Render image identities only; root admission still requires runtime acceptance."""
import argparse
from collections import Counter
from datetime import UTC, datetime
import sys

from verify_release_security_evidence import (
    EvidenceFailure, SHA_PATTERN, SEVERITIES, VPS_SERVICES, file_sha256,
    load_json, validate_applied_exceptions, validate_evidence, validate_trivy_ignore, write_json,
)


def render(args):
    if args.repository.lower() != 'mattoyuzuru/mnema' or not SHA_PATTERN.fullmatch(args.sha):
        raise EvidenceFailure('candidate target or source is invalid')
    if args.run_id <= 0 or args.run_attempt <= 0:
        raise EvidenceFailure('candidate build identity is invalid')
    data = load_json(args.evidence)
    source = {'repository': args.repository.lower(), 'commit': args.sha,
              'workflow': '.github/workflows/deploy.yaml',
              'runId': args.run_id, 'runAttempt': args.run_attempt}
    if not isinstance(data, dict) or data.get('schemaVersion') != 1 or data.get('source') != source:
        raise EvidenceFailure('candidate source does not match verified security evidence')
    if data.get('policy') != {'blockedSeverities': ['CRITICAL', 'HIGH'],
                             'exceptionMaximumLifetimeDays': 30, 'outcome': 'passed'}:
        raise EvidenceFailure('candidate security policy did not pass')
    images = data.get('images')
    if not isinstance(images, list) or len(images) != len(VPS_SERVICES):
        raise EvidenceFailure('candidate requires four image security records')
    ignore_hash = validate_trivy_ignore(args.trivy_ignore)
    result = {}
    totals = Counter()
    exception_count = 0
    for item in images:
        service = item.get('service') if isinstance(item, dict) else None
        if service not in VPS_SERVICES or service in result:
            raise EvidenceFailure('candidate has missing, duplicate or unexpected services')
        verified = validate_evidence(item, service, args.repository, args.sha,
                                     args.run_id, args.run_attempt, ignore_hash)
        validate_applied_exceptions(verified['exceptions'], datetime.now(UTC).date(), service)
        counts = verified['scanner']['counts']
        if set(counts) != SEVERITIES or any(type(v) is not int or v < 0 for v in counts.values()):
            raise EvidenceFailure('candidate vulnerability counts are invalid')
        allowed = Counter(exception['severity'] for exception in verified['exceptions'])
        if any(counts[severity] != allowed[severity] for severity in ('HIGH', 'CRITICAL')):
            raise EvidenceFailure('candidate has unresolved blocking vulnerabilities')
        totals.update(counts)
        exception_count += len(verified['exceptions'])
        result[service] = verified['image']
    summary = {'imageCount': len(VPS_SERVICES), 'vulnerabilities':
               {severity: totals[severity] for severity in sorted(SEVERITIES)},
               'exceptionCount': exception_count}
    if data.get('summary') != summary:
        raise EvidenceFailure('candidate security summary is inconsistent')
    # Deliberately omit every dispatcher acceptance flag. A published artifact
    # cannot authorize migrations, first-launch data boundaries or live auth.
    write_json(args.output, {'schemaVersion': 1, 'sha': args.sha, 'images': result,
                            'source': source, 'securityEvidenceSha256': file_sha256(args.evidence)})


def main():
    from pathlib import Path
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--run-attempt', type=int, required=True)
    parser.add_argument('--trivy-ignore', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    try:
        render(parser.parse_args())
    except EvidenceFailure as error:
        print(f'VPS candidate rejected: {error}', file=sys.stderr)
        return 1
    print('VPS candidate image/security binding passed; deployment admission remains separate')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
