# Browser security headers and CSP rollout

## Contract

The frontend container generates `/etc/nginx/conf.d/security-headers.inc` before nginx starts. Nginx includes it at server scope with `add_header_inherit merge`, so the same `always` headers cover the SPA shell, runtime config, immutable assets, AI-disabled `503` responses, and nginx error responses without replacing location-specific cache headers.

Every environment sends:

- `X-Content-Type-Options: nosniff`;
- `Referrer-Policy: strict-origin-when-cross-origin`;
- a deny-by-default `Permissions-Policy` for unused sensor, media-capture, payment, and USB capabilities;
- an enforced CSP baseline with `base-uri 'self'`, `object-src 'none'`, and `frame-ancestors 'none'`;
- no nginx version in the `Server` response token.

Hosted policies use only `self`, data/blob where the application needs them, and exact origins generated from the release manifest:

- the environment-specific auth and object-storage origins;
- `https://fonts.googleapis.com` and `https://fonts.gstatic.com`;
- `https://challenges.cloudflare.com` for Turnstile scripts, frames, and connections;
- the exact Google, GitHub, and Yandex avatar origins already emitted by the three supported federated identity mappers.

Production media uses the Yandex Object Storage path-style form `https://storage.yandexcloud.net/<bucket>/<key>`, so browser-facing presigned uploads and downloads stay on the exact CSP origin instead of moving to a bucket-specific subdomain. Both URL forms are supported by [Yandex Object Storage](https://yandex.cloud/en/docs/storage/concepts/object); the renderer and AWS SDK presigner regression test bind this choice.

There is no wildcard source and no `unsafe-eval`. The JSON-LD block is admitted by one reviewed SHA-256 hash, and `script-src-attr 'none'` rejects inline event handlers. Angular component styles and user flashcard layouts still require the single `style-src 'unsafe-inline'` exception. Removing that exception belongs to [#74](https://github.com/MattoYuzuru/Mnema/issues/74); do not expand it to scripts or use it as a shortcut for a new third-party origin.

The policy follows the [W3C CSP report-only rollout model](https://www.w3.org/TR/CSP/#header-content-security-policy-report-only), [Cloudflare's exact Turnstile CSP origins](https://developers.cloudflare.com/turnstile/reference/content-security-policy/), and nginx's [`always` and inherited-header behavior](https://nginx.org/en/docs/http/ngx_http_headers_module.html).

## Rollout states

| Environment | Enforced policy | Observed policy | HSTS |
| --- | --- | --- | --- |
| development | clickjacking/base/object baseline | none | none |
| staging | clickjacking/base/object baseline | full resource policy | none |
| production | full resource policy | none | `max-age=300`, host only |

Production deliberately omits `includeSubDomains` and `preload`. Those flags affect hosts outside this application and require a separate domain inventory and long-lived rollback decision. HSTS is generated only for the verified HTTPS production mode, consistent with the [HSTS host and lifetime semantics](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Strict-Transport-Security).

## Preflight and hosted evidence

Run the production frontend build before the contract because the verifier binds the JSON-LD hash and hashed static bundle:

```sh
cd frontend
npm run build
cd ..
./scripts/test-browser-security-headers.sh
```

The contract rejects unknown deployment modes, non-HTTPS or injected hosted origins, broad CSP sources, unexpected inline executable content, an exposed nginx version, and missing headers on success/error/static/runtime-config responses. It also starts the pinned nginx image for both staging and production modes.

After a staging rollout, `staging-deploy.yaml` performs two fail-closed checks inside the existing release smoke boundary:

1. an HTTP smoke checks `/`, `/login`, `/app-config.js`, the hashed main bundle, a static `404`, and the AI-disabled `503` response;
2. headless Chrome loads `/login`, confirms that the configured Turnstile client was attempted, and rejects any CSP violation in the browser log.

The same checks are part of the production smoke with enforced CSP, but production deployment still requires the repository's explicit production environment authorization.

## Stop, classify, and rollback

Stop promotion when the staging browser reports any violation. Classify the blocked URL by application feature and directive, then choose one of these bounded actions:

- for a required resource already owned by Mnema, add its exact environment origin and repeat the staging observation;
- for unexpected legacy/external content, keep it blocked and move the content migration or removal into the owning work item;
- for an inline script or handler, remove it or bind an immutable script body with a reviewed hash; never add script `unsafe-inline`;
- for Turnstile, retain the vendor's exact `challenges.cloudflare.com` sources instead of proxying `api.js` or admitting a wildcard.

A failed hosted check remains part of the release smoke outcome, so the existing staging or production workflow restores the previous verified manifest. Follow [release verification and rollback](release-verification-runbook.md) for the operational record. If production HSTS itself must be withdrawn, serve `Strict-Transport-Security: max-age=0` over HTTPS; merely rolling back to a response without HSTS leaves the five-minute cached policy active until expiry.

Do not promote a report-only policy to production by editing a live container. Change the generated contract, pass the repository quality gates, observe staging, and promote the exact tested image digest through the protected workflows.
