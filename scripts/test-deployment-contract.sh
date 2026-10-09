#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
MAIN_WORKFLOW="$REPO_ROOT/.github/workflows/deploy.yaml"
PULL_REQUEST_WORKFLOW="$REPO_ROOT/.github/workflows/pull-request.yaml"
VPS_WORKFLOW="$REPO_ROOT/.github/workflows/vps-deploy.yaml"

# The only delivery path is Main CI plus the manual VPS operations workflow.
for workflow in "$REPO_ROOT"/.github/workflows/*.yaml; do
  case "$(basename "$workflow")" in
    deploy.yaml | pull-request.yaml | vps-deploy.yaml | dependency-review.yaml) ;;
    *)
      echo "Unexpected workflow $(basename "$workflow"); review the delivery boundary" >&2
      exit 1
      ;;
  esac
done

grep -Fq 'workflow_dispatch:' "$MAIN_WORKFLOW"
grep -Fq 'name: Require exact main branch' "$MAIN_WORKFLOW"
test "$(grep -c 'needs: validate-main-ref' "$MAIN_WORKFLOW")" -eq 3
for quality_workflow in "$MAIN_WORKFLOW" "$PULL_REQUEST_WORKFLOW"; do
  grep -Fq 'name: Verify Identity to Learning black-box security' "$quality_workflow"
  grep -Fq './backend/gradlew -p backend :services:identity-account:bootJar :services:learning:bootJar' "$quality_workflow"
  grep -Fq 'python3 scripts/learning-security/run.py' "$quality_workflow"
  grep -Fq 'MNEMA_RUN_CANCELLATION_INTEGRATION=1 python3 -m unittest discover -s scripts/learning-security/tests -v' "$quality_workflow"
  grep -Fq 'python3 scripts/learning-security/verify_cancellation.py' "$quality_workflow"
done

if grep -Eq '^    uses:' "$MAIN_WORKFLOW" "$VPS_WORKFLOW"; then
  echo "Delivery workflows must not call other workflows" >&2
  exit 1
fi
if grep -Fq 'secrets: inherit' "$MAIN_WORKFLOW" "$VPS_WORKFLOW"; then
  echo "Deployment workflows must not inherit repository secrets" >&2
  exit 1
fi

# Promo codes and A/B (#302): production Learning must receive its secrets and the popup copy from the private runtime environment.
for value in \
  MNEMA_PROMO_HASH_SECRET MNEMA_EXPERIMENT_SECRET MNEMA_PROMO_POPUP_ENABLED MNEMA_PROMO_POPUP_ID \
  MNEMA_PROMO_POPUP_TITLE MNEMA_PROMO_POPUP_BODY MNEMA_PROMO_POPUP_CTA MNEMA_PROMO_POPUP_CODE MNEMA_PROMO_POPUP_COOLDOWN
do
  grep -Fq "$value: \${$value:-" "$REPO_ROOT/deploy/production/compose.yaml"
done

# Identity must receive its fixed origin, signing key reference, trusted proxies, OAuth client ids and avatar bucket from the production Compose file.
for value in \
  MNEMA_IDENTITY_FRONTEND_ORIGIN MNEMA_IDENTITY_REDIRECT_URI MNEMA_IDENTITY_TRUSTED_PROXY_CIDRS \
  MNEMA_IDENTITY_SIGNING_JWK_SET_FILE MNEMA_IDENTITY_SIGNING_ACTIVE_KID
do
  test "$(grep -c "^      $value: ." "$REPO_ROOT/deploy/production/compose.yaml")" -ge 1
done
grep -Fq 'MNEMA_IDENTITY_SIGNING_JWK_SET_FILE: /run/secrets/identity-signing.json' \
  "$REPO_ROOT/deploy/production/compose.yaml"
for provider in GOOGLE YANDEX GITHUB; do
  case "$provider" in
    GITHUB) variable=GH_CLIENT_ID ;;
    *) variable="${provider}_CLIENT_ID" ;;
  esac
  grep -Fq "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_${provider}_CLIENT_ID: \${$variable:-}" \
    "$REPO_ROOT/deploy/production/compose.yaml"
done
grep -Fq 'MNEMA_AVATAR_BUCKET: ${MNEMA_AVATAR_BUCKET:-' "$REPO_ROOT/deploy/production/compose.yaml"
if grep -R -E 'UserApiClient|USER_BASE_URL|app\.user\.base-url' \
  "$REPO_ROOT/backend/services/learning/src/main" "$REPO_ROOT/backend/services/identity-account/src/main" >/dev/null; then
  echo 'Replacement production sources must not call the deleted standalone user runtime' >&2
  exit 1
fi
# Validate the executable local contract without starting Docker or reading .env.
python3 - "$REPO_ROOT" <<'PY_LOCAL_COMPOSE'
import json
import os
from pathlib import Path
import re
import subprocess
import sys

root = Path(sys.argv[1])
compose = root / "docker-compose.yml"
command = ["docker", "compose", "--env-file", os.devnull, "-f", str(compose),
           "config", "--format", "json"]
environment = {key: value for key, value in os.environ.items()
               if not key.startswith(("MNEMA_LOCAL_", "COMPOSE_"))}
# Old credentials must never satisfy the replacement password requirement.
environment.update(POSTGRES_PASSWORD="legacy-test-value", POSTGRES_USER="legacy-user",
                   POSTGRES_DB="legacy-db")
environment["MNEMA_LOCAL_IDENTITY_SIGNING_ACTIVE_KID"] = "local-test-kid"
environment["MNEMA_LOCAL_IDENTITY_SIGNING_JWK_SET_FILE"] = os.devnull
missing = subprocess.run(command, env=environment, capture_output=True, text=True, check=False)
assert missing.returncode != 0 and "MNEMA_LOCAL_POSTGRES_PASSWORD" in missing.stderr

environment["MNEMA_LOCAL_POSTGRES_PASSWORD"] = "replacement-test-value"
result = subprocess.run(command, env=environment, capture_output=True, text=True, check=False)
assert result.returncode == 0, "Replacement Compose config must render successfully"
config = json.loads(result.stdout)
assert config["name"] == "mnema-replacement"
assert set(config["services"]) == {"postgres", "identity-account", "learning"}
assert set(config["volumes"]) == {"replacement_postgres_data"}
assert config["volumes"]["replacement_postgres_data"]["name"] == "mnema-replacement_replacement_postgres_data"
postgres = config["services"]["postgres"]
assert postgres["environment"]["POSTGRES_DB"] == "mnema"
assert postgres["environment"]["POSTGRES_USER"] == "mnema"
assert postgres["environment"]["POSTGRES_PASSWORD"] == "replacement-test-value"
assert postgres["volumes"][0]["source"] == "replacement_postgres_data"
assert postgres["volumes"][0]["target"] == "/var/lib/postgresql"
assert re.fullmatch(r"postgres:18@sha256:[0-9a-f]{64}", postgres["image"])
for service in config["services"].values():
    assert all(port["host_ip"] == "127.0.0.1" for port in service.get("ports", []))
    assert "env_file" not in service
stages = set(re.findall(r"(?im)^FROM .* AS ([a-z0-9-]+)$",
                        (root / "backend/Dockerfile").read_text()))
for name in ("identity-account", "learning"):
    service = config["services"][name]
    assert service["build"]["target"] == name + "-runtime"
    assert service["build"]["target"] in stages
    assert service["environment"]["SPRING_DATASOURCE_PASSWORD"] == "replacement-test-value"
assert config["services"]["identity-account"]["environment"]["MNEMA_IDENTITY_ISSUER"] == "https://localhost:18081"
assert config["services"]["learning"]["environment"]["MNEMA_IDENTITY_ISSUER"] == "https://localhost:18081"
identity_mount = config["services"]["identity-account"]["volumes"][0]
assert identity_mount["source"] == os.devnull
assert identity_mount["target"] == "/var/run/secrets/mnema-identity/identity-signing-jwk-set.json"
assert identity_mount["read_only"] is True
source = compose.read_text()
assert all(name.startswith("MNEMA_LOCAL_") for name in re.findall(r"\$\{([A-Z_]+)", source))

# Superseded launchers and their deployable source are absent, not merely gated.
for service in ("core", "media", "import", "ai"):
    assert not (root / "backend/services" / service).exists()
    assert f'"services:{service}"' not in (root / "backend/settings.gradle.kts").read_text()
for launcher in ("mnema-local", "mnema-public"):
    for suffix in (".sh", ".ps1"):
        assert not (root / "scripts" / (launcher + suffix)).exists()
routes = (root / "frontend/src/app/app.routes.ts").read_text()
runtime_config = (root / "frontend/src/app/app.config.ts").read_text()
for old_route in ("my-study", "public-decks", "settings", "admin"):
    assert f"path: '{old_route}'" not in routes
for old_api in ("/api/core", "/api/media", "/api/import", "/api/ai", "/api/user"):
    assert old_api not in runtime_config
print("local_replacement_contract=ok")
PY_LOCAL_COMPOSE

printf 'deployment_contract=ok\n'
