#!/usr/bin/env python3
"""Persist, adopt and apply complete Mnema release manifests without secret data."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen
from zipfile import BadZipFile, ZipFile
from io import BytesIO


SERVICES = ("frontend", "auth", "user", "core", "media", "import")
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
DIGEST_REF_PATTERN = re.compile(r"^[a-z0-9./_-]+@sha256:[0-9a-f]{64}$")
STATE_CURRENT = "mnema-release-current"
STATE_PREVIOUS = "mnema-release-previous"
MAX_CONFIGMAP_PAYLOAD = 900_000


class StateFailure(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class ReleaseArtifact:
    manifest: str
    checksum: str
    created_at: str


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req: Request, fp: Any, code: int, msg: str, headers: Any, newurl: str):
        return None


class GitHubArtifactClient:
    def __init__(self, repository: str, token: str) -> None:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise StateFailure("invalid_repository")
        if not token:
            raise StateFailure("github_token_missing")
        self.repository = repository
        self.token = token

    def fetch(self, name: str, release_sha: str, manifest_filename: str) -> ReleaseArtifact:
        artifact = self._find(name, release_sha)
        archive = self._download(int(artifact["id"]))
        try:
            with ZipFile(BytesIO(archive)) as bundle:
                names = set(bundle.namelist())
                checksum_filename = f"{manifest_filename}.sha256"
                if manifest_filename not in names or checksum_filename not in names:
                    raise StateFailure("artifact_files_missing")
                manifest_bytes = bundle.read(manifest_filename)
                checksum_text = bundle.read(checksum_filename).decode("utf-8")
        except (BadZipFile, UnicodeDecodeError):
            raise StateFailure("artifact_archive_invalid") from None
        checksum = parse_checksum_file(checksum_text, manifest_filename)
        actual = hashlib.sha256(manifest_bytes).hexdigest()
        if actual != checksum:
            raise StateFailure("artifact_checksum_mismatch")
        try:
            manifest = manifest_bytes.decode("utf-8")
        except UnicodeDecodeError:
            raise StateFailure("artifact_manifest_not_utf8") from None
        return ReleaseArtifact(manifest, checksum, str(artifact["created_at"]))

    def _find(self, name: str, release_sha: str) -> dict[str, Any]:
        for page in range(1, 11):
            query = urlencode({"name": name, "per_page": 100, "page": page})
            payload = self._json(f"https://api.github.com/repos/{self.repository}/actions/artifacts?{query}")
            artifacts = payload.get("artifacts")
            if not isinstance(artifacts, list):
                raise StateFailure("artifact_list_invalid")
            matches = [
                item
                for item in artifacts
                if isinstance(item, dict)
                and item.get("name") == name
                and item.get("expired") is False
                and isinstance(item.get("workflow_run"), dict)
                and item["workflow_run"].get("head_sha") == release_sha
            ]
            if matches:
                return max(matches, key=lambda item: str(item.get("created_at", "")))
            if len(artifacts) < 100:
                break
        raise StateFailure("release_artifact_not_found")

    def _json(self, url: str) -> dict[str, Any]:
        request = self._request(url)
        try:
            with urlopen(request, timeout=30) as response:
                payload = json.load(response)
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError):
            raise StateFailure("github_api_unavailable") from None
        if not isinstance(payload, dict):
            raise StateFailure("github_response_invalid")
        return payload

    def _download(self, artifact_id: int) -> bytes:
        request = self._request(
            f"https://api.github.com/repos/{self.repository}/actions/artifacts/{artifact_id}/zip"
        )
        try:
            build_opener(NoRedirect()).open(request, timeout=30)
        except HTTPError as redirect:
            signed_url = redirect.headers.get("Location")
            redirect.close()
            if redirect.code != 302 or not signed_url:
                raise StateFailure("artifact_download_unavailable") from None
        except (URLError, TimeoutError):
            raise StateFailure("artifact_download_unavailable") from None
        try:
            with urlopen(Request(signed_url), timeout=60) as response:
                data = response.read(MAX_CONFIGMAP_PAYLOAD + 200_000)
        except (HTTPError, URLError, TimeoutError):
            raise StateFailure("artifact_download_unavailable") from None
        if len(data) > MAX_CONFIGMAP_PAYLOAD + 100_000:
            raise StateFailure("artifact_archive_too_large")
        return data

    def _request(self, url: str) -> Request:
        return Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "mnema-release-state/1",
            },
        )


class Kubectl:
    def __init__(self, namespace: str) -> None:
        if not re.fullmatch(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?", namespace) or len(namespace) > 63:
            raise StateFailure("invalid_namespace")
        self.namespace = namespace

    def get_configmap(self, name: str, *, required: bool = True) -> dict[str, Any] | None:
        result = subprocess.run(
            ["kubectl", "-n", self.namespace, "get", "configmap", name, "-o", "json"],
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            if not required and "not found" in result.stderr.lower():
                return None
            raise StateFailure("configmap_read_failed")
        try:
            value = json.loads(result.stdout)
        except json.JSONDecodeError:
            raise StateFailure("configmap_json_invalid") from None
        if not isinstance(value, dict):
            raise StateFailure("configmap_json_invalid")
        return value

    def persist(self, name: str, manifest: str, record: dict[str, Any]) -> None:
        record_text = json.dumps(record, indent=2, sort_keys=True) + "\n"
        if len(manifest.encode()) + len(record_text.encode()) > MAX_CONFIGMAP_PAYLOAD:
            raise StateFailure("release_state_too_large")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "manifest.yaml"
            record_path = root / "record.json"
            manifest_path.write_text(manifest, encoding="utf-8")
            record_path.write_text(record_text, encoding="utf-8")
            rendered = self._run(
                [
                    "kubectl", "-n", self.namespace, "create", "configmap", name,
                    f"--from-file=manifest.yaml={manifest_path}",
                    f"--from-file=record.json={record_path}",
                    "--dry-run=client", "-o", "yaml",
                ]
            )
            self._run(["kubectl", "apply", "-f", "-"], input_text=rendered)

    def apply_manifest(self, manifest_path: Path) -> None:
        self._run(["kubectl", "apply", "--dry-run=server", "-f", str(manifest_path)])
        self._run(["kubectl", "apply", "-f", str(manifest_path)])

    def _run(self, command: list[str], input_text: str | None = None) -> str:
        result = subprocess.run(
            command,
            input=input_text,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            raise StateFailure("kubectl_command_failed")
        return result.stdout


class ReleaseStateManager:
    def __init__(self, kubectl: Kubectl, artifact_client: GitHubArtifactClient | None = None) -> None:
        self.kubectl = kubectl
        self.artifact_client = artifact_client

    def snapshot(
        self,
        *,
        environment: str,
        artifact_name: str,
        artifact_filename: str,
        rollback_manifest: Path,
        rollback_record: Path,
    ) -> str:
        current = self.kubectl.get_configmap(STATE_CURRENT, required=False)
        if current is None:
            current = self._adopt_live(environment, artifact_name, artifact_filename)
        manifest, record = state_from_configmap(current)
        validate_record(manifest, record)
        write_private(rollback_manifest, manifest)
        write_private(rollback_record, json.dumps(record, indent=2, sort_keys=True) + "\n")
        return str(record["releaseId"])

    def record(self, manifest_path: Path, record_path: Path) -> str:
        manifest = manifest_path.read_text(encoding="utf-8")
        record = {**read_record(record_path), "deployedAt": utc_now()}
        validate_record(manifest, record)
        write_private(record_path, json.dumps(record, indent=2, sort_keys=True) + "\n")
        current = self.kubectl.get_configmap(STATE_CURRENT, required=False)
        if current is not None:
            old_manifest, old_record = state_from_configmap(current)
            validate_record(old_manifest, old_record)
            self.kubectl.persist(STATE_PREVIOUS, old_manifest, old_record)
        self.kubectl.persist(STATE_CURRENT, manifest, record)
        return str(record["releaseId"])

    def rollback(self, manifest_path: Path, record_path: Path) -> str:
        manifest = manifest_path.read_text(encoding="utf-8")
        record = read_record(record_path)
        validate_record(manifest, record)
        self.kubectl.apply_manifest(manifest_path)
        return str(record["releaseId"])

    def _adopt_live(self, environment: str, artifact_name: str, artifact_filename: str) -> dict[str, Any]:
        if self.artifact_client is None:
            raise StateFailure("artifact_client_missing")
        live = self.kubectl.get_configmap("mnema-release")
        live_data = configmap_data(live)
        release_id = str(live_data.get("releaseId", ""))
        if not SHA_PATTERN.fullmatch(release_id):
            raise StateFailure("live_release_id_invalid")
        artifact = self.artifact_client.fetch(artifact_name, release_id, artifact_filename)
        record = build_record(
            artifact.manifest,
            environment=environment,
            deployed_at=artifact.created_at,
            workflow_run_id=None,
            adopted=True,
        )
        artifact_images = record["images"]
        for service in SERVICES:
            if live_data.get(f"{service}Image") != artifact_images[service]:
                raise StateFailure("live_release_artifact_mismatch")
        self.kubectl.persist(STATE_CURRENT, artifact.manifest, record)
        return {"data": {"manifest.yaml": artifact.manifest, "record.json": json.dumps(record)}}


def parse_manifest(manifest: str) -> tuple[str, dict[str, str]]:
    release_match = re.search(r'^\s*releaseId:\s*"([0-9a-f]{40})"\s*$', manifest, re.MULTILINE)
    if not release_match:
        raise StateFailure("manifest_release_id_missing")
    images: dict[str, str] = {}
    for service in SERVICES:
        match = re.search(
            rf'^\s*{service}Image:\s*"([a-z0-9./_-]+@sha256:[0-9a-f]{{64}})"\s*$',
            manifest,
            re.MULTILINE,
        )
        if not match or not DIGEST_REF_PATTERN.fullmatch(match.group(1)):
            raise StateFailure("manifest_image_missing")
        images[service] = match.group(1)
    return release_match.group(1), images


def build_record(
    manifest: str,
    *,
    environment: str,
    deployed_at: str | None,
    workflow_run_id: str | None,
    adopted: bool = False,
) -> dict[str, Any]:
    release_id, images = parse_manifest(manifest)
    return {
        "schemaVersion": 1,
        "releaseId": release_id,
        "environment": environment,
        "manifestSha256": hashlib.sha256(manifest.encode()).hexdigest(),
        "images": images,
        "deployedAt": deployed_at or utc_now(),
        "workflowRunId": workflow_run_id,
        "adopted": adopted,
        "knownRisks": [
            "Binary rollback is allowed only across forward-compatible expand/contract schema changes.",
            "A destructive schema migration requires roll-forward or verified data restore instead of binary rollback.",
        ],
    }


def validate_record(manifest: str, record: dict[str, Any]) -> None:
    release_id, images = parse_manifest(manifest)
    if record.get("schemaVersion") != 1:
        raise StateFailure("record_schema_invalid")
    if record.get("releaseId") != release_id:
        raise StateFailure("record_release_mismatch")
    if record.get("images") != images:
        raise StateFailure("record_images_mismatch")
    if record.get("manifestSha256") != hashlib.sha256(manifest.encode()).hexdigest():
        raise StateFailure("record_checksum_mismatch")
    if not isinstance(record.get("knownRisks"), list) or not record["knownRisks"]:
        raise StateFailure("record_risks_missing")


def create_broken_staging_manifest(manifest: str) -> tuple[str, str]:
    release_id, images = parse_manifest(manifest)
    frontend_image = images["frontend"]
    broken_release_id = "0" * 40
    broken_frontend_image = frontend_image.rsplit("@sha256:", 1)[0] + "@sha256:" + "0" * 64
    if manifest.count(frontend_image) < 2:
        raise StateFailure("broken_drill_frontend_image_incomplete")
    if manifest.count(release_id) < 2:
        raise StateFailure("broken_drill_release_id_incomplete")

    broken = manifest.replace(frontend_image, broken_frontend_image).replace(release_id, broken_release_id)
    parsed_release_id, parsed_images = parse_manifest(broken)
    if parsed_release_id != broken_release_id or parsed_images["frontend"] != broken_frontend_image:
        raise StateFailure("broken_drill_manifest_invalid")
    return broken, broken_release_id


def state_from_configmap(configmap: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    data = configmap_data(configmap)
    manifest = data.get("manifest.yaml")
    record_text = data.get("record.json")
    if not isinstance(manifest, str) or not isinstance(record_text, str):
        raise StateFailure("release_state_incomplete")
    try:
        record = json.loads(record_text)
    except json.JSONDecodeError:
        raise StateFailure("release_record_invalid") from None
    if not isinstance(record, dict):
        raise StateFailure("release_record_invalid")
    return manifest, record


def configmap_data(configmap: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(configmap, dict) or not isinstance(configmap.get("data"), dict):
        raise StateFailure("configmap_data_missing")
    return configmap["data"]


def read_record(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise StateFailure("release_record_invalid") from None
    if not isinstance(value, dict):
        raise StateFailure("release_record_invalid")
    return value


def parse_checksum_file(text: str, filename: str) -> str:
    match = re.fullmatch(rf"([0-9a-f]{{64}})\s+\*?{re.escape(filename)}\s*", text)
    if not match:
        raise StateFailure("artifact_checksum_file_invalid")
    return match.group(1)


def write_private(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)


def utc_now() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def emit_result(release_id: str, status: str) -> None:
    payload = {"releaseId": release_id, "status": status}
    print(json.dumps(payload))
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with Path(github_output).open("a", encoding="utf-8") as output:
            output.write(f"release_id={release_id}\n")
            output.write(f"state_status={status}\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=("build-record", "snapshot", "record", "rollback", "create-broken-staging"),
    )
    parser.add_argument("--namespace")
    parser.add_argument("--environment")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--record", type=Path)
    parser.add_argument("--rollback-manifest", type=Path)
    parser.add_argument("--rollback-record", type=Path)
    parser.add_argument("--artifact-name")
    parser.add_argument("--artifact-filename")
    parser.add_argument("--source-manifest", type=Path)
    parser.add_argument("--output-manifest", type=Path)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--workflow-run-id", default=os.environ.get("GITHUB_RUN_ID"))
    return parser.parse_args()


def required(value: Any, name: str) -> Any:
    if value is None or value == "":
        raise StateFailure(f"{name}_missing")
    return value


def main() -> int:
    try:
        args = parse_args()
        if args.command == "build-record":
            manifest_path = required(args.manifest, "manifest")
            record_path = required(args.record, "record")
            manifest = manifest_path.read_text(encoding="utf-8")
            record = build_record(
                manifest,
                environment=required(args.environment, "environment"),
                deployed_at=None,
                workflow_run_id=args.workflow_run_id,
            )
            write_private(record_path, json.dumps(record, indent=2, sort_keys=True) + "\n")
            emit_result(str(record["releaseId"]), "record_built")
            return 0

        if args.command == "create-broken-staging":
            source_path = required(args.source_manifest, "source_manifest")
            output_path = required(args.output_manifest, "output_manifest")
            broken, release_id = create_broken_staging_manifest(source_path.read_text(encoding="utf-8"))
            write_private(output_path, broken)
            emit_result(release_id, "broken_staging_manifest_built")
            return 0

        kubectl = Kubectl(required(args.namespace, "namespace"))
        artifact_client = None
        if args.command == "snapshot":
            artifact_client = GitHubArtifactClient(
                required(args.repository, "repository"),
                os.environ.get("GH_TOKEN", ""),
            )
        manager = ReleaseStateManager(kubectl, artifact_client)
        if args.command == "snapshot":
            release_id = manager.snapshot(
                environment=required(args.environment, "environment"),
                artifact_name=required(args.artifact_name, "artifact_name"),
                artifact_filename=required(args.artifact_filename, "artifact_filename"),
                rollback_manifest=required(args.rollback_manifest, "rollback_manifest"),
                rollback_record=required(args.rollback_record, "rollback_record"),
            )
            status = "snapshot_ready"
        elif args.command == "record":
            release_id = manager.record(
                required(args.manifest, "manifest"),
                required(args.record, "record"),
            )
            status = "release_recorded"
        else:
            release_id = manager.rollback(
                required(args.rollback_manifest, "rollback_manifest"),
                required(args.rollback_record, "rollback_record"),
            )
            status = "rollback_applied"
        emit_result(release_id, status)
        return 0
    except (StateFailure, OSError) as error:
        code = error.code if isinstance(error, StateFailure) else "filesystem_error"
        print(f"::error::release state operation failed code={code}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
