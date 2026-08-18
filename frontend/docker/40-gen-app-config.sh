#!/bin/sh
set -eu

OUT="${MNEMA_APP_CONFIG_OUT:-/usr/share/nginx/html/app-config.js}"
AI_ROUTE="${MNEMA_AI_ROUTE_OUT:-/etc/nginx/conf.d/ai-route.inc}"
AI_ENABLED="${MNEMA_FEATURE_AI_ENABLED:-false}"

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
append_string_override "features.aiSystemProviderName" "${MNEMA_FEATURE_AI_SYSTEM_PROVIDER_NAME:-}"
append_bool_override "federatedAuthEnabled" "${MNEMA_FEATURE_FEDERATED_AUTH_ENABLED:-}"
append_bool_override "showEmailVerificationWarning" "${MNEMA_FEATURE_SHOW_EMAIL_VERIFICATION_WARNING:-}"
append_bool_override "aiEnabled" "$AI_ENABLED"
append_bool_override "aiSystemProviderEnabled" "${MNEMA_FEATURE_AI_SYSTEM_PROVIDER_ENABLED:-}"

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
