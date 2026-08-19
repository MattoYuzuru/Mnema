#!/bin/sh
set -eu

NAMESPACE=mnema-staging
ISSUER=letsencrypt-prod

issuer_json=$(kubectl get clusterissuer "$ISSUER" -o json)
printf '%s' "$issuer_json" | python3 -c '
import json
import sys

issuer = json.load(sys.stdin)
ready = any(
    condition.get("type") == "Ready" and condition.get("status") == "True"
    for condition in issuer.get("status", {}).get("conditions", [])
)
solvers = issuer.get("spec", {}).get("acme", {}).get("solvers", [])
staging_solver = any(
    "staging.mnema.app" in solver.get("selector", {}).get("dnsZones", [])
    and solver.get("http01", {}).get("ingress", {}).get("serviceType") == "ClusterIP"
    and solver.get("http01", {}).get("ingress", {}).get("ingressClassName") == "traefik"
    for solver in solvers
)
if not ready or not staging_solver:
    raise SystemExit(1)
'

secret_quota=$(kubectl -n "$NAMESPACE" get resourcequota mnema-staging-quota \
  -o jsonpath='{.spec.hard.count\/secrets}')
case "$secret_quota" in
  '' | *[!0-9]*)
    echo "Staging Secret quota is missing or invalid" >&2
    exit 1
    ;;
esac
if [ "$secret_quota" -lt 10 ]; then
  echo "Staging Secret quota cannot hold application, TLS and renewal Secrets with headroom" >&2
  exit 1
fi

for certificate_name in \
  staging-mnema-app-tls \
  auth-staging-mnema-app-tls \
  storage-staging-mnema-app-tls
do
  kubectl -n "$NAMESPACE" wait --for=create "certificate/$certificate_name" \
    --timeout=120s >/dev/null
  kubectl -n "$NAMESPACE" wait --for=condition=Ready=True \
    "certificate/$certificate_name" --timeout=300s >/dev/null
  certificate_json=$(kubectl -n "$NAMESPACE" get certificate "$certificate_name" -o json)
  printf '%s' "$certificate_json" | python3 -c '
import json
import sys

certificate = json.load(sys.stdin)
issuer = certificate.get("spec", {}).get("issuerRef", {})
if (
    issuer.get("kind") != "ClusterIssuer"
    or issuer.get("name") != "letsencrypt-prod"
    or not certificate.get("status", {}).get("renewalTime")
):
    raise SystemExit(1)
'
  secret_json=$(kubectl -n "$NAMESPACE" get secret "$certificate_name" -o json)
  printf '%s' "$secret_json" | python3 -c '
import json
import sys

secret = json.load(sys.stdin)
data = secret.get("data", {})
if secret.get("type") != "kubernetes.io/tls" or not data.get("tls.crt") or not data.get("tls.key"):
    raise SystemExit(1)
'
done

printf 'staging_tls_boundary=ok\n'
