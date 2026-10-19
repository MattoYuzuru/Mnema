# Link previews without SSR

Status: current (Share/14, #436). Decision: [community decks architecture, section 9 (CD-10)](../architecture/community-decks.md#9-ссылки-og-rate-limits-cd-10);
product behaviour: [«Ссылки и «Поделиться»»](../product/community-decks.md#ссылки-и-поделиться).

Telegram, VK and search engines read the HTML of a link and run no JavaScript. The Angular shell has one `index.html`, so before this change every
`/d/{code}/{slug}` showed the home card, `index,follow` and the home canonical. The web server now asks Learning for the card of the deck and puts it
into the head of `index.html`; there is no server-side rendering and no change to the application.

## Result

| Address | What a crawler gets |
|---|---|
| `/d/{code}/{slug}` of a PUBLIC deck | its title, description (about 200 characters), author (when Identity has the public card), `canonical`, `og:*`, `twitter:*`, `index,follow` |
| `/d/{code}` or a wrong slug of a PUBLIC deck | one `301` to `/d/{code}/{canonical slug}`, the query string kept (`?material=…&utm_source=…`) |
| LINK, INVITE, private, rotated, deleted, unknown, malformed, never published | the same generic Mnema card for all of them, `noindex` (robots meta tag and `X-Robots-Tag: noindex`), no canonical, no redirect (the answer is not an oracle) |
| any failure of the call (Learning down, slow, disabled, limited) | the unchanged `index.html`, `200`, exactly as before this change (its own validators and cache policy) |
| everything else (`/`, `/login`, assets, `/api`, `/app-config.js`) | untouched |

`/u/{username}`: the author page is a route of the application (`/u/:username`), but the web server does not wire a preview for it. Learning answers
`/api/public/meta/profiles/{username}` with the generic card for every login and does not ask Identity, because a real card would need one by-username
lookup per anonymous request against Identity's 60 per 15 minutes per address budget, and an anonymous caller could drain it. A link to `/u/{username}`
therefore gets the unchanged `index.html` and the application renders the page.

## Design and why

Learning owns the facts (access level, published title and description, slug, consent of the author). The web server only inserts them.

```
crawler ── GET /d/CODE/slug?q ──> nginx location ~ ^/d/
                                  1. auth_request /__mnema_og_gate        GET Learning /api/public/meta/decks/CODE/head   (cached per code, see below)
                                       X-Mnema-Canonical differs from the requested path -> 301 to it, query kept       X-Mnema-Preview: redirect
                                       401 / 403 / any other failure                      -> plain /index.html (200)    X-Mnema-Preview: fallback
                                  2. @mnema_og_page: ssi on, serve index.html                                           X-Mnema-Preview: card | generic
                                       <!--# include virtual="/__mnema_og_head" stub="mnema_head" -->
                                            the same cached answer -> escaped <head> fragment
                                            empty or any status >= 300 -> stub = the default block
```

`frontend/src/index.html` wraps its default title, description, robots, canonical, `og:*` and `twitter:*` in a SSI `block` named `mnema_head` and
follows it with the `include`. Where SSI is off (home, other routes, `ng serve`, any static host) the block is ordinary markup and the include is a
comment: the page is byte-for-byte what it was. Where it is on, the block is never printed in place; it is the `stub`.

Official documentation that decided it:

- [ngx_http_ssi_module](https://nginx.org/en/docs/http/ngx_http_ssi_module.html): `include virtual` takes the result of another request; `stub` names a
  `block` printed when the subrequest has an empty body or fails; `ssi_silent_errors` hides the SSI error text. Real behaviour checked with the pinned
  image: SSI prints the body of **any** answer, so an error page of Learning would land in `<head>`; therefore the include location turns every status of
  300 and above into an empty body ([`proxy_intercept_errors`](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_intercept_errors) plus
  [`error_page`](https://nginx.org/en/docs/http/ngx_http_core_module.html#error_page) to `return 204`).
- [ngx_http_auth_request_module](https://nginx.org/en/docs/http/ngx_http_auth_request_module.html): the subrequest result gates the request (2xx
  continues, 401/403 deny, anything else is an error) and `auth_request_set` copies a response header into a variable. The canonical path travels in
  `X-Mnema-Canonical`, compared with the requested path by a `map` (a lookahead with a backreference), so nginx decides and Learning answers the same
  thing for every address of a deck. A redirect must be decided before the first byte, so SSI cannot do it, and the gate runs in the access phase; `return 301` is a
  rewrite-phase directive, so the page continues in a named location after `try_files` (rewrite-phase `if` after the access phase). `auth_request` accepts
  only a fixed URI, which is why the code and slug are read from `$request_uri` by [`map`](https://nginx.org/en/docs/http/ngx_http_map_module.html) in
  the subrequests too.
- [ngx_http_core_module](https://nginx.org/en/docs/http/ngx_http_core_module.html): `absolute_redirect off` (the redirect is the relative `/d/...`,
  not `http://127.0.0.1:18080/...`), `internal`, named locations, `try_files`.

Rejected:

- **njs (`ngx_http_js_module`)**: the module is in the pinned image, but `load_module` belongs to the main context
  ([ngx_core_module](https://nginx.org/en/docs/ngx_core_module.html#load_module)) while production mounts only `conf.d/default.conf`; it would need a
  second host file and an image/host coupling in the install order (a host file with `js_import` fails `nginx -t` on the old image). SSI, `map` and
  `auth_request` are compiled into the base image, so the new host file also works with the old image and the old backend.
- **`sub_filter` with `auth_request_set` variables**: the card can only travel in a response header (limited size, ASCII), the several default tags
  cannot be removed as one block, and there is no failure fallback.
- **SSR / a Caddy plugin**: CD-10 rejects Angular SSR for now; Caddy cannot edit the body without a plugin.

## Learning contract

Wire contract and examples: [`contracts/decks/public-meta.json`](../../contracts/decks/public-meta.json); implementation and tests:
[Learning guide, link previews](../../backend/services/learning/guide.md#link-previews-share14-436).

- `GET|HEAD /api/public/meta/decks/{code}` (JSON) and `/head` (the HTML fragment); `/profiles/{username}` and `/head`. No query parameter changes an answer.
- Same public chain, limiter, bulkhead and kill switch (`learning.community.public-routes.enabled`) as the deck reads, so the routes are `404` until
  the owner switches public reads on. The answer is a guest's and never depends on a token (a present token is validated like on every public route,
  then not used): `Cache-Control: public, max-age=300`.
- The fragment contains only the fixed `title`, `meta` and `link` tags; every value is plain text escaped by `PreviewHead.escape` (`& < > " '`), so a
  title such as `"><script>` cannot leave its attribute, open a tag, a comment or an SSI directive. Texts are one line, and bidirectional formatting
  characters (U+200E/F, U+202A–202E, U+2066–2069, U+061C) are dropped, so a title or a name cannot reorder the text around it in a messenger card.
- The fragment is reachable on the application origin (`/api/...`), so it is served as `text/plain;charset=UTF-8` with
  `Content-Security-Policy: default-src 'none'; sandbox`: a browser shows it as text and never runs it. SSI prints the bytes whatever the type.
- Response headers for the web server: `X-Mnema-Preview: card|generic`; for a PUBLIC deck `X-Mnema-Canonical: /d/{code}/{slug}`; for the generic card
  `X-Robots-Tag: noindex`.
- The author comes from Identity's public card (`/api/accounts/profiles/{id}`, only with the owner's consent, fields per consent) through a call of its
  own with a 150 ms deadline. A card or its absence is cached 60 s; a failure (timeout, 5xx, 429, malformed) is remembered 5 s, so a struggling
  Identity is not asked again by every preview. A failure leaves the author out and never blocks the card.
- `MNEMA_PUBLIC_BASE_URL` (already set to `https://mnema.app` for Learning in production) is the origin of every canonical address and image.

## Web server configuration

The same block, byte for byte, is in three files (`scripts/tests/test_verify_link_previews.py` pins that): only the upstream line differs.

| File | Used by | Learning upstream |
|---|---|---|
| `deploy/production/nginx.conf` | production frontend container (host network), installed by the administrator | `set $mnema_meta_upstream 127.0.0.1:18082;` |
| `frontend/nginx.conf` | the standalone frontend image | generated `conf.d/mnema-og-meta.inc` from `MNEMA_OG_META_UPSTREAM=host:port` (and `MNEMA_OG_META_RESOLVER=<IPv4>` when it is a name, validated to four octets of 0 to 255) |
| `deploy/local-full-stack/nginx.conf` | the local full stack | `set $mnema_meta_upstream learning:8080;` (resolver `127.0.0.11`) |

**Standalone without an upstream** (`MNEMA_OG_META_UPSTREAM` unset, the default): the feature is off and Learning is never called. `/d/...` pages are still
answered by the preview location, so they are `index.html` processed by SSI with the stub: the same bytes as the shell, but without
`ETag`/`Last-Modified`, with `X-Mnema-Preview: fallback`, and no redirect for a wrong slug.

**Bounds and hygiene.** Connect 100 ms, send and read 300 ms, no retry. No request header is forwarded (`proxy_pass_request_headers off`: no cookie, no
credential, no `Accept-Encoding`) except `X-Forwarded-For: <incoming>, <peer>`, so Learning's per-visitor limit sees the visitor (Learning trusts
`X-Forwarded-For` from loopback only; Caddy supplies the visitor's address). The URL is built only from the code (10 base58 characters) that the map
validated. The redirect target is the canonical path Learning named, accepted only with the exact shape `/d/{code}/{slug}` and only when it differs from the
path asked for, and `return 301 $target$is_args$args` keeps the query string. The gate answer's `X-Robots-Tag` (generic card only) is copied onto the page; an
empty value adds no header. `index.html` keeps `Cache-Control: public, max-age=0, must-revalidate` and every generated security header
(`add_header_inherit merge`); SSI removes `Last-Modified`, and `etag off; if_modified_since off;` stop the static file's validators from answering for other
bytes. The JSON-LD block that the CSP hash admits is not touched. A client-side navigation after load runs `PublicPageMeta`, which restores the shell's
`robots` and title constants (not a snapshot of the head, which already holds the card of the URL).

**Gate failures.** A gate answer of 401, 403 or any other failure serves the plain page through `@mnema_og_fallback`; nothing but the one failed call
happened (the include only runs for a page that passed the gate). A 401 of the gate is first turned into a 500, because `auth_request` would otherwise copy
the subrequest's `WWW-Authenticate` onto the page.

**One cached call per deck.** The gate and the include read the same answer from a small nginx cache (`proxy_cache_path` in each file, zone `mnema_og`, 1 MiB of
keys, 4 MiB on disk, key = the validated code): [`proxy_cache_lock`](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_cache_lock) lets one
request ask Learning while the others wait up to 300 ms. TTL: 60 s for a card, 10 s for Learning's 404 (routes disabled) and 500/502/503/504 (which also sheds
load from a busy Learning). A 429 is **not** cached: it is a refusal for one visitor's rate limit, and in a shared cache it would become every visitor's. So a
deck page costs Learning at most one request per deck per minute however many visitors look at it, and a failing gate exactly one request; a visitor's
120-a-minute budget is spent only on cache misses. The cache directory is created by the nginx master under the production container's capability set
(`CHOWN SETUID SETGID`), which `scripts/test-link-previews.sh` reproduces. Nothing the visitor chooses enters the key, so scanning random codes cannot grow it
beyond `max_size`, and each miss is charged to the scanner's own limit in Learning. `proxy_ignore_headers` makes Learning's `Cache-Control` irrelevant here.

**`X-Mnema-Preview`** on the page (no ids, no codes): `card` (Learning named a PUBLIC deck), `generic` (the generic card), `redirect` (the 301), `fallback` (the
unchanged shell served from a `/d/` address: gate failure, a shape that is not a deck address, standalone without an upstream). Routes outside `/d/` do not
carry it. It is the gate's verdict: if the card itself then fails the page shows the defaults, the header still says `card`. Count it in the proxy's logs or a
probe, not in Learning (its metric is `mnema_public_deck_requests_total{route="meta"}`).

## Verify

```bash
cd frontend && npm run build && cd ..
./scripts/test-link-previews.sh      # three configurations in the pinned nginx image against a stand-in Learning (needs Docker, openssl)
python3 -m unittest scripts.tests.test_verify_link_previews
```

The script covers: PUBLIC deck tags (also with a query), `301` for a missing or wrong slug with the query kept, the generic `noindex` card, empty and failing
gates (503, 429, 404, 403, 401 with a challenge header, no answer), a good gate with a failing card (stub, no error page in `<head>`), non-deck shapes, what
reaches Learning (no cookie or credential, visitor address last), the calls a page view costs (one per code per minute, 404 cached, 429 not, eight parallel
views one call), assets and `/api` untouched, `X-Mnema-Preview`, and the security and cache headers on every kind of answer.

After a release that has public routes on (replace the code with a real PUBLIC deck; the visitor address is whatever you send):

```bash
curl -s -A 'TelegramBot (like TwitterBot)' https://mnema.app/d/<code>/<slug> | sed -n '/<title>/,/twitter:image/p'
curl -sI https://mnema.app/d/<code>            # 301 to /d/<code>/<slug> for a PUBLIC deck, X-Mnema-Preview: redirect
curl -sI https://mnema.app/d/<link-only-code>  # X-Mnema-Preview: generic, X-Robots-Tag: noindex
```

## Operating it

- **Install order** (administrator, from the exact main commit, before the `prod` approval): only `deploy/production/nginx.conf` changes. It works with
  the running image and the running Learning, so install it first; it is picked up when the release recreates the frontend container. **Validate the
  staged file before installing it**, with the running image, so the rollout cannot fail on syntax. Run it after the `scp` of the recipe in
  [production delivery](production-delivery.md#implementation-and-release), from the same shell (`$d` is the staging directory the recipe created), and install only
  if it ends with `test is successful`:
  `ssh mnema "img=\$(sudo docker inspect -f '{{.Config.Image}}' mnema-prod-frontend-1) && sudo docker run --rm --entrypoint sh -v $d/nginx.conf:/etc/nginx/conf.d/default.conf:ro \"\$img\" -c 'touch /etc/nginx/conf.d/security-headers.inc && nginx -t'"`.
  No Caddy, Compose or dispatcher change.
- **Switching it on** is `learning.community.public-routes.enabled` in Learning, the same switch as the public deck view. Production Compose does not set
  it yet; until then every `/d/...` page falls back to the unchanged `index.html` with exactly one call to Learning per deck per 10 s (its `404`, cached).
- **Rollback**: reinstall the previous `nginx.conf` (and let the next deploy recreate the container), or switch the public routes off. The new
  image with the old host file shows the block markers as comments and the defaults as plain markup.
- **Metric**: `mnema_public_deck_requests_total{route="meta",outcome}` with `ok` (public card), `generic`, `disabled`, `rate_limited`, `busy`.
- **Residual risks**: a preview outlives its deck. nginx and HTTP caches hold a card for 60 to 300 s, and **messengers (Telegram, VK) and search engines keep
  the preview they took for much longer and do not re-read it**: after a deck is unpublished or lowered to LINK, or an author withdraws the consent, old
  links in chats still show the old title, description and author until the messenger refreshes them (the owner can ask the messenger's bot to re-read the
  link). A card taken while Identity was slow has no author until then. The by-username lookup of a profile hits Identity's 60 per 15 minutes per address
  limit with Learning's address, which is why nginx does not wire `/u/`.
