#!/usr/bin/env python3
"""Transfer only JaCoCo data and bounded suite metadata between isolated CI shards."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

MODULES = ("identity-account", "learning")
ROOT = Path(__file__).resolve().parents[1]


def revision(root: Path) -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()


def partition(names: list[str], durations: dict[str, int], count: int) -> dict[str, int]:
    """Match Gradle's LPT allocation, with UTF-8 SHA-256 tie-breaking across runtimes."""
    if not 1 <= count <= 8 or len(names) != len(set(names)):
        raise ValueError("Invalid shard count or duplicate inventory class")
    totals = [0] * count
    assignments = {}
    for name in sorted(names, key=lambda n: (-durations.get(n, 5000), hashlib.sha256(n.encode('utf-8')).digest())):
        bucket = min(range(count), key=lambda i: (totals[i], i))
        assignments[name] = bucket + 1
        totals[bucket] += durations.get(name, 5000)
    return assignments


def assignments(root: Path, module: str, count: int) -> dict[str, int]:
    durations = {}
    for line in (root / 'backend/test-durations.tsv').read_text().splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        fields = line.split('\t')
        if len(fields) != 2 or fields[0] in durations or not 1 <= int(fields[1]) <= 3_600_000:
            raise ValueError(f"Invalid or duplicate test duration: {line}")
        durations[fields[0]] = int(fields[1])
    names = json.loads((root / f'backend/services/{module}/build/test-inventory.json').read_text())
    return partition(names, durations, count)


def source_digest(root: Path) -> str:
    digest = hashlib.sha256()
    paths = sorted((root / "backend").glob("services/*/src/test/java/**/*.java"))
    paths += sorted((root / "backend").glob("test-support/**/*.java"))
    paths += sorted((root / "backend").glob("**/*.gradle.kts"))
    paths += [root / "backend/test-opt-ins.json", root / "backend/test-durations.tsv"]
    for path in paths:
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def require_inventory(root: Path, suites: list[dict], index: int, count: int) -> None:
    for module in MODULES:
        expected = {name for name, shard in assignments(root, module, count).items() if shard == index}
        actual = {suite['name'].split('$')[0] for suite in suites if suite['module'] == module}
        if not expected or actual != expected:
            raise ValueError(f"Incomplete {module} inventory: missing={sorted(expected-actual)}, unexpected={sorted(actual-expected)}")


def pack(root: Path, index: int, count: int, output: Path) -> dict:
    if not 1 <= index <= count <= 8:
        raise ValueError("Expected shard index/count with count <= 8")
    output.mkdir(parents=True, exist_ok=False)
    suites = []
    execution = {}
    allowed_skips = json.loads((root / "backend/test-opt-ins.json").read_text())
    for module in MODULES:
        build = root / "backend/services" / module / "build"
        allocated = assignments(root, module, count)
        reports = sorted((build / "test-results/test").glob("TEST-*.xml"))
        if not reports:
            raise ValueError(f"No reports for {module}")
        for path in reports:
            suite = ET.parse(path).getroot()
            name = suite.attrib["name"]
            if allocated.get(name.split("$")[0]) != index:
                raise ValueError(f"Suite outside shard: {name}")
            tests = suite.findall("testcase")
            unexpected_skips = [test.get("name") for test in tests if test.find("skipped") is not None
                                and test.get("name") not in allowed_skips.get(name, [])]
            if unexpected_skips:
                raise ValueError(f"Unexpected skipped tests in {name}: {unexpected_skips}")
            if any(test.find("failure") is not None or test.find("error") is not None for test in tests):
                raise ValueError(f"Failing suite: {name}")
            if int(suite.get("failures", "0")) or int(suite.get("errors", "0")):
                raise ValueError(f"Failing suite counters: {name}")
            suites.append({"module": module, "name": name, "tests": len(tests),
                           "skipped": sum(test.find("skipped") is not None for test in tests),
                           "seconds": float(suite.get("time", "0"))})
        source = build / "jacoco/test.exec"
        if not source.is_file() or not source.stat().st_size:
            raise ValueError(f"No execution data for {module}")
        target = output / f"{module}.exec"
        shutil.copyfile(source, target)
        execution[module] = hashlib.sha256(target.read_bytes()).hexdigest()
    require_inventory(root, suites, index, count)
    manifest = {"version": 1, "revision": revision(root), "source": source_digest(root),
                "index": index, "count": count, "execution": execution, "suites": suites}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def merge(root: Path, inputs: Path, count: int) -> list[dict]:
    if not 1 <= count <= 8:
        raise ValueError("Expected 1..8 shards")
    manifests = sorted(inputs.glob("*/manifest.json"))
    if len(manifests) != count:
        raise ValueError(f"Expected {count} manifests, found {len(manifests)}")
    seen = set()
    names = set()
    results = []
    transfers = []
    expected_revision, expected_source = revision(root), source_digest(root)
    allocated = {module: assignments(root, module, count) for module in MODULES}
    for path in manifests:
        data = json.loads(path.read_text())
        index = data["index"]
        if (data["version"] != 1 or data["count"] != count or not 1 <= index <= count
                or index in seen or data["revision"] != expected_revision or data["source"] != expected_source):
            raise ValueError("Duplicate, stale or incompatible shard")
        seen.add(index)
        if {suite['module'] for suite in data['suites']} != set(MODULES):
            raise ValueError("Missing module suite evidence")
        for suite in data["suites"]:
            key = (suite["module"], suite["name"])
            if suite["module"] not in MODULES or key in names or allocated[suite["module"]].get(suite["name"].split("$")[0]) != index:
                raise ValueError("Duplicate suite or incorrect partition")
            if suite["tests"] < 1 or not 0 <= suite["skipped"] <= suite["tests"] or suite["seconds"] < 0:
                raise ValueError("Invalid suite counters")
            names.add(key)
        require_inventory(root, data['suites'], index, count)
        for module in MODULES:
            source = path.parent / f"{module}.exec"
            if hashlib.sha256(source.read_bytes()).hexdigest() != data["execution"][module]:
                raise ValueError("Execution data checksum mismatch")
            target = root / f"backend/services/{module}/build/jacoco/test-shard-{index}.exec"
            if target.exists():
                raise ValueError(f"Stale merge output exists: {target}")
            transfers.append((source, target))
        results.append(data)
    # Validate every input before writing anything; never run tests again in the aggregate job.
    for source, target in transfers:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    return results


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    packing = sub.add_parser("pack")
    packing.add_argument("--index", type=int, required=True)
    packing.add_argument("--count", type=int, default=8)
    packing.add_argument("--output", type=Path, default=ROOT / "backend/build/ci-shard")
    merging = sub.add_parser("merge")
    merging.add_argument("--inputs", type=Path, default=ROOT / "backend/build/ci-inputs")
    merging.add_argument("--count", type=int, default=8)
    sub.add_parser("verify-local")
    args = parser.parse_args()
    if args.command == "verify-local":
        with tempfile.TemporaryDirectory(prefix="mnema-test-inventory-") as temporary:
            data = pack(ROOT, 1, 1, Path(temporary) / "evidence")
    else:
        data = pack(ROOT, args.index, args.count, args.output) if args.command == "pack" else merge(ROOT, args.inputs, args.count)
    manifests = [data] if isinstance(data, dict) else data
    suites = [suite for manifest in manifests for suite in manifest["suites"]]
    print(json.dumps({"suites": len(suites), "tests": sum(suite["tests"] for suite in suites),
                      "skipped": sum(suite["skipped"] for suite in suites),
                      "suite_seconds": round(sum(suite["seconds"] for suite in suites), 2),
                      "slowest": sorted(suites, key=lambda suite: suite["seconds"], reverse=True)[:10]}))


if __name__ == "__main__":
    main()
