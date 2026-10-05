#!/usr/bin/env python3
"""Bounded CONNECT probes. Credentials travel only through curl's stdin."""

import argparse
import json
from pathlib import Path
import subprocess
from urllib.parse import urlsplit


def curl_quote(value):
    if not isinstance(value, str) or any(c in value for c in "\r\n\x00"):
        raise ValueError("invalid private credential format")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def probe(proxy, target, credentials):
    config = "proxy = " + curl_quote(proxy) + "\n"
    if credentials:
        config += "proxy-user = " + curl_quote(credentials["user"] + ":" + credentials["password"]) + "\n"
    result = subprocess.run(
        ["curl", "-q", "--config", "-", "--noproxy", "", "--silent", "--head",
         "--max-time", "12", "--connect-timeout", "5", "--output", "/dev/null",
         "--write-out", "%{http_connect} %{http_code} %{time_total}", target],
        input=config, text=True, capture_output=True, timeout=15,
    )
    # Do not print curl stderr, response bodies, config or private values.
    parts = result.stdout.split()
    if len(parts) != 3:
        return {"connect": 0, "http": 0, "seconds": None, "transport_exit": result.returncode}
    return {"connect": int(parts[0]), "http": int(parts[1]), "seconds": float(parts[2]),
            "transport_exit": result.returncode}


def accepted(name, result):
    if name == "allowed-google":
        return result["connect"] == 200 and result["http"] in (200, 404) and result["transport_exit"] == 0
    if name == "no-auth":
        return result["connect"] == 407
    if name == "non-connect":
        return result["http"] == 403
    return result["connect"] == 403


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proxy", default="http://127.0.0.1:13128")
    parser.add_argument("--auth-file", required=True, type=Path)
    args = parser.parse_args()
    uri = urlsplit(args.proxy)
    if uri.scheme != "http" or uri.hostname not in ("127.0.0.1", "localhost") or not uri.port or uri.username or uri.path:
        parser.error("verification requires a private loopback HTTP proxy")
    credentials = json.loads(args.auth_file.read_text())
    curl_quote(credentials["user"])
    curl_quote(credentials["password"])
    checks = [
        ("allowed-google", "https://generativelanguage.googleapis.com/", credentials),
        ("no-auth", "https://generativelanguage.googleapis.com/", None),
        ("foreign-host", "https://example.com/", credentials),
        ("loopback", "https://127.0.0.1/", credentials),
        ("ipv6-loopback", "https://[::1]/", credentials),
        ("mapped-loopback", "https://[::ffff:127.0.0.1]/", credentials),
        ("metadata", "https://169.254.169.254/", credentials),
        ("non-connect", "http://generativelanguage.googleapis.com/", credentials),
        ("foreign-port", "https://generativelanguage.googleapis.com:444/", credentials),
    ]
    results = []
    for name, target, auth in checks:
        try:
            result = probe(args.proxy, target, auth)
        except (subprocess.TimeoutExpired, ValueError):
            result = {"connect": 0, "http": 0, "seconds": None, "transport_exit": -1}
        results.append({"name": name, **result, "passed": accepted(name, result)})
    passed = all(x["passed"] for x in results)
    print(json.dumps({"scope": "CONNECT transport only; no paid provider capability", "passed": passed, "checks": results}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
