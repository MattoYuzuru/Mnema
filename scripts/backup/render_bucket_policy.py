#!/usr/bin/env python3
"""Render the canonical conditional-write statements for a complete bucket policy."""

from __future__ import annotations

import json
import os
import re
import sys


BUCKET = re.compile(r"^(?![.-])(?!.*\.\.)(?!.*\.$)[a-z0-9.-]{3,63}$")
PREFIX = re.compile(r"^(?!/)(?!.*(?:\.\.|//))[A-Za-z0-9._/-]+(?<!/)$")


def required(name: str, pattern: re.Pattern[str]) -> str:
    value = os.environ.get(name, "")
    if not pattern.fullmatch(value):
        raise ValueError(f"{name} is missing or invalid")
    return value


def render_policy_fragment(bucket: str, prefix: str) -> dict[str, object]:
    immutable = f"arn:aws:s3:::{bucket}/{prefix}/postgres/*/*"
    latest = f"arn:aws:s3:::{bucket}/{prefix}/postgres/latest.env"
    return {
        "Statement": [
            {
                "Sid": "DenyImmutableWritesWithoutIfNoneMatch",
                "Effect": "Deny",
                "Principal": "*",
                "Action": "s3:PutObject",
                "Resource": immutable,
                "Condition": {"Null": {"s3:if-none-match": "true"}},
            },
            {
                "Sid": "DenyUnconditionalLatestPointerWrites",
                "Effect": "Deny",
                "Principal": "*",
                "Action": "s3:PutObject",
                "Resource": latest,
                "Condition": {
                    "Null": {
                        "s3:if-match": "true",
                        "s3:if-none-match": "true",
                    }
                },
            },
        ],
    }


def main() -> int:
    try:
        policy = render_policy_fragment(
            required("S3_BUCKET", BUCKET),
            required("S3_PREFIX", PREFIX),
        )
    except ValueError as error:
        print(f"policy_error={str(error).replace(' ', '_').lower()}", file=sys.stderr)
        return 1
    json.dump(policy, sys.stdout, sort_keys=True, separators=(",", ":"))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
