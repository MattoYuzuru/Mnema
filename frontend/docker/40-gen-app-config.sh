#!/bin/sh
set -eu

OUT="${MNEMA_APP_CONFIG_OUT:-/usr/share/nginx/html/app-config.js}"
SECURITY_HEADERS="${MNEMA_SECURITY_HEADERS_OUT:-/etc/nginx/conf.d/security-headers.inc}"
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
add_header Permissions-Policy "accelerometer=(), camera=(self), geolocation=(), gyroscope=(), magnetometer=(), microphone=(self), payment=(), usb=()" always;
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
      full_csp="default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self' 'sha256-Fp5GJnYMl9gcleSNB+7ZRLuuxVyhm4juel5fT2zlUrU=' https://challenges.cloudflare.com; script-src-attr 'none'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data: blob: $AUTH_ORIGIN $STORAGE_ORIGIN https://lh3.googleusercontent.com https://avatars.githubusercontent.com https://github.com https://avatars.yandex.net; media-src 'self' blob: $STORAGE_ORIGIN; connect-src 'self' $AUTH_ORIGIN $STORAGE_ORIGIN https://challenges.cloudflare.com; frame-src https://challenges.cloudflare.com https://www.youtube-nocookie.com; worker-src 'self' blob:; manifest-src 'self'"
      if [ "$APP_ENV" = "staging" ]; then
        printf 'add_header Content-Security-Policy "%s" always;\n' "$baseline_csp" >> "$security_tmp"
        printf 'add_header Content-Security-Policy-Report-Only "%s" always;\n' "$full_csp" >> "$security_tmp"
      else
        printf 'add_header Content-Security-Policy "%s" always;\n' "$full_csp" >> "$security_tmp"
        # One-year host-only policy. includeSubDomains and preload
        # require a separate inventory and long-lived rollout decision.
        printf '%s\n' 'add_header Strict-Transport-Security "max-age=31536000" always;' >> "$security_tmp"
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
    control_free=$(printf '%s' "$value" | LC_ALL=C tr -d '\000-\037\177')
    [ "$control_free" = "$value" ] || fail "$key must not contain control characters"
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
IDENTITY_REDIRECT="${MNEMA_IDENTITY_REDIRECT_URI:-${PUBLIC_ORIGIN:+$PUBLIC_ORIGIN/auth/callback}}"
LEARNING_BASE="${MNEMA_LEARNING_API_BASE_URL:-/api}"
[ "$LEARNING_BASE" = /api ] || fail "Learning must use the canonical same-origin /api boundary"
if [ -n "$IDENTITY_REDIRECT" ]; then
  case "$IDENTITY_REDIRECT" in
    https://*/auth/callback) ;;
    *) fail "Identity redirect must use HTTPS and the exact auth callback path" ;;
  esac
  redirect_authority=${IDENTITY_REDIRECT#https://}
  redirect_authority=${redirect_authority%/auth/callback}
  case "$redirect_authority" in
    "" | *[!a-z0-9.:-]* | /* | *..*) fail "Identity redirect must contain a DNS host and optional local port" ;;
  esac
  [ "$AUTH_ORIGIN" != "https://$redirect_authority" ] || fail "Identity must have a separate origin to omit ambient cookies"
fi
if [ "$APP_ENV" != development ]; then
  [ "$IDENTITY_REDIRECT" = "$PUBLIC_ORIGIN/auth/callback" ] || fail "Identity redirect must exactly match the public auth callback"
fi
append_string_override "identityRedirectUri" "$IDENTITY_REDIRECT"
append_string_override "learningApiBaseUrl" "$LEARNING_BASE"
append_string_override "clientId" "${MNEMA_CLIENT_ID:-}"
append_string_override "buildId" "${MNEMA_BUILD_ID:-dev}"
SUPPORT_USERNAME="${MNEMA_SUPPORT_TELEGRAM_USERNAME:-}"
if [ -n "$SUPPORT_USERNAME" ]; then
  case "$SUPPORT_USERNAME" in
    *[!a-zA-Z0-9_]* | ? | ?? | ??? | ????) fail "Support Telegram username must contain 5-32 Latin letters, numbers or underscores" ;;
  esac
  [ "${#SUPPORT_USERNAME}" -le 32 ] || fail "Support Telegram username must contain 5-32 Latin letters, numbers or underscores"
  case "$SUPPORT_USERNAME" in
    *[bB][oO][tT]) ;;
    *) fail "Support Telegram username must end in bot" ;;
  esac
fi
append_string_override "supportTelegramUsername" "$SUPPORT_USERNAME"
append_bool_override "showEmailVerificationWarning" "${MNEMA_FEATURE_SHOW_EMAIL_VERIFICATION_WARNING:-}"

write_security_headers
