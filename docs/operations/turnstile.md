# Identity abuse protection

The current Identity `/api/accounts/login` and `/api/accounts/register` endpoints
verify Turnstile **before** authenticating a password or creating an account. CSRF,
trusted client-address resolution and the existing rate limits remain separate.
This protection does not replace WAF, network DDoS controls or AI budget limits.
Federated OAuth and recovery/mail routes keep their existing independent controls;
this slice does not claim Turnstile coverage for them.

## Configuration and production gate

| Name | Owning runtime / behavior |
| --- | --- |
| `APP_ENV` | Identity: fixed `prod` in the reviewed deployment configuration |
| `MNEMA_IDENTITY_TURNSTILE_MODE` | `required`, `blocked`, or local-only `disabled` |
| `MNEMA_IDENTITY_TURNSTILE_PRIVACY_APPROVED` | Administrator enables only with operator evidence in #280/#351 |
| `TURNSTILE_SITE_KEY` | Public widget key; read from Identity configuration by the browser |
| `TURNSTILE_SECRET_KEY` | Identity-only private secret; never frontend/Git/CI output |

The explicit local environments are `dev`, `development`, `test`, `local`,
`local-blackbox`, `local-browser-fixture`, and `local-full-stack` (the existing
disposable repository fixtures). Other environments cannot disable protection: `disabled` becomes
`blocked`. `required` without the approved privacy boundary also becomes `blocked`.
Blocked mode returns 503 for password login/registration, performs no Siteverify
call and causes the browser to avoid loading the Cloudflare script. Health and
other service behavior remain available. Invalid/missing required keys stop startup;
Cloudflare testing keys are forbidden outside local/test environments. The
production Compose/config verifier must pin `APP_ENV=prod`; callers cannot choose it.

`GET /api/accounts/abuse-protection` exposes only mode and the public site key when
required. It never exposes a secret or claims that legal approval has occurred.

## Widget and request contract

Confirm in the Cloudflare account that the real widget belongs to this operator,
uses **Invisible mode**, and permits exactly `mnema.app` and `auth.mnema.app` before
promotion. The frontend and Identity-hosted OAuth login page create an explicit
widget on submission, with action `register` or `login`. Registration's following
password login obtains a **second** token. Tokens remain in memory and go only in
the JSON mutation's `turnstileToken`, never in a redirect/query or browser storage.

The server calls the fixed HTTPS Siteverify endpoint with only secret and token.
It checks success, exact action, one of the two configured hostnames and timestamp
(maximum age 300 seconds; at most 30 seconds future clock skew). Siteverify owns
atomic one-use/replay enforcement. No success caching or retry can turn a consumed
token into a second authorization. Missing/forged/replayed/expired/mismatched tokens
return 403. Upstream failure returns 503 without exposing response content.

Siteverify has a 2-second connect timeout and 5-second read timeout, no redirects.
Before the external call, the server limits validation to 30 attempts per trusted
client address per 15 minutes. Password and registration limits remain in place.
The browser bounds config/script loading to 8 seconds each and the challenge to
20 seconds; error/expiry/timeout removes the widget and a new submission starts
a fresh check. Waiting/error feedback uses accessible status/alert text, with
the password cleared afterward. Invisible mode cannot guarantee success or
uninterrupted access during a provider outage.

Production frontend CSP already permits the Cloudflare script, frame and connection.
Identity uses an explicit CSP and its own external `/login/script.js`; no inline
script exception is needed. The privacy page and hosted login disclosure link to
the Turnstile Privacy Addendum, as required for Invisible use.

## Data and acceptance boundaries

Omitting `remoteip` in Siteverify reduces the server payload; it does not remove
the direct browser→Cloudflare IP/device flow or prove Russian-law compliance.
Operator identity/contact, purposes, recipients/retention, consent/other basis and
any required notifications must be completed by the operator in #280/#351. The
targeted Turnstile disclosure is not evidence that the entire privacy policy or
notifications have been approved. Keep the production gate blocked until then.

Local unit/HTTP/browser fixtures prove implementation behavior, not ownership of a
real widget. Production acceptance must separately demonstrate real invisible
login/register, exact hostname/action, duplicate/expiry denial, direct API denial,
script blocking, upstream timeout, CSP and mobile/keyboard feedback. Do not use
dummy tokens or test keys as production evidence. Rollback preserves the protection
boundary: set mode `blocked` rather than disable it in production.

Sources informing the design:
[Cloudflare Siteverify and one-use contract](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/),
[explicit widget execution](https://developers.cloudflare.com/turnstile/get-started/client-side-rendering/widget-configurations/),
[Turnstile Privacy Addendum](https://www.cloudflare.com/turnstile-privacy-policy/),
[Spring RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html).
