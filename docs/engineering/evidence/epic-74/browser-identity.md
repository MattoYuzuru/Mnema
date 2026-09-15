# Browser Identity integration — #192

## Implemented boundary

The replacement frontend uses the existing Identity Account API, not the removed
legacy `/auth/login` and `/auth/register`. No Identity backend behavior, dependencies,
client registration or security policy changes. `mnema-web` requests its six registered
scopes: `openid profile account.read account.write learning.read learning.write`.

Registration/password login use a credentialed CSRF bootstrap and canonical account
routes, followed by authorization code/S256 PKCE at the exact `/auth/callback`. The
one-use transaction is consumed before exchange; state, issuer/client/callback and
ten-minute transaction bounds are checked. Invalid/repeated callbacks do not exchange.
No client secret, refresh token, ID-token profile trust or JWT-payload decoding.

Only the short-lived access token and issuer/client/expiry binding enter sessionStorage.
Reload always verifies the real bearer profile; cached text is not identity proof.
Restoration is single-flight, protected guards await it, and public shell rendering
does not wait for a private request. Expiry and API401 remove access; old request401
cannot clear a newer token. 403/503 do not masquerade as logout.

Cookie mutation admission is service-scoped, not component-scoped: another login/register
is rejected while the prior flow owns an already-dispatched POST. Epochs fence late
profile/callback/CSRF completions. This is not a claim that cancellation reverses a
committed server mutation or that different browser processes are globally serialized.

Logout/password mutations explicitly select the current verified bearer account with
no cookie. The accepted distinct Identity/frontend origins are enforced before these
requests because XHR `withCredentials:false` is not cookie omission for same-origin.
Same-origin custom Identity overrides are deliberately rejected. Cookie login/register
still require CSRF; existing Spring Resource Server already exempts bearer requests.

Local logout always clears ordinary access. Only confirmed server success reports remote
revocation. Explicit retry may retain the captured target token **in memory only** until
expiry/new login; it never becomes active access, never enters storage, and never falls
back to another account's ambient cookie. Expired retry remains unconfirmed. Password
mutation expiration is token-bound, and delayed logout cannot navigate over new login.

Bearer interceptor scope is explicit: canonical Learning Deck paths and exact Identity
profile/userinfo paths only. Legacy Core/User/Media endpoints, lookalike origins/path
prefixes and ambiguous routing encodings do not receive the replacement credential.

## Local configuration and UX

`MNEMA_AUTH_SERVER_URL` is an explicit HTTPS Identity origin;
`MNEMA_IDENTITY_REDIRECT_URI` is the registered frontend HTTPS `/auth/callback`;
`MNEMA_LEARNING_API_BASE_URL` is `/api`. Hosted callback must equal
`MNEMA_PUBLIC_ORIGIN/auth/callback`. Runtime values reject controls without echoing them.
Plain HTTP development does not receive an insecure authentication bypass. The disposable
[browser fixture](../../../../scripts/browser-identity/README.md) supplies two local HTTPS
origins without touching global certificate trust or any server.

Login/register are semantic Russian paper/indigo forms with labels, autocomplete,
busy/disabled states, safe errors, focus on the first invalid control and UTF-8 password
bounds. Error retries retain non-secret input; password clears after submission/destruction.
Callback is lazy. Full replacement shell/Deck routes belong to #194; the screenshot here
still has the legacy outer shell and is not accepted full visual/authoring completion.

## Evidence and failure-first corrections

- Node22.23.2, Chrome153, Java21, disposable PostgreSQL18.4.
- `npm run lint`: PASS. `npm test`: **83 PASS**. `npm run build`: PASS,
  initial682.07kB raw/161.58kB estimated transfer on the pre-shell replacement build.
- Tests cover malformed stored/token/profile/config values, one-use callbacks, exact
  scopes, revoked restore, stale completions, cookie mutation overlap, bearer confinement,
  timeout/failure logout distinction, expiry/retry, guard deep links, form duplicate
  submit, safe errors and byte bounds. Initial form focus tests81PASS/2FAIL exposed stale
  CSS-state selection; authoritative form controls corrected it to83PASS.
- `python3 -m unittest discover -s scripts/tests -p test_browser_identity_config.py`:
  **5 PASS**; included in the existing frontend release-contract gate step.
- Fixture safety10PASS/0skips; Node22syntaxPASS. Independent reviewer reran10 safety
  tests and inspected exact source hashes/real browser artifacts; no blocking delta.
- Real HTTPS browser fixture: **8 scenarios PASS**,3PKCE exchanges,40Identity/216page
  requests,0JSruntime exceptions. Wrong unsolicited/live-state/replayed callbacks,
  register/explicit login, reload verification, logout and two-account isolation.
- Two-account bug reproduced before correction: tabA bearer200/tabB401 after A logout;
  corrected run **A401/B200**. Proxy wire proof: two logout requests, Bearer present,
  Cookie/CSRF absent. Server subject and ambient-cookieB preconditions were checked.
- Initial replay assertion could match an old page's alert; independent finding fixed
  by exact new-loader DOMContentLoaded wait. Earlier replay proof withdrawn; corrected
  full fixture passed. Initial150page-request harness budget was too small for hard SPA
  navigations; explicit300page/100Identity/180second envelope retained bounded execution.
- Owned fixture JVMs/Chrome process groups/container/private keys/cookies/logs were
  removed; sanitized output retains only counts, artifact fingerprints and anonymous PNG.

The browser run is real implementation evidence, not a mocked HTTP client. Its test proxy
is not production nginx/CSP; loopback same-site ports are not production subdomains.
The fixture is a separately executed browser check, not silently counted as one of the
existing33repository steps. Exact candidate/full gate/hosted CI results are recorded in
the PR delivery evidence after execution; these narrower checks do not replace them.

Latest run2026-09-13T10:57:42Z on candidate `aa5cde7a381405ea05355e8618b66bb0edf0a44f`
after API #193 integration uses canonical `/api` config and the latest focus/logout
warning source. Sanitized [browser results](browser-identity/browser.json),
[artifact fingerprints](browser-identity/fixture.json) and [anonymous screenshot](browser-identity/login.png)
are committed. Frontend tree SHA256: `5ca4589b4cd859535786c74db8ad3276dfa62fafe67a5cf6f55aac7f071b09f8`.
Fingerprint means sorted relative file name + NUL + SHA256(content) per asset.

Exact integrated candidate full repository gate: 33/33 PASS, unchanged clean tree;
the separately rerun browser fixture again passed all eight scenarios with full cleanup.

## Residuals and sources

No production/staging/deploy, manual screen-reader, IME, mobile device, cross-browser,
load/soak or complete authoring proof. Two-account password mutation has source/unit
proof, not a separate real-browser password scenario. Remaining legacy product routes
are not authenticated compatibility adapters; they are removed by owning replacement
slices. No automatic/private page prefetch or token refresh is introduced.

- [Spring Security6.5.11 source](https://github.com/spring-projects/spring-security/blob/6.5.11/config/src/main/java/org/springframework/security/config/annotation/web/configurers/oauth2/server/resource/OAuth2ResourceServerConfigurer.java): existing bearer CSRF exemption; no backend weakening.
- [Angular HTTP setup](https://angular.dev/guide/http/setup) and [XHR standard](https://xhr.spec.whatwg.org/#the-send()-method): retained XHR requires distinct-origin credential omission.
- [Angular initializer](https://angular.dev/api/core/provideAppInitializer): nonblocking public startup with awaited protected guard.
- [OAuth security BCP](https://www.rfc-editor.org/rfc/rfc9700.html): PKCE/state, exact redirect and one-use authorization transaction.
