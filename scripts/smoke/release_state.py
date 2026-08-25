#!/usr/bin/env python3
"""Persist, adopt and apply complete Mnema release manifests without secret data."""

from __future__ import annotations

import argparse
import copy
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
AUTHENTICATED_SMOKE_VERSION = 1
IDENTITY_SMOKE_VERSION = 0
READINESS_SMOKE_VERSION = -1
LIVE_RESOURCE_ANNOTATIONS_TO_DROP = {
    "deployment.kubernetes.io/revision",
    "kubectl.kubernetes.io/last-applied-configuration",
}
SERVICE_CLUSTER_FIELDS = {
    "clusterIP",
    "clusterIPs",
    "healthCheckNodePort",
    "ipFamilies",
    "ipFamilyPolicy",
}


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

    def create_state(self, name: str, manifest: str, record: dict[str, Any]) -> None:
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
            self._run(["kubectl", "create", "-f", "-"], input_text=rendered)

    def apply_manifest(self, manifest_path: Path) -> None:
        self._run(["kubectl", "apply", "--dry-run=server", "-f", str(manifest_path)])
        self._run(["kubectl", "apply", "-f", str(manifest_path)])

    def has_application_deployments(self) -> bool:
        command = ["kubectl", "-n", self.namespace, "get", "deployment"]
        command.extend(f"mnema-{service}" for service in SERVICES)
        command.extend(["--ignore-not-found=true", "-o", "name"])
        return bool(self._run(command).strip())

    def get_resource(self, kind: str, name: str) -> dict[str, Any]:
        output = self._run(["kubectl", "-n", self.namespace, "get", kind, name, "-o", "json"])
        try:
            value = json.loads(output)
        except json.JSONDecodeError:
            raise StateFailure("live_resource_json_invalid") from None
        if not isinstance(value, dict):
            raise StateFailure("live_resource_json_invalid")
        return value

    def get_pods(self, selector: str) -> list[dict[str, Any]]:
        output = self._run(
            ["kubectl", "-n", self.namespace, "get", "pods", "-l", selector, "-o", "json"]
        )
        try:
            value = json.loads(output)
        except json.JSONDecodeError:
            raise StateFailure("live_pod_json_invalid") from None
        items = value.get("items") if isinstance(value, dict) else None
        if not isinstance(items, list) or any(not isinstance(item, dict) for item in items):
            raise StateFailure("live_pod_json_invalid")
        return items

    def dry_run_manifest(self, manifest_path: Path) -> None:
        self._run(["kubectl", "apply", "--dry-run=server", "-f", str(manifest_path)])

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
        allow_empty: bool = False,
    ) -> str | None:
        current = self.kubectl.get_configmap(STATE_CURRENT, required=False)
        if current is None:
            current = self._adopt_live(
                environment,
                artifact_name,
                artifact_filename,
                allow_empty=allow_empty,
            )
        if current is None:
            return None
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

    def seed_live(self, manifest_path: Path, record_path: Path) -> str:
        manifest = manifest_path.read_text(encoding="utf-8")
        record = read_record(record_path)
        validate_record(manifest, record)
        if (
            record.get("environment") != "production"
            or record.get("adopted") is not True
            or record.get("authenticatedSmokeVersion") != READINESS_SMOKE_VERSION
            or record.get("legacyReadinessOnly") is not True
        ):
            raise StateFailure("live_seed_record_invalid")
        if any(
            self.kubectl.get_configmap(name, required=False) is not None
            for name in (STATE_CURRENT, STATE_PREVIOUS, "mnema-release")
        ):
            raise StateFailure("live_seed_already_initialized")
        self.kubectl.create_state(STATE_CURRENT, manifest, record)
        return str(record["releaseId"])

    def rollback(self, manifest_path: Path, record_path: Path) -> tuple[str, int]:
        manifest = manifest_path.read_text(encoding="utf-8")
        record = read_record(record_path)
        validate_record(manifest, record)
        self.kubectl.apply_manifest(manifest_path)
        return (
            str(record["releaseId"]),
            int(record["authenticatedSmokeVersion"]),
        )

    def _adopt_live(
        self,
        environment: str,
        artifact_name: str,
        artifact_filename: str,
        *,
        allow_empty: bool,
    ) -> dict[str, Any] | None:
        if self.artifact_client is None:
            raise StateFailure("artifact_client_missing")
        live = self.kubectl.get_configmap("mnema-release", required=False)
        if live is None:
            if allow_empty and not self.kubectl.has_application_deployments():
                return None
            raise StateFailure("live_release_state_missing")
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
        return {"data": {"manifest.yaml": artifact.manifest, "record.json": json.dumps(record)}}


class LiveReleaseCapture:
    """Build a secret-free rollback baseline for a pre-release-state production deployment."""

    def __init__(self, kubectl: Kubectl) -> None:
        self.kubectl = kubectl

    def capture(self, release_id: str, environment: str) -> tuple[str, dict[str, Any]]:
        if (
            environment != "production"
            or self.kubectl.namespace != "prod"
            or not SHA_PATTERN.fullmatch(release_id)
        ):
            raise StateFailure("live_capture_identity_invalid")
        if (
            self.kubectl.get_configmap(STATE_CURRENT, required=False) is not None
            or self.kubectl.get_configmap(STATE_PREVIOUS, required=False) is not None
            or self.kubectl.get_configmap("mnema-release", required=False) is not None
        ):
            raise StateFailure("live_capture_already_initialized")

        resources: list[dict[str, Any]] = []
        images: dict[str, str] = {}
        for service in SERVICES:
            name = f"mnema-{service}"
            deployment = self.kubectl.get_resource("deployment", name)
            pods = self.kubectl.get_pods(f"app={name}")
            captured, image = capture_deployment(deployment, pods, service, release_id)
            resources.append(captured)
            images[service] = image
            resources.append(sanitize_live_resource(self.kubectl.get_resource("service", name)))

        for ingress in ("mnema", "mnema-auth"):
            resources.append(sanitize_live_resource(self.kubectl.get_resource("ingress", ingress)))

        manifest = render_live_manifest(release_id, images, resources, self.kubectl.namespace)
        record = build_record(
            manifest,
            environment=environment,
            deployed_at=utc_now(),
            workflow_run_id=None,
            adopted=True,
        )
        record = {
            **record,
            "authenticatedSmokeVersion": READINESS_SMOKE_VERSION,
            "legacyReadinessOnly": True,
        }
        validate_record(manifest, record)
        return manifest, record


def capture_deployment(
    deployment: dict[str, Any],
    pods: list[dict[str, Any]],
    service: str,
    release_id: str,
) -> tuple[dict[str, Any], str]:
    name = f"mnema-{service}"
    metadata = deployment.get("metadata", {})
    spec = deployment.get("spec", {})
    status = deployment.get("status", {})
    replicas = spec.get("replicas", 1)
    if (
        metadata.get("name") != name
        or not isinstance(replicas, int)
        or replicas < 1
        or status.get("observedGeneration") != metadata.get("generation")
        or status.get("updatedReplicas") != replicas
        or status.get("readyReplicas") != replicas
        or status.get("availableReplicas") != replicas
        or status.get("unavailableReplicas", 0) not in (0, None)
    ):
        raise StateFailure("live_deployment_not_stable")

    active_pods = [pod for pod in pods if not pod.get("metadata", {}).get("deletionTimestamp")]
    if len(active_pods) != replicas or any(not pod_is_ready(pod) for pod in active_pods):
        raise StateFailure("live_deployment_pods_not_stable")

    captured = sanitize_live_resource(deployment)
    template_spec = captured.get("spec", {}).get("template", {}).get("spec", {})
    app_image = ""
    for spec_key, status_key in (
        ("initContainers", "initContainerStatuses"),
        ("containers", "containerStatuses"),
    ):
        containers = template_spec.get(spec_key, [])
        if not isinstance(containers, list):
            raise StateFailure("live_deployment_containers_invalid")
        for container in containers:
            if not isinstance(container, dict) or not isinstance(container.get("name"), str):
                raise StateFailure("live_deployment_containers_invalid")
            original_image = container.get("image")
            runtime_image = one_runtime_image(active_pods, status_key, container["name"])
            if not isinstance(original_image, str) or not image_repositories_match(
                original_image, runtime_image
            ):
                raise StateFailure("live_runtime_image_repository_mismatch")
            container["image"] = runtime_image
            if spec_key == "containers" and container["name"] == service:
                assert_release_identity(original_image, container, release_id, runtime_image)
                app_image = runtime_image
    if not app_image:
        raise StateFailure("live_application_container_missing")
    return captured, app_image


def pod_is_ready(pod: dict[str, Any]) -> bool:
    status = pod.get("status", {})
    conditions = status.get("conditions", [])
    return status.get("phase") == "Running" and any(
        isinstance(condition, dict)
        and condition.get("type") == "Ready"
        and condition.get("status") == "True"
        for condition in conditions
    )


def one_runtime_image(pods: list[dict[str, Any]], status_key: str, container_name: str) -> str:
    images: set[str] = set()
    for pod in pods:
        statuses = pod.get("status", {}).get(status_key, [])
        matches = [item for item in statuses if isinstance(item, dict) and item.get("name") == container_name]
        if len(matches) != 1 or not matches[0].get("imageID"):
            raise StateFailure("live_runtime_image_missing")
        image = re.sub(r"^[a-z0-9+.-]+://", "", str(matches[0]["imageID"]))
        if not DIGEST_REF_PATTERN.fullmatch(image):
            raise StateFailure("live_runtime_image_not_immutable")
        images.add(image)
    if len(images) != 1:
        raise StateFailure("live_runtime_image_mixed")
    return next(iter(images))


def image_repository(image: str) -> str:
    without_digest = image.split("@", 1)[0]
    slash = without_digest.rfind("/")
    colon = without_digest.rfind(":")
    return without_digest[:colon] if colon > slash else without_digest


def image_repositories_match(original_image: str, runtime_image: str) -> bool:
    original = image_repository(original_image)
    runtime = image_repository(runtime_image)
    return original == runtime or ("/" not in original and runtime.endswith(f"/{original}"))


def assert_release_identity(
    original_image: str,
    container: dict[str, Any],
    release_id: str,
    runtime_image: str,
) -> None:
    tag = re.search(r":sha-([0-9a-f]{7,40})$", original_image)
    if tag is not None:
        if not release_id.startswith(tag.group(1)):
            raise StateFailure("live_release_tag_mismatch")
        return
    if original_image != runtime_image:
        raise StateFailure("live_release_identity_missing")
    env = container.get("env", [])
    build_ids = [
        item.get("value")
        for item in env
        if isinstance(item, dict) and item.get("name") == "MNEMA_BUILD_ID"
    ]
    if build_ids != [release_id]:
        raise StateFailure("live_release_identity_missing")


def sanitize_live_resource(resource: dict[str, Any]) -> dict[str, Any]:
    value = copy.deepcopy(resource)
    metadata = value.get("metadata")
    if not isinstance(metadata, dict) or not metadata.get("name") or not metadata.get("namespace"):
        raise StateFailure("live_resource_identity_invalid")
    for key in ("creationTimestamp", "generation", "managedFields", "resourceVersion", "selfLink", "uid"):
        metadata.pop(key, None)
    annotations = metadata.get("annotations")
    if isinstance(annotations, dict):
        for key in LIVE_RESOURCE_ANNOTATIONS_TO_DROP:
            annotations.pop(key, None)
        if not annotations:
            metadata.pop("annotations", None)
    value.pop("status", None)
    if value.get("kind") == "Service":
        service_spec = value.get("spec", {})
        for key in SERVICE_CLUSTER_FIELDS:
            service_spec.pop(key, None)
    return value


def render_live_manifest(
    release_id: str,
    images: dict[str, str],
    resources: list[dict[str, Any]],
    namespace: str,
) -> str:
    if set(images) != set(SERVICES):
        raise StateFailure("live_release_images_incomplete")
    lines = [
        "apiVersion: v1",
        "kind: ConfigMap",
        "metadata:",
        "  name: mnema-release",
        f"  namespace: {namespace}",
        "data:",
        f'  releaseId: "{release_id}"',
    ]
    for service in SERVICES:
        lines.append(f'  {service}Image: "{images[service]}"')
    documents = ["\n".join(lines) + "\n"]
    documents.extend(json.dumps(resource, indent=2, sort_keys=True) + "\n" for resource in resources)
    manifest = "---\n".join(documents)
    if len(manifest.encode()) > MAX_CONFIGMAP_PAYLOAD:
        raise StateFailure("release_state_too_large")
    return manifest


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
        "authenticatedSmokeVersion": 0 if adopted else AUTHENTICATED_SMOKE_VERSION,
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
    smoke_version = record.get("authenticatedSmokeVersion")
    if smoke_version not in (
        READINESS_SMOKE_VERSION,
        IDENTITY_SMOKE_VERSION,
        AUTHENTICATED_SMOKE_VERSION,
    ):
        raise StateFailure("record_smoke_capability_invalid")
    if smoke_version < AUTHENTICATED_SMOKE_VERSION and record.get("adopted") is not True:
        raise StateFailure("record_smoke_capability_downgrade")
    if smoke_version == READINESS_SMOKE_VERSION and record.get("legacyReadinessOnly") is not True:
        raise StateFailure("record_readiness_capability_invalid")


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


def emit_result(
    release_id: str,
    status: str,
    authenticated_smoke_supported: bool | None = None,
    identity_smoke_supported: bool | None = None,
) -> None:
    payload = {"releaseId": release_id, "status": status}
    if authenticated_smoke_supported is not None:
        payload["authenticatedSmokeSupported"] = authenticated_smoke_supported
    if identity_smoke_supported is not None:
        payload["identitySmokeSupported"] = identity_smoke_supported
    print(json.dumps(payload))
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with Path(github_output).open("a", encoding="utf-8") as output:
            output.write(f"release_id={release_id}\n")
            output.write(f"state_status={status}\n")
            if authenticated_smoke_supported is not None:
                output.write(
                    "authenticated_smoke_supported="
                    f"{'true' if authenticated_smoke_supported else 'false'}\n"
                )
            if identity_smoke_supported is not None:
                output.write(
                    "identity_smoke_supported="
                    f"{'true' if identity_smoke_supported else 'false'}\n"
                )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=(
            "build-record",
            "capture-live",
            "seed-live",
            "snapshot",
            "record",
            "rollback",
            "create-broken-staging",
        ),
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
    parser.add_argument("--release-id")
    parser.add_argument("--allow-empty", action="store_true")
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

        if args.command == "capture-live":
            manifest_path = required(args.manifest, "manifest")
            record_path = required(args.record, "record")
            kubectl = Kubectl(required(args.namespace, "namespace"))
            manifest, record = LiveReleaseCapture(kubectl).capture(
                required(args.release_id, "release_id"),
                required(args.environment, "environment"),
            )
            write_private(manifest_path, manifest)
            write_private(record_path, json.dumps(record, indent=2, sort_keys=True) + "\n")
            kubectl.dry_run_manifest(manifest_path)
            emit_result(str(record["releaseId"]), "live_capture_ready", False)
            return 0

        kubectl = Kubectl(required(args.namespace, "namespace"))
        artifact_client = None
        if args.command == "seed-live":
            manager = ReleaseStateManager(kubectl)
            release_id = manager.seed_live(
                required(args.manifest, "manifest"),
                required(args.record, "record"),
            )
            emit_result(release_id, "live_state_seeded", False, False)
            return 0
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
                allow_empty=args.allow_empty,
            )
            if release_id is None:
                print(json.dumps({"releaseId": None, "status": "no_previous_release"}))
                github_output = os.environ.get("GITHUB_OUTPUT")
                if github_output:
                    with Path(github_output).open("a", encoding="utf-8") as output:
                        output.write("previous_available=false\n")
                        output.write("state_status=no_previous_release\n")
                return 0
            github_output = os.environ.get("GITHUB_OUTPUT")
            if github_output:
                with Path(github_output).open("a", encoding="utf-8") as output:
                    output.write("previous_available=true\n")
            status = "snapshot_ready"
        elif args.command == "record":
            release_id = manager.record(
                required(args.manifest, "manifest"),
                required(args.record, "record"),
            )
            status = "release_recorded"
        else:
            release_id, smoke_version = manager.rollback(
                required(args.rollback_manifest, "rollback_manifest"),
                required(args.rollback_record, "rollback_record"),
            )
            status = "rollback_applied"
            emit_result(
                release_id,
                status,
                smoke_version >= AUTHENTICATED_SMOKE_VERSION,
                smoke_version >= IDENTITY_SMOKE_VERSION,
            )
            return 0
        emit_result(release_id, status)
        return 0
    except (StateFailure, OSError) as error:
        code = error.code if isinstance(error, StateFailure) else "filesystem_error"
        print(f"::error::release state operation failed code={code}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
