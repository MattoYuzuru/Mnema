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


LEGACY_SERVICES = ("frontend", "auth", "user", "core", "media", "import")
MAINTENANCE_SERVICES = ("identity-account", "learning")
SERVICES = LEGACY_SERVICES
ALL_SERVICES = (*LEGACY_SERVICES, *MAINTENANCE_SERVICES)
LEGACY_TOPOLOGY = "legacy-six-service"
MAINTENANCE_TOPOLOGY = "identity-learning"
MAINTENANCE_MODE = "maintenance"
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
DIGEST_REF_PATTERN = re.compile(r"^[a-z0-9./_-]+@sha256:[0-9a-f]{64}$")
STATE_CURRENT = "mnema-release-current"
STATE_PREVIOUS = "mnema-release-previous"
MAX_CONFIGMAP_PAYLOAD = 900_000
AUTHENTICATED_SMOKE_VERSION = 1
IDENTITY_SMOKE_VERSION = 0
READINESS_SMOKE_VERSION = -1
PREVIOUS_MAINTENANCE_SMOKE_VERSION = 1
MAINTENANCE_SMOKE_VERSION = 2
SUPPORTED_MAINTENANCE_SMOKE_VERSIONS = frozenset(
    (PREVIOUS_MAINTENANCE_SMOKE_VERSION, MAINTENANCE_SMOKE_VERSION)
)
SNAPSHOT_INGRESSES = ("mnema", "mnema-auth")
SNAPSHOT_AUGMENTATION_MARKER = "# mnema-release-state: captured-live-ingresses"
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


@dataclass(frozen=True)
class ManifestIdentity:
    release_id: str
    topology: str
    release_mode: str
    production_eligible: bool
    images: dict[str, str]

    @property
    def schema_version(self) -> int:
        return 2 if self.topology == MAINTENANCE_TOPOLOGY else 1

    @property
    def services(self) -> tuple[str, ...]:
        return services_for_topology(self.topology)


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
        command.extend(f"mnema-{service}" for service in ALL_SERVICES)
        command.extend(["--ignore-not-found=true", "-o", "name"])
        return bool(self._run(command).strip())

    def delete_applications(self, services: tuple[str, ...]) -> None:
        if self.namespace != "mnema-staging":
            raise StateFailure("transition_requires_staging_namespace")
        if any(service not in ALL_SERVICES for service in services):
            raise StateFailure("transition_service_not_allowlisted")
        for service in services:
            self._run(
                [
                    "kubectl",
                    "-n",
                    self.namespace,
                    "delete",
                    f"deployment/mnema-{service}",
                    f"service/mnema-{service}",
                    "--ignore-not-found=true",
                    "--cascade=foreground",
                    "--wait=true",
                    "--timeout=180s",
                ]
            )

    def rollout(self, services: tuple[str, ...]) -> None:
        if any(service not in ALL_SERVICES for service in services):
            raise StateFailure("rollout_service_not_allowlisted")
        for service in services:
            self._run(
                [
                    "kubectl",
                    "-n",
                    self.namespace,
                    "rollout",
                    "status",
                    f"deployment/mnema-{service}",
                    "--timeout=1200s",
                ]
            )

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
        manifest, record = ensure_rollback_ingresses(self.kubectl, manifest, record)
        validate_record(manifest, record)
        write_private(rollback_manifest, manifest)
        write_private(rollback_record, json.dumps(record, indent=2, sort_keys=True) + "\n")
        return str(record["releaseId"])

    def record(self, manifest_path: Path, record_path: Path) -> str:
        manifest = manifest_path.read_text(encoding="utf-8")
        record = {**read_record(record_path), "deployedAt": utc_now()}
        validate_record(manifest, record)
        if record.get("environment") in ("prod", "production") and record.get(
            "productionEligible"
        ) is False:
            raise StateFailure("release_not_production_eligible")
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

    def rollback(
        self,
        manifest_path: Path,
        record_path: Path,
        candidate_manifest_path: Path | None = None,
        transition_plan_path: Path | None = None,
    ) -> tuple[str, int, str, str, int | None]:
        manifest = manifest_path.read_text(encoding="utf-8")
        record = read_record(record_path)
        validate_record(manifest, record)
        transition_plan = None
        if candidate_manifest_path is None and transition_plan_path is not None:
            raise StateFailure("rollback_transition_inputs_incomplete")
        if candidate_manifest_path is not None:
            candidate_manifest = candidate_manifest_path.read_text(encoding="utf-8")
            transition_plan = build_transition_plan(candidate_manifest, manifest, self.kubectl.namespace)
            if transition_plan_path is not None:
                transition_plan = read_transition_plan(transition_plan_path)
            validate_transition_plan(
                transition_plan,
                candidate_manifest,
                manifest,
                self.kubectl.namespace,
            )
        self.kubectl.apply_manifest(manifest_path)
        if transition_plan is not None:
            self.kubectl.delete_applications(tuple(transition_plan["removeApplications"]))
        identity = parse_manifest_identity(manifest)
        return (
            str(record["releaseId"]),
            int(record.get("authenticatedSmokeVersion", READINESS_SMOKE_VERSION)),
            identity.topology,
            identity.release_mode,
            (
                int(record["maintenanceSmokeVersion"])
                if identity.topology == MAINTENANCE_TOPOLOGY
                else None
            ),
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


def document_resource_identity(document: str) -> tuple[str, str, str] | None:
    # Captured JSON may be followed by the inert augmentation marker before
    # the next YAML document separator. Full-line comments are not JSON data.
    document = "\n".join(line for line in document.splitlines() if not line.lstrip().startswith("#"))
    stripped = document.strip()
    if not stripped:
        return None
    if stripped.startswith("{"):
        try:
            value = json.loads(stripped)
        except json.JSONDecodeError:
            return None
        metadata = value.get("metadata") if isinstance(value, dict) else None
        if not isinstance(metadata, dict):
            return None
        kind = value.get("kind")
        name = metadata.get("name")
        namespace = metadata.get("namespace")
        if all(isinstance(item, str) and item for item in (kind, name, namespace)):
            return str(kind), str(name), str(namespace)
        return None

    kind_match = re.search(r"^kind:\s*([A-Za-z][A-Za-z0-9]*)\s*$", document, re.MULTILINE)
    metadata_match = re.search(
        r"^metadata:\s*$\n(?P<body>(?:^[ \t]+.*(?:\n|$))*)",
        document,
        re.MULTILINE,
    )
    if kind_match is None or metadata_match is None:
        return None
    body = metadata_match.group("body")
    name_match = re.search(r"^  name:\s*([a-z0-9]([-a-z0-9]*[a-z0-9])?)\s*$", body, re.MULTILINE)
    namespace_match = re.search(
        r"^  namespace:\s*([a-z0-9]([-a-z0-9]*[a-z0-9])?)\s*$",
        body,
        re.MULTILINE,
    )
    if name_match is None or namespace_match is None:
        return None
    return kind_match.group(1), name_match.group(1), namespace_match.group(1)


def resource_identities(manifest: str) -> set[tuple[str, str, str]]:
    identities: set[tuple[str, str, str]] = set()
    for document in re.split(r"^---\s*$", manifest, flags=re.MULTILINE):
        identity = document_resource_identity(document)
        if identity is not None:
            identities.add(identity)
    return identities


def manifest_ingress_route_pairs(
    manifest: str,
    namespace: str,
) -> dict[str, tuple[tuple[str, str], ...]]:
    """Read only the bounded path/backend pairs needed to bind rollback routes."""
    result: dict[str, tuple[tuple[str, str], ...]] = {}
    expected_names = set(SNAPSHOT_INGRESSES)
    for document in re.split(r"^---\s*$", manifest, flags=re.MULTILINE):
        identity = document_resource_identity(document)
        if (
            identity is None
            or identity[0] != "Ingress"
            or identity[1] not in expected_names
            or identity[2] != namespace
        ):
            continue
        name = identity[1]
        if name in result:
            raise StateFailure("rollback_ingress_manifest_duplicate")
        stripped = "\n".join(
            line for line in document.splitlines() if not line.lstrip().startswith("#")
        ).strip()
        if stripped.startswith("{"):
            try:
                resource = json.loads(stripped)
            except json.JSONDecodeError:
                raise StateFailure("rollback_ingress_manifest_invalid") from None
            pairs = route_pairs(resource)
        else:
            path_matches = list(
                re.finditer(
                    r"^[ \t]*-[ \t]+path:[ \t]*([^#\s]+)[ \t]*$",
                    document,
                    re.MULTILINE,
                )
            )
            pairs_list: list[tuple[str, str]] = []
            for index, match in enumerate(path_matches):
                end = path_matches[index + 1].start() if index + 1 < len(path_matches) else len(document)
                segment = document[match.end():end]
                services = re.findall(
                    r"^[ \t]+name:[ \t]+mnema-([a-z0-9-]+)[ \t]*$",
                    segment,
                    re.MULTILINE,
                )
                if len(services) != 1:
                    raise StateFailure("rollback_ingress_manifest_invalid")
                pairs_list.append((match.group(1), services[0]))
            pairs = tuple(pairs_list)
        if not pairs:
            raise StateFailure("rollback_ingress_manifest_invalid")
        result[name] = pairs
    return result


def ensure_rollback_ingresses(
    kubectl: Kubectl,
    manifest: str,
    record: dict[str, Any],
) -> tuple[str, dict[str, Any]]:
    # Production is deliberately frozen in #143; its prior operational format
    # is not migrated by this staging-only route-ownership change.
    if kubectl.namespace != "mnema-staging":
        return manifest, record
    topology = parse_manifest_identity(manifest).topology
    maintenance_smoke_version = (
        int(record["maintenanceSmokeVersion"])
        if topology == MAINTENANCE_TOPOLOGY
        else None
    )
    captured = [
        sanitize_live_resource(kubectl.get_resource("ingress", name))
        for name in SNAPSHOT_INGRESSES
    ]
    validate_staging_routes(captured, topology, maintenance_smoke_version)
    manifest_routes = manifest_ingress_route_pairs(manifest, kubectl.namespace)
    present = set(manifest_routes)
    expected = set(SNAPSHOT_INGRESSES)
    if present == expected:
        for name in SNAPSHOT_INGRESSES:
            if manifest_routes[name] != expected_staging_route_pairs(
                name,
                topology,
                maintenance_smoke_version,
            ):
                raise StateFailure("rollback_ingress_topology_mismatch")
        return manifest, record
    if present:
        raise StateFailure("rollback_ingress_manifest_incomplete")

    suffix = SNAPSHOT_AUGMENTATION_MARKER + "\n---\n"
    suffix += "---\n".join(
        json.dumps(resource, indent=2, sort_keys=True) + "\n" for resource in captured
    )
    separator = "" if manifest.endswith("\n") else "\n"
    augmented_manifest = manifest + separator + suffix
    source_record = copy.deepcopy(record)
    augmentation = {
        "schemaVersion": 1,
        "sourceManifestLength": len(manifest),
        "sourceRecord": source_record,
        "capturedResources": [f"Ingress/{name}" for name in SNAPSHOT_INGRESSES],
    }
    augmented_record = {
        **source_record,
        "manifestSha256": hashlib.sha256(augmented_manifest.encode()).hexdigest(),
        "snapshotAugmentation": augmentation,
    }
    return augmented_manifest, augmented_record


def validate_staging_routes(
    resources: list[dict[str, Any]],
    topology: str,
    maintenance_smoke_version: int | None,
) -> None:
    """Reject drift or mixed routes before blessing a captured rollback baseline."""
    for name, resource in zip(SNAPSHOT_INGRESSES, resources, strict=True):
        host = "staging.mnema.app" if name == "mnema" else "auth.staging.mnema.app"
        tls = "staging-mnema-app-tls" if name == "mnema" else "auth-staging-mnema-app-tls"
        if document_resource_identity(json.dumps(resource)) != ("Ingress", name, "mnema-staging"):
            raise StateFailure("rollback_ingress_identity_mismatch")
        annotations = resource.get("metadata", {}).get("annotations", {})
        if annotations != {"cert-manager.io/cluster-issuer": "letsencrypt-prod"}:
            raise StateFailure("rollback_ingress_annotations_invalid")
        paths = expected_staging_route_pairs(name, topology, maintenance_smoke_version)
        expected = {
            "ingressClassName": "traefik",
            "tls": [{"hosts": [host], "secretName": tls}],
            "rules": [{"host": host, "http": {"paths": [
                {"path": path, "pathType": "Prefix",
                 "backend": {"service": {"name": f"mnema-{service}", "port": {"number": 80}}}}
                for path, service in paths
            ]}}],
        }
        if resource.get("spec") != expected:
            raise StateFailure("rollback_ingress_topology_mismatch")


def route_pairs(resource: Any) -> tuple[tuple[str, str], ...]:
    try:
        rules = resource["spec"]["rules"]
        if not isinstance(rules, list) or len(rules) != 1:
            raise StateFailure("rollback_ingress_manifest_invalid")
        paths = rules[0]["http"]["paths"]
        result: list[tuple[str, str]] = []
        for path in paths:
            service_name = path["backend"]["service"]["name"]
            if not isinstance(service_name, str) or not service_name.startswith("mnema-"):
                raise StateFailure("rollback_ingress_manifest_invalid")
            result.append((str(path["path"]), service_name.removeprefix("mnema-")))
        return tuple(result)
    except (KeyError, TypeError, IndexError):
        raise StateFailure("rollback_ingress_manifest_invalid") from None


def expected_staging_route_pairs(
    name: str,
    topology: str,
    maintenance_smoke_version: int | None,
) -> tuple[tuple[str, str], ...]:
    if name not in SNAPSHOT_INGRESSES:
        raise StateFailure("rollback_ingress_identity_mismatch")
    if topology == MAINTENANCE_TOPOLOGY:
        if maintenance_smoke_version == PREVIOUS_MAINTENANCE_SMOKE_VERSION:
            return (("/api", "learning"),) if name == "mnema" else (
                ("/api", "identity-account"),
            )
        if maintenance_smoke_version == MAINTENANCE_SMOKE_VERSION:
            return (("/api", "learning"),) if name == "mnema" else (
                ("/", "identity-account"),
            )
        raise StateFailure("rollback_maintenance_smoke_unsupported")
    if topology == LEGACY_TOPOLOGY:
        if name == "mnema-auth":
            return (("/", "auth"),)
        return (
            ("/", "frontend"),
            ("/api/user", "user"),
            ("/api/core", "core"),
            ("/api/media", "media"),
            ("/api/import", "import"),
        )
    raise StateFailure("manifest_topology_invalid")


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


def services_for_topology(topology: str) -> tuple[str, ...]:
    if topology == LEGACY_TOPOLOGY:
        return LEGACY_SERVICES
    if topology == MAINTENANCE_TOPOLOGY:
        return MAINTENANCE_SERVICES
    raise StateFailure("manifest_topology_invalid")


def image_marker(service: str) -> str:
    if service == "identity-account":
        return "identityAccountImage"
    return f"{service}Image"


def unique_manifest_value(manifest: str, key: str, pattern: str, failure: str) -> str:
    matches = re.findall(rf'^\s*{re.escape(key)}:\s*"({pattern})"\s*$', manifest, re.MULTILINE)
    if len(matches) != 1:
        raise StateFailure(failure)
    return matches[0]


def parse_manifest_identity(manifest: str) -> ManifestIdentity:
    release_id = unique_manifest_value(
        manifest,
        "releaseId",
        r"[0-9a-f]{40}",
        "manifest_release_id_missing",
    )
    topology_matches = re.findall(
        r'^\s*releaseTopology:\s*"([a-z0-9-]+)"\s*$', manifest, re.MULTILINE
    )
    if not topology_matches:
        topology = LEGACY_TOPOLOGY
        release_mode = "legacy"
        production_eligible = True
    else:
        if len(topology_matches) != 1 or topology_matches[0] != MAINTENANCE_TOPOLOGY:
            raise StateFailure("manifest_topology_invalid")
        topology = topology_matches[0]
        release_mode = unique_manifest_value(
            manifest,
            "releaseMode",
            r"[a-z0-9-]+",
            "manifest_release_mode_invalid",
        )
        production_value = unique_manifest_value(
            manifest,
            "productionEligible",
            r"true|false",
            "manifest_production_eligibility_invalid",
        )
        production_eligible = production_value == "true"
        if release_mode != MAINTENANCE_MODE:
            raise StateFailure("manifest_release_mode_invalid")
        if production_eligible:
            raise StateFailure("manifest_maintenance_production_eligible")

    services = services_for_topology(topology)
    images: dict[str, str] = {}
    for service in services:
        image = unique_manifest_value(
            manifest,
            image_marker(service),
            r"[a-z0-9./_-]+@sha256:[0-9a-f]{64}",
            "manifest_image_missing",
        )
        if not DIGEST_REF_PATTERN.fullmatch(image):
            raise StateFailure("manifest_image_missing")
        images[service] = image

    excluded_services = set(ALL_SERVICES) - set(services)
    if any(
        re.search(rf"^\s*{re.escape(image_marker(service))}:\s*", manifest, re.MULTILINE)
        for service in excluded_services
    ):
        raise StateFailure("manifest_image_set_invalid")
    return ManifestIdentity(
        release_id,
        topology,
        release_mode,
        production_eligible,
        images,
    )


def parse_manifest(manifest: str) -> tuple[str, dict[str, str]]:
    identity = parse_manifest_identity(manifest)
    return identity.release_id, identity.images


def build_record(
    manifest: str,
    *,
    environment: str,
    deployed_at: str | None,
    workflow_run_id: str | None,
    adopted: bool = False,
) -> dict[str, Any]:
    identity = parse_manifest_identity(manifest)
    if environment in ("prod", "production") and not identity.production_eligible:
        raise StateFailure("release_not_production_eligible")
    record = {
        "schemaVersion": identity.schema_version,
        "releaseId": identity.release_id,
        "environment": environment,
        "manifestSha256": hashlib.sha256(manifest.encode()).hexdigest(),
        "images": identity.images,
        "deployedAt": deployed_at or utc_now(),
        "workflowRunId": workflow_run_id,
        "adopted": adopted,
        "knownRisks": [
            "Binary rollback is allowed only across forward-compatible expand/contract schema changes.",
            "A destructive schema migration requires roll-forward or verified data restore instead of binary rollback.",
        ],
    }
    if identity.schema_version == 1:
        record["authenticatedSmokeVersion"] = (
            IDENTITY_SMOKE_VERSION if adopted else AUTHENTICATED_SMOKE_VERSION
        )
    else:
        record.update(
            {
                "releaseTopology": identity.topology,
                "releaseMode": identity.release_mode,
                "productionEligible": identity.production_eligible,
                "maintenanceSmokeVersion": MAINTENANCE_SMOKE_VERSION,
            }
        )
    return record


def validate_record(manifest: str, record: dict[str, Any]) -> None:
    identity = parse_manifest_identity(manifest)
    if record.get("schemaVersion") != identity.schema_version:
        raise StateFailure("record_schema_invalid")
    if record.get("releaseId") != identity.release_id:
        raise StateFailure("record_release_mismatch")
    if record.get("images") != identity.images:
        raise StateFailure("record_images_mismatch")
    if record.get("manifestSha256") != hashlib.sha256(manifest.encode()).hexdigest():
        raise StateFailure("record_checksum_mismatch")
    if not isinstance(record.get("knownRisks"), list) or not record["knownRisks"]:
        raise StateFailure("record_risks_missing")
    if identity.schema_version == 1:
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
    else:
        maintenance_smoke_version = record.get("maintenanceSmokeVersion")
        expected = {
            "releaseTopology": identity.topology,
            "releaseMode": identity.release_mode,
            "productionEligible": identity.production_eligible,
        }
        if (
            any(record.get(key) != value for key, value in expected.items())
            or type(maintenance_smoke_version) is not int
            or maintenance_smoke_version not in SUPPORTED_MAINTENANCE_SMOKE_VERSIONS
        ):
            raise StateFailure("record_maintenance_contract_invalid")
        if "authenticatedSmokeVersion" in record:
            raise StateFailure("record_maintenance_auth_smoke_invalid")

    augmentation = record.get("snapshotAugmentation")
    if augmentation is not None:
        validate_snapshot_augmentation(manifest, record, augmentation)


def validate_snapshot_augmentation(
    manifest: str,
    record: dict[str, Any],
    augmentation: Any,
) -> None:
    expected_fields = {
        "schemaVersion",
        "sourceManifestLength",
        "sourceRecord",
        "capturedResources",
    }
    if not isinstance(augmentation, dict) or set(augmentation) != expected_fields:
        raise StateFailure("snapshot_augmentation_invalid")
    source_length = augmentation.get("sourceManifestLength")
    source_record = augmentation.get("sourceRecord")
    if (
        augmentation.get("schemaVersion") != 1
        or not isinstance(source_length, int)
        or source_length <= 0
        or source_length >= len(manifest)
        or not isinstance(source_record, dict)
        or augmentation.get("capturedResources")
        != [f"Ingress/{name}" for name in SNAPSHOT_INGRESSES]
    ):
        raise StateFailure("snapshot_augmentation_invalid")
    source_manifest = manifest[:source_length]
    suffix = manifest[source_length:]
    if suffix.count(SNAPSHOT_AUGMENTATION_MARKER) != 1:
        raise StateFailure("snapshot_augmentation_marker_invalid")
    validate_record(source_manifest, source_record)
    suffix_resources = resource_identities(suffix)
    expected_resources = {
        ("Ingress", name, identity_namespace(manifest)) for name in SNAPSHOT_INGRESSES
    }
    if suffix_resources != expected_resources:
        raise StateFailure("snapshot_augmentation_resources_invalid")
    expected_record = {
        **source_record,
        "manifestSha256": hashlib.sha256(manifest.encode()).hexdigest(),
        "snapshotAugmentation": augmentation,
    }
    if record != expected_record:
        raise StateFailure("snapshot_augmentation_record_invalid")


def identity_namespace(manifest: str) -> str:
    first_document = re.split(r"^---\s*$", manifest, maxsplit=1, flags=re.MULTILINE)[0]
    identity = document_resource_identity(first_document)
    if identity is None or identity[:2] != ("ConfigMap", "mnema-release"):
        raise StateFailure("manifest_namespace_missing")
    return identity[2]


def transition_endpoint(manifest: str | None) -> dict[str, Any]:
    if manifest is None:
        return {
            "releaseId": None,
            "topology": "empty",
            "manifestSha256": None,
            "applications": [],
        }
    identity = parse_manifest_identity(manifest)
    namespace = identity_namespace(manifest)
    documents = [doc for doc in re.split(r"^---\s*$", manifest, flags=re.MULTILINE) if doc.strip()]
    resources = [document_resource_identity(document) for document in documents]
    expected = {("ConfigMap", "mnema-release", namespace)}
    expected.update(("Ingress", name, namespace) for name in SNAPSHOT_INGRESSES)
    expected.update(
        (kind, f"mnema-{service}", namespace)
        for service in identity.services for kind in ("Deployment", "Service")
    )
    if len(resources) != len(expected) or set(resources) != expected:
        raise StateFailure("transition_resource_inventory_invalid")
    return {
        "releaseId": identity.release_id,
        "topology": identity.topology,
        "manifestSha256": hashlib.sha256(manifest.encode()).hexdigest(),
        "applications": list(identity.services),
    }


def build_transition_plan(
    source_manifest: str | None,
    target_manifest: str,
    namespace: str,
) -> dict[str, Any]:
    if namespace != "mnema-staging":
        raise StateFailure("transition_requires_staging_namespace")
    source = transition_endpoint(source_manifest)
    target = transition_endpoint(target_manifest)
    if source_manifest is not None and identity_namespace(source_manifest) != namespace:
        raise StateFailure("transition_source_namespace_mismatch")
    if identity_namespace(target_manifest) != namespace:
        raise StateFailure("transition_target_namespace_mismatch")
    source_apps = tuple(str(item) for item in source["applications"])
    target_apps = tuple(str(item) for item in target["applications"])
    remove_applications = [item for item in source_apps if item not in target_apps]
    add_applications = [item for item in target_apps if item not in source_apps]
    remove_resources = [
        {"kind": kind, "name": f"mnema-{service}", "namespace": namespace}
        for service in remove_applications
        for kind in ("Deployment", "Service")
    ]
    return {
        "schemaVersion": 1,
        "namespace": namespace,
        "source": source,
        "target": target,
        "removeApplications": remove_applications,
        "addApplications": add_applications,
        "removeResources": remove_resources,
        "protectedResources": [
            "Secret/*",
            "PersistentVolume/*",
            "PersistentVolumeClaim/*",
            "StatefulSet/*",
            "Ingress/mnema",
            "Ingress/mnema-auth",
        ],
    }


def read_transition_plan(path: Path) -> dict[str, Any]:
    try:
        plan = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise StateFailure("transition_plan_invalid") from None
    if not isinstance(plan, dict):
        raise StateFailure("transition_plan_invalid")
    return plan


def validate_transition_plan(
    plan: dict[str, Any],
    source_manifest: str | None,
    target_manifest: str,
    namespace: str,
) -> None:
    expected = build_transition_plan(source_manifest, target_manifest, namespace)
    if plan != expected:
        raise StateFailure("transition_plan_mismatch")


def write_transition_plan(
    path: Path,
    source_manifest: str | None,
    target_manifest: str,
    namespace: str,
) -> dict[str, Any]:
    plan = build_transition_plan(source_manifest, target_manifest, namespace)
    write_private(path, json.dumps(plan, indent=2, sort_keys=True) + "\n")
    return plan


def create_broken_staging_manifest(manifest: str) -> tuple[str, str]:
    identity = parse_manifest_identity(manifest)
    release_id = identity.release_id
    service = identity.services[0]
    application_image = identity.images[service]
    broken_release_id = "0" * 40
    broken_application_image = (
        application_image.rsplit("@sha256:", 1)[0] + "@sha256:" + "0" * 64
    )
    if manifest.count(application_image) < 2:
        raise StateFailure("broken_drill_application_image_incomplete")
    if manifest.count(release_id) < 2:
        raise StateFailure("broken_drill_release_id_incomplete")

    broken = manifest.replace(application_image, broken_application_image).replace(
        release_id, broken_release_id
    )
    parsed_release_id, parsed_images = parse_manifest(broken)
    if parsed_release_id != broken_release_id or parsed_images[service] != broken_application_image:
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


def record_identity(record: dict[str, Any]) -> tuple[str, str, tuple[str, ...]]:
    schema_version = record.get("schemaVersion")
    if schema_version == 1:
        topology = LEGACY_TOPOLOGY
        release_mode = "legacy"
    elif schema_version == 2:
        topology = record.get("releaseTopology")
        release_mode = record.get("releaseMode")
        maintenance_smoke_version = record.get("maintenanceSmokeVersion")
        if (
            topology != MAINTENANCE_TOPOLOGY
            or release_mode != MAINTENANCE_MODE
            or record.get("productionEligible") is not False
            or type(maintenance_smoke_version) is not int
            or maintenance_smoke_version not in SUPPORTED_MAINTENANCE_SMOKE_VERSIONS
        ):
            raise StateFailure("record_maintenance_contract_invalid")
    else:
        raise StateFailure("record_schema_invalid")
    services = services_for_topology(str(topology))
    images = record.get("images")
    if not isinstance(images, dict) or set(images) != set(services):
        raise StateFailure("record_images_mismatch")
    if any(
        not isinstance(images[service], str)
        or not DIGEST_REF_PATTERN.fullmatch(images[service])
        for service in services
    ):
        raise StateFailure("record_images_mismatch")
    if not SHA_PATTERN.fullmatch(str(record.get("releaseId", ""))):
        raise StateFailure("record_release_mismatch")
    return str(topology), str(release_mode), services


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
    release_topology: str | None = None,
    release_mode: str | None = None,
    maintenance_smoke_supported: bool | None = None,
) -> None:
    payload = {"releaseId": release_id, "status": status}
    if authenticated_smoke_supported is not None:
        payload["authenticatedSmokeSupported"] = authenticated_smoke_supported
    if identity_smoke_supported is not None:
        payload["identitySmokeSupported"] = identity_smoke_supported
    if release_topology is not None:
        payload["releaseTopology"] = release_topology
    if release_mode is not None:
        payload["releaseMode"] = release_mode
    if maintenance_smoke_supported is not None:
        payload["maintenanceSmokeSupported"] = maintenance_smoke_supported
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
            if release_topology is not None:
                output.write(f"release_topology={release_topology}\n")
            if release_mode is not None:
                output.write(f"release_mode={release_mode}\n")
            if maintenance_smoke_supported is not None:
                output.write(
                    "maintenance_smoke_supported="
                    f"{'true' if maintenance_smoke_supported else 'false'}\n"
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
            "plan-transition",
            "apply-removals",
            "check-production-eligible",
            "rollout",
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
    parser.add_argument("--target-manifest", type=Path)
    parser.add_argument("--candidate-manifest", type=Path)
    parser.add_argument("--transition-plan", type=Path)
    parser.add_argument("--plan", type=Path)
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

        if args.command == "check-production-eligible":
            manifest = required(args.manifest, "manifest").read_text(encoding="utf-8")
            if not parse_manifest_identity(manifest).production_eligible:
                raise StateFailure("release_not_production_eligible")
            emit_result(parse_manifest_identity(manifest).release_id, "production_eligible")
            return 0

        if args.command in ("plan-transition", "apply-removals"):
            target = required(args.target_manifest, "target_manifest").read_text(encoding="utf-8")
            source = args.source_manifest.read_text(encoding="utf-8") if args.source_manifest else None
            namespace = args.namespace or identity_namespace(target)
            plan_path = required(args.plan, "plan")
            if args.command == "plan-transition":
                plan = write_transition_plan(plan_path, source, target, namespace)
                print(json.dumps(plan, sort_keys=True))
            else:
                plan = read_transition_plan(plan_path)
                validate_transition_plan(plan, source, target, namespace)
                Kubectl(namespace).delete_applications(tuple(plan["removeApplications"]))
                emit_result(parse_manifest_identity(target).release_id, "removals_applied")
            return 0

        if args.command == "rollout":
            record = read_record(required(args.record, "record"))
            _, _, services = record_identity(record)
            Kubectl(required(args.namespace, "namespace")).rollout(services)
            emit_result(record["releaseId"], "rollouts_ready")
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
        elif args.command == "rollback":
            (
                release_id,
                smoke_version,
                topology,
                release_mode,
                maintenance_smoke_version,
            ) = manager.rollback(
                required(args.rollback_manifest, "rollback_manifest"),
                required(args.rollback_record, "rollback_record"),
                args.candidate_manifest,
                args.transition_plan,
            )
            status = "rollback_applied"
            emit_result(
                release_id,
                status,
                smoke_version >= AUTHENTICATED_SMOKE_VERSION,
                smoke_version >= IDENTITY_SMOKE_VERSION,
                topology,
                release_mode,
                maintenance_smoke_version == MAINTENANCE_SMOKE_VERSION,
            )
            return 0
        else:
            raise StateFailure("command_not_implemented")
        emit_result(release_id, status)
        return 0
    except (StateFailure, OSError) as error:
        code = error.code if isinstance(error, StateFailure) else "filesystem_error"
        print(f"::error::release state operation failed code={code}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
