#!/bin/sh
set -eu

case "${MNEMA_FAKE_CHROME_CASE:-rendered}" in
  rendered)
    printf '%s\n' \
      '<script data-turnstile="true"></script>' \
      '<input type="hidden" name="cf-turnstile-response">'
    ;;
  missing-response)
    printf '%s\n' '<script data-turnstile="true"></script>'
    ;;
  csp-violation)
    printf '%s\n' \
      '<script data-turnstile="true"></script>' \
      '<input type="hidden" name="cf-turnstile-response">'
    printf '%s\n' \
      "Refused to load a script because it violates the following Content Security Policy directive; source: https://app.example.test" >&2
    ;;
  *)
    echo "Unknown fake Chrome case: ${MNEMA_FAKE_CHROME_CASE}" >&2
    exit 2
    ;;
esac
