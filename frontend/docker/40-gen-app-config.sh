#!/bin/sh
set -eu

OUT="${MNEMA_APP_CONFIG_OUT:-/usr/share/nginx/html/app-config.js}"
AI_ROUTE="${MNEMA_AI_ROUTE_OUT:-/etc/nginx/conf.d/ai-route.inc}"
SECURITY_HEADERS="${MNEMA_SECURITY_HEADERS_OUT:-/etc/nginx/conf.d/security-headers.inc}"
AI_ENABLED="${MNEMA_FEATURE_AI_ENABLED:-false}"
APP_ENV="${MNEMA_APP_ENV:-development}"
PUBLIC_ORIGIN="${MNEMA_PUBLIC_ORIGIN:-}"
AUTH_ORIGIN="${MNEMA_AUTH_SERVER_URL:-}"
STORAGE_ORIGIN="${MNEMA_STORAGE_ORIGIN:-}"

fail() {
  printf 'frontend runtime configuration error: %s\n' "$1" >&2
  exit 1
}

validate_https_origin() {
  label="$1"
  origin="$2"
  case "$origin" in
    https://*) authority=${origin#https://} ;;
    *) fail "$label must be an https origin" ;;
  esac
  case "$authority" in
    "" | *[!a-z0-9.-]* | .* | *. | *..*)
      fail "$label must contain only a lowercase DNS host without a path, port, query or fragment"
      ;;
  esac
}

write_security_headers() {
  security_tmp="${SECURITY_HEADERS}.tmp.$$"
  trap 'rm -f "$security_tmp"' EXIT HUP INT TERM

  cat > "$security_tmp" <<'NGINX'
add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()" always;
NGINX

  baseline_csp="base-uri 'self'; object-src 'none'; frame-ancestors 'none'"
  case "$APP_ENV" in
    development)
      printf 'add_header Content-Security-Policy "%s" always;\n' "$baseline_csp" >> "$security_tmp"
      ;;
    staging | prod)
      validate_https_origin "MNEMA_PUBLIC_ORIGIN" "$PUBLIC_ORIGIN"
      validate_https_origin "MNEMA_AUTH_SERVER_URL" "$AUTH_ORIGIN"
      validate_https_origin "MNEMA_STORAGE_ORIGIN" "$STORAGE_ORIGIN"
      full_csp="default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self' 'sha256-VR45d+4Tpmsv5J0dHbmYAic5u7F3Ttjk763rpC0sZHI=' https://challenges.cloudflare.com; script-src-attr 'none'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data: blob: $STORAGE_ORIGIN https://lh3.googleusercontent.com https://avatars.githubusercontent.com https://github.com https://avatars.yandex.net; media-src 'self' blob: $STORAGE_ORIGIN; connect-src 'self' $AUTH_ORIGIN $STORAGE_ORIGIN https://challenges.cloudflare.com; frame-src https://challenges.cloudflare.com; worker-src 'self' blob:; manifest-src 'self'"
      if [ "$APP_ENV" = "staging" ]; then
        printf 'add_header Content-Security-Policy "%s" always;\n' "$baseline_csp" >> "$security_tmp"
        printf 'add_header Content-Security-Policy-Report-Only "%s" always;\n' "$full_csp" >> "$security_tmp"
      else
        printf 'add_header Content-Security-Policy "%s" always;\n' "$full_csp" >> "$security_tmp"
        # Start with a bounded host-only policy. includeSubDomains and preload
        # require a separate inventory and long-lived rollout decision.
        printf '%s\n' 'add_header Strict-Transport-Security "max-age=300" always;' >> "$security_tmp"
      fi
      ;;
    *)
      fail "MNEMA_APP_ENV must be development, staging or prod"
      ;;
  esac

  mv "$security_tmp" "$SECURITY_HEADERS"
  trap - EXIT HUP INT TERM
}

js_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

append_string_override() {
  key="$1"
  value="$2"
  if [ -n "$value" ]; then
    escaped="$(js_escape "$value")"
    printf 'window.MNEMA_APP_CONFIG.%s = "%s";\n' "$key" "$escaped" >> "$OUT"
  fi
}

append_bool_override() {
  key="$1"
  value="$2"
  case "$value" in
    true|false)
      printf 'window.MNEMA_APP_CONFIG.features.%s = %s;\n' "$key" "$value" >> "$OUT"
      ;;
    *)
      ;;
  esac
}

cat > "$OUT" <<'JS'
window.MNEMA_APP_CONFIG = window.MNEMA_APP_CONFIG || {};
window.MNEMA_APP_CONFIG.features = window.MNEMA_APP_CONFIG.features || {};
JS

append_string_override "authServerUrl" "${MNEMA_AUTH_SERVER_URL:-}"
append_string_override "apiBaseUrl" "${MNEMA_API_BASE_URL:-}"
append_string_override "coreApiBaseUrl" "${MNEMA_CORE_API_BASE_URL:-}"
append_string_override "mediaApiBaseUrl" "${MNEMA_MEDIA_API_BASE_URL:-}"
append_string_override "importApiBaseUrl" "${MNEMA_IMPORT_API_BASE_URL:-}"
append_string_override "aiApiBaseUrl" "${MNEMA_AI_API_BASE_URL:-}"
append_string_override "clientId" "${MNEMA_CLIENT_ID:-}"
append_string_override "buildId" "${MNEMA_BUILD_ID:-dev}"
append_string_override "features.aiSystemProviderName" "${MNEMA_FEATURE_AI_SYSTEM_PROVIDER_NAME:-}"
append_bool_override "federatedAuthEnabled" "${MNEMA_FEATURE_FEDERATED_AUTH_ENABLED:-}"
append_bool_override "showEmailVerificationWarning" "${MNEMA_FEATURE_SHOW_EMAIL_VERIFICATION_WARNING:-}"
append_bool_override "aiEnabled" "$AI_ENABLED"
append_bool_override "aiSystemProviderEnabled" "${MNEMA_FEATURE_AI_SYSTEM_PROVIDER_ENABLED:-}"

write_security_headers

if [ "$AI_ENABLED" = "true" ]; then
  : > "$AI_ROUTE"
else
  printf '%s\n' \
    'location ^~ /api/ai {' \
    '  default_type application/problem+json;' \
    '  add_header Cache-Control "no-store" always;' \
    '  add_header Retry-After "86400" always;' \
    '  return 503 '\''{"type":"about:blank","title":"AI temporarily unavailable","status":503,"code":"AI_TEMPORARILY_UNAVAILABLE"}'\'';' \
    '}' > "$AI_ROUTE"
fi
