---
artifact:
  id: public-web-seo
  type: contract
  title: "Public web: rendering, search indexing and SEO maintenance"
  status: current
  created_at: "2026-10-07"
  updated_at: "2026-10-08"
  owners: ["project-owner"]
---

# Public web: rendering, search indexing and SEO maintenance

This document owns public-page discovery and rendering. Product copy belongs to the
page components; [brand and UI](./mnema-brand-and-ui-contract.md) owns visual design;
[production delivery](../operations/production-delivery.md) owns rollout. Search
visibility is determined by search engines, independently of the visitor's browser.
Chrome, Firefox, Safari, Edge and Yandex Browser receive the same HTML and assets.

## Route and indexing contract

[`public-pages.json`](../../frontend/src/app/core/seo/public-pages.json) is the
explicit inventory of indexable public pages and their title/description. Today it
contains `/`, `/ai`, `/events`, `/privacy` and `/terms`.

- Every inventory entry has a real Angular route, prerendered HTML, a unique title
  and description, one self canonical at `https://mnema.app`, matching Open Graph
  and Twitter metadata, and a crawlable HTML link from the public shell.
- Every indexable public page must also appear in
  [`sitemap.xml`](../../frontend/src/sitemap.xml), including new static information
  pages. Only public canonical page URLs belong there. Login, registration,
  callbacks, personal decks, profile, plans and editorial routes are excluded.
- Query parameters and fragments never enter canonical URLs or shared metadata.
  No public page points its canonical to the homepage unless it is the homepage.
- `robots.txt` advertises the sitemap and permits page/asset crawling. Private
  routes use `noindex,follow` in their original CSR HTML and an HTTP
  `X-Robots-Tag`. Blocking them in robots.txt would prevent robots reading noindex;
  neither measure substitutes for authentication or authorization.
- Generated `/index.html` and `/ai/index.html`-style delivery filenames redirect
  permanently to their real public routes, preserving query parameters.
- Unknown public URLs return HTTP 404 and an accessible recovery page. URLs within
  the private `/decks` namespace remain client-rendered and noindex; route guards
  and APIs still decide valid resources and account access.
- The current 64 px paper/indigo ICO is served at the stable `/favicon.ico` URL.
  Brand SVG/PNG assets and the favicon revalidate; content-hashed JS/CSS and the
  unchanged local font retain long caching. External favicon caches update on
  their own schedules.

Do not add keyword stuffing, hidden SEO text, fabricated ratings, fake FAQs,
unavailable search actions, bot-specific content or duplicate regional pages.
The homepage `WebSite` identifies «Мнема» and its alternative name «Mnema».
Other public routes describe their actual page with `WebPage` structured data.

## Rendering and the owner's performance constraint

Angular 22.2.1's official `@angular/ssr` and `@angular/platform-server` packages
are development dependencies used to generate HTML during a production build.
`outputMode: static` serves this output through the existing Nginx image; no
Node server or new production service is introduced.

**SEO changes must preserve the existing paper style, component positions,
responsive layout and user flows, and must not introduce a measured regression
in page loading or lazy feature loading.** Do not redesign an existing page as
part of SEO work. A bounded text-wrapping fix for the pre-existing `/ai` overflow
at 320 px/200% root text preserves ordinary page geometry; verify both before
and after rather than dismissing the accessibility failure. Compare against the pre-change production build in the same
browser/environment, inspect actual requested chunks and DOM geometry, and run
the real authenticated flows. Bundle-size estimates alone do not prove runtime
performance. Record test conditions and residual device/browser limits rather
than asserting identical timing for every user.

Public documents reuse the existing components. Native Angular hydration reuses
their DOM. Hydration providers are enabled only when the initial document
contains Angular's prerender state; private direct loads retain CSR. There are no
deferred public blocks or HTTP transfer cache. Browser session restoration starts
after the first render; private guards still await `restore()` immediately.
Server rendering must not access browser storage, credentials or production APIs.

`/events` is **not a static copy of the news database**. Its stable heading,
introduction and public shell are prerendered. Published entries are fetched after
the browser render from the public, no-store API, up to 50 per page. Drafts and
private account data never enter a frontend image. This keeps editorial
publication immediate, but crawlers must execute JavaScript to read entries and
the button paginator does not provide independent crawlable post URLs. If
discoverability of individual announcements becomes a product requirement, add
stable public post URLs and live rendering/feed discovery through a separate
reviewed contract; do not read production data in CI or bake stale records into
each release. Events API and permission truth: [events contract](../../contracts/events/README.md).

## Changing a public page

1. Add/change the actual route and meaningful visible content. Keep semantic HTML,
   a single page heading, ordinary `<a href>` navigation and mobile accessibility.
2. Update `public-pages.json`, the sitemap and Nginx's public route mapping together.
   Do not list redirects, private routes, errors or query-only variants.
3. Run the full [quality gate](../engineering/agent-runbook.md#quality-gate-what-ci-equivalent-means).
   `npm run build` additionally runs
   [`verify-public-seo.mjs`](../../frontend/scripts/verify-public-seo.mjs), checking
   the generated initial HTML, metadata, crawlable links, sitemap, favicon and the
   separate noindex CSR shell. A missing prerendered route or metadata fails the build.
4. Verify direct loads and client navigation, including public → private → public,
   hydration errors, the event API, responsive geometry and lazy private chunks.
   The [HTTPS harness](../../scripts/browser-identity/README.md) serves real
   prerendered files and the CSR shell, rather than substituting the homepage.
5. After an authorized deployment, check public HTTP 200, unknown HTTP 404,
   private noindex headers, initial HTML without JavaScript, sitemap XML, favicon,
   and shared previews on the real origin.

The sitemap intentionally omits `priority` and `changefreq` (Google ignores them)
and `lastmod` until an accurate content-update source exists. Never stamp every
page with the deployment date or claim that an editorial publication regenerated
the static sitemap. A five-URL sitemap needs no database, crawler job or paging.

## Search operations and freshness

The owner should verify the canonical domain in Google Search Console, Yandex
Webmaster and Bing Webmaster Tools, submit `/sitemap.xml`, and inspect the public
URLs with each engine's URL/rendering tools. Verification tokens are configuration;
do not invent them or publish private account data. Search-console changes and
indexing submissions need the owner's authorized account/effect.

After a public-route, canonical, favicon or rendering change, inspect crawling,
chosen canonical, index coverage and rendered content. Review impressions/clicks,
relevant queries and Core Web Vitals over sufficient real traffic, rather than
judging ranking from a single personalized search. Recheck diagnostics after a
release, and official recommendations quarterly or when a search engine reports
a material change. The document review date is a maintenance record, not a sitemap
modification date. Do not add analytics or recurring jobs merely to keep this file
fresh. Correct technical indexing does not guarantee a ranking position or a
fixed recrawl time; useful public content and real references remain necessary.

## Primary sources checked on 2026-10-07 and 2026-10-08

| Source | Decision it informs |
|---|---|
| [Google SEO starter guide](https://developers.google.com/search/docs/fundamentals/seo-starter-guide) | Useful visible content, crawlable structure, descriptive titles and mobile experience. |
| [Google JavaScript SEO](https://developers.google.com/search/docs/crawling-indexing/javascript/javascript-seo-basics) | Original HTML with usable public content, noindex boundaries and JavaScript rendering limits. |
| [Google canonical URLs](https://developers.google.com/search/docs/crawling-indexing/consolidate-duplicate-urls) | Self canonicals, one preferred origin and agreement between initial HTML and client navigation. |
| [Google sitemap guidance](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap) | Only canonical indexable URLs; no invented update dates, frequency or priority. |
| [Google favicon](https://developers.google.com/search/docs/appearance/favicon-in-search), [site names](https://developers.google.com/search/docs/appearance/site-names) | Stable current square ICO and factual bilingual WebSite identity. |
| [Yandex page appearance and JavaScript](https://yandex.ru/support/webmaster/ru/robot-workings/vision), [sitemap](https://yandex.ru/support/webmaster/ru/indexing-options/sitemap) | Titles/descriptions, semantic content, rendering diagnostics and sitemap discovery. |
| [Bing sitemap guidance](https://blogs.bing.com/webmaster/2025/7/Keeping-Content-Discoverable-with-Sitemaps-in-AI-Powered-Search/) | Complete XML discovery, accurate content modification dates, robots.txt and webmaster diagnostics. IndexNow is a possible future authorized integration, not a replacement for a sitemap. |
| [Angular hybrid rendering](https://angular.dev/guide/ssr), [hydration](https://angular.dev/guide/hydration), [TitleStrategy](https://angular.dev/api/router/TitleStrategy) | Native build-time prerender, reuse of existing DOM, server-safe providers and metadata on navigation. |
| [Core Web Vitals thresholds](https://web.dev/articles/defining-core-web-vitals-thresholds) | Assess LCP/INP/CLS from real traffic; synthetic comparison is implementation evidence, not field-user coverage. |

Browser response security follows the [current CSP contract](../operations/browser-security-headers.md)
and the [HTML data-block semantics](https://html.spec.whatwg.org/multipage/scripting.html#the-script-element).
JSON-LD and hydration state are parsed inert data; no inline executable-script exception is introduced.
