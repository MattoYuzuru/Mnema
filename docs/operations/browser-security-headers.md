# Browser security headers and CSP rollout

## Contract

The frontend container generates `/etc/nginx/conf.d/security-headers.inc` before nginx starts. Nginx includes it at server scope with `add_header_inherit merge`, so the same `always` headers cover the SPA shell, runtime config, immutable assets, AI-disabled `503` responses, and nginx error responses without replacing location-specific cache headers.

Every environment sends:

- `X-Content-Type-Options: nosniff`;
- `Referrer-Policy: strict-origin-when-cross-origin`;
- a deny-by-default `Permissions-Policy` for unused sensors, payment, and USB capabilities; camera and microphone are limited to `self` for the user-initiated media recorder;
- an enforced CSP baseline with `base-uri 'self'`, `object-src 'none'`, and `frame-ancestors 'none'`;
- no nginx version in the `Server` response token.

Hosted policies use only `self`, data/blob where the application needs them, and exact origins generated from the release manifest:

- the environment-specific auth and object-storage origins;
- `https://fonts.googleapis.com` and `https://fonts.gstatic.com`;
- `https://challenges.cloudflare.com` for Turnstile scripts, frames, and connections;
- `https://www.youtube-nocookie.com` for a viewer-initiated privacy-enhanced YouTube iframe;
- the exact Google, GitHub, and Yandex avatar origins already emitted by the three supported federated identity mappers.

Production media uses the Yandex Object Storage path-style form `https://storage.yandexcloud.net/<bucket>/<key>`, so browser-facing presigned uploads and downloads stay on the exact CSP origin instead of moving to a bucket-specific subdomain. Both URL forms are supported by [Yandex Object Storage](https://yandex.cloud/en/docs/storage/concepts/object); the renderer and AWS SDK presigner regression test bind this choice.

There is no wildcard source and no `unsafe-eval`. The JSON-LD block is admitted by one reviewed SHA-256 hash, and `script-src-attr 'none'` rejects inline event handlers. Angular component styles still require the single `style-src 'unsafe-inline'` exception. Any future CSP hardening must inventory replacement and remaining legacy styles in a bounded task; do not expand the exception to scripts or use it as a shortcut for a new third-party origin.

The policy follows the [W3C CSP report-only rollout model](https://www.w3.org/TR/CSP/#header-content-security-policy-report-only), [Cloudflare's exact Turnstile CSP origins](https://developers.cloudflare.com/turnstile/reference/content-security-policy/), and nginx's [`always` and inherited-header behavior](https://nginx.org/en/docs/http/ngx_http_headers_module.html).

## Modes

| Mode | Enforced policy | Observed policy | HSTS |
| --- | --- | --- | --- |
| development | clickjacking/base/object baseline | none | none |
| staging | clickjacking/base/object baseline | full resource policy | none |
| production | full resource policy | none | `max-age=31536000` (one year), host only |

`staging` is a report-only observation mode of the frontend generator. No staging environment is deployed
today (the Kubernetes staging flow was removed); the mode stays only so a future observation environment can
reuse the generator contract.

`auth.mnema.app` (Identity, served by host Caddy, not by the frontend nginx) returns the same `Strict-Transport-Security: max-age=31536000` from `deploy/production/Caddyfile`. Production deliberately omits `includeSubDomains` and `preload`. Those flags affect hosts outside this application and require a separate domain inventory and long-lived rollback decision. HSTS is generated only for the verified HTTPS production mode, consistent with the [HSTS host and lifetime semantics](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Strict-Transport-Security).

## Preflight and hosted evidence

Run the production frontend build before the contract because the verifier binds the JSON-LD hash and hashed static bundle:

```sh
cd frontend
npm run build
cd ..
./scripts/test-browser-security-headers.sh
```

The contract rejects unknown deployment modes, non-HTTPS or injected hosted origins, broad CSP sources, unexpected inline executable content, an exposed nginx version, and missing headers on success/error/static/runtime-config responses. It also starts the pinned nginx image for both staging and production modes.

The post-deploy public smoke (`scripts/smoke/vps_public_smoke.py`, see [production delivery](production-delivery.md)) checks the live origin over HTTP, including HSTS `max-age=31536000`, `X-Content-Type-Options: nosniff` and a present CSP on `https://mnema.app/` and HSTS on the auth origin (presence checks only; the exact policy is enforced by the contract above before release). `scripts/verify-hosted-browser-csp.sh https://<host>/login` is a manual hosted check: headless Chrome loads `/login`, confirms that Turnstile created its [documented `cf-turnstile-response` form field](https://developers.cloudflare.com/turnstile/get-started/client-side-rendering/widget-configurations/#form-integration), and rejects any CSP violation in the browser log. Chrome's serialized DOM does not expose Turnstile's internal challenge frame, so the stable form-integration contract is used instead. Its negative cases run in `test-browser-security-headers.sh` against a fake browser; it is not part of an automated workflow.

## Stop, classify, and rollback

Stop promotion when the browser reports any violation. Classify the blocked URL by application feature and directive, then choose one of these bounded actions:

- for a required resource already owned by Mnema, add its exact environment origin and repeat the observation;
- for unexpected legacy/external content, keep it blocked and move the content migration or removal into the owning work item;
- for an inline script or handler, remove it or bind an immutable script body with a reviewed hash; never add script `unsafe-inline`;
- for Turnstile, retain the vendor's exact `challenges.cloudflare.com` sources instead of proxying `api.js` or admitting a wildcard.

A failed hosted check is handled through the VPS rollback in [production delivery](production-delivery.md). If production HSTS itself must be withdrawn, serve `Strict-Transport-Security: max-age=0` over HTTPS; merely rolling back to a response without HSTS leaves the one-year cached policy active until expiry.

Do not promote a report-only policy to production by editing a live container. Change the generated contract, pass the repository quality gates, and promote the exact tested image digest through the protected Main CI release.
