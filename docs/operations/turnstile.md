# Identity abuse protection

The current Identity `/api/accounts/login` and `/api/accounts/register` endpoints
verify Turnstile **before** authenticating a password or creating an account. CSRF,
trusted client-address resolution and the existing rate limits remain separate.
This protection does not replace WAF, network DDoS controls or AI budget limits.
Federated OAuth and recovery/mail routes keep their existing independent controls;
this slice does not claim Turnstile coverage for them.

## Configuration

| Name | Owning runtime / behavior |
| --- | --- |
| `APP_ENV` | Identity: fixed `prod` in the reviewed deployment configuration |
| `MNEMA_IDENTITY_TURNSTILE_MODE` | `required` (production default), `blocked` (operational kill switch), or local-only `disabled` |
| `TURNSTILE_SITE_KEY` | Public widget key; read from Identity configuration by the browser |
| `TURNSTILE_SECRET_KEY` | Identity-only private secret; never frontend/Git/CI output |

The explicit local environments are `dev`, `development`, `test`, `local`,
`local-blackbox`, `local-browser-fixture`, and `local-full-stack` (the existing
disposable repository fixtures). Other environments cannot disable protection: `disabled` becomes
`blocked`. There is no legal-approval flag: production runs `required` by owner
decision of 2026-10-08, after the main RKN notification and the cross-border
notification for Cloudflare were submitted (see the
[legal launch status](../product/russia-legal-launch-checklist-2026.md)). Blocked
mode, selected deliberately through the deployed application configuration (`PROD_MNEMA_IDENTITY_TURNSTILE_MODE`), returns 503 for password login/registration, performs no Siteverify
call and causes the browser to avoid loading the Cloudflare script, while OAuth sign-in and the rest of the service keep working. Invalid/missing required keys stop startup;
Cloudflare testing keys are forbidden outside local/test environments. The
production Compose/config verifier must pin `APP_ENV=prod`; callers cannot choose it.

`GET /api/accounts/abuse-protection` exposes only mode and the public site key when
required. It never exposes a secret.

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
script exception is needed. The public privacy policy (section 8) and the hosted
login disclosure link to the Turnstile Privacy Addendum, as required for Invisible
use, and the login and registration page links the policy and terms next to the
submit button.

## Data and acceptance boundaries

Omitting `remoteip` in Siteverify reduces the server payload; it does not remove
the direct browser→Cloudflare IP/device flow. That flow is a cross-border
transfer to Cloudflare, Inc. (USA) and is disclosed in section 8 of the public
[privacy policy](../../frontend/src/app/privacy-page.component.html) with the
purpose, data, Cloudflare's roles and the notification. The Cloudflare-side list of
processing countries and retention for this account is not confirmed (the
published addendum does not state it); keep the policy wording aligned with
whatever Cloudflare confirms. The root, auth and www DNS records are DNS-only:
proxying site traffic through Cloudflare is a different data flow that this
disclosure does not cover.

The owner confirmed the Cloudflare widget (Invisible mode, exactly `mnema.app` and
`auth.mnema.app`) and the three OAuth callbacks. Local unit/HTTP/browser fixtures
prove implementation behavior, not ownership of a real widget. Real production
evidence still has to demonstrate invisible login/register, exact hostname/action,
duplicate/expiry denial, direct API denial, script blocking, upstream timeout, CSP
and mobile/keyboard feedback. Do not use dummy tokens or test keys as production
evidence. Rollback preserves the protection boundary: set mode `blocked` rather
than disable it in production.

Sources informing the design:
[Cloudflare Siteverify and one-use contract](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/),
[explicit widget execution](https://developers.cloudflare.com/turnstile/get-started/client-side-rendering/widget-configurations/),
[Turnstile Privacy Addendum](https://www.cloudflare.com/turnstile-privacy-policy/),
[Spring RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html).
