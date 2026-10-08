---
artifact:
  id: public-seo-verification-2026-10-08
  type: verification
  title: "Public SEO and cleanup verification"
  status: historical
  created_at: "2026-10-08"
  updated_at: "2026-10-08"
  owners: ["project-owner"]
---

# Public SEO and cleanup verification

Frozen implementation evidence, not the live deployment record or a ranking guarantee.
Canonical behavior: [public web SEO](../../../frontend/public-web-seo.md).
The tested code diff against `0be432371dd6d631ef9caab53c983a62765a20bb`
has SHA-256 `f68ba138c61222ca2017ec917334ced8efd6db164c4b23cc1157fff14c2ad413`.
This evidence/navigation-only addition follows those tests.

## Validation

- JDK 25 `clean quality`: Identity 124 and Learning 1964 tests, no failures;
  Learning's 19 opt-in live provider/evaluation/media tests remain intentionally
  skipped. Coverage 92.56%/95.25%, both floors passed. An initial unchanged worker
  handover test failed; isolated 3 tests and the full repeated gate passed.
- Node 24.21.0 `npm ci`, lint, 2087 frontend tests and production build passed;
  statements 89.86%, branches 85.18%, functions 89.81%, lines 93.72%.
- Five actual prerendered documents, metadata/canonicals, sitemap and separate
  private CSR shell passed the build gate. Deliberately wrong/duplicate canonical,
  public noindex, empty public shell and missing events URL were rejected.
- 250 repository script tests and 40 browser-fixture tests passed. Documentation,
  immutable action/image pins and automation/security/artifact policies passed.
- Pinned Nginx staging/production response/CSP contracts passed, including real
  404 and private noindex/no-store. Production-source listener 18080 with
 `Host: mnema.app` returns relative 308 canonical aliases and preserves query
 parameters. Five adversarial HTML/CSP tests reject executable inline scripts,
 duplicate attributes/data blocks and malformed JSON.
- Full real HTTPS Identity/Learning/PostgreSQL18/MinIO/media-worker browser
 harness: 48 scenarios passed in Chrome 154.0.8037.98, zero runtime errors and
 zero external requests. It covers login/account isolation, lazy editors,
 authoring, media, all exercise mechanics, Study, public AI/footer, owner-only
 event drafts/publication and responsive geometry.

## Interface and performance comparison

The baseline browser build uses the original source at `0be43237`. Its initial
local copy missed the tracked WOFF2 asset; that copy was repaired from the exact
Git blob (`fd25500bc842f4758f1e7c31e89511f4ca1e2718930f903c1ae55b7d2a9de5ef`)
before the final comparison. The same font is available on production.

Five alternating before/after cold-cache trials per route, local gzip HTTP,
Chrome CPU 4x, per-request network 40ms/200000 B/s, viewport 390 px. Medians:

| Route | FCP before → after | Observed LCP before → after | JS encoded bytes before → after |
|---|---:|---:|---:|
| `/` | 236 → 128 ms | 236 → 128 ms | 151131 → 163302 |
| `/ai` | 164 → 88 ms | 196 → 88 ms | 155466 → 167656 |
| `/login` | 200 → 208 ms | 200 → 208 ms | 151131 → 163302 |

These are synthetic observations, not field Core Web Vitals or an assurance of
zero latency change on every device. Native hydration adds approximately 12 KB
of compressed browser JS. Public paint improved; the private sample differs by
8 ms within overlapping run variability. Private feature chunks remain lazy
and were not requested during public startup. INP and real-user latency were
not measured.

After fonts/rendering settle, headings, main content and footer rectangles and
visible text match exactly before/after on `/`, `/ai` and `/events` at 1440 and 390 px.
An existing `/ai` overflow at 320 px/200% root text was reproduced on both builds
and corrected by one inherited `overflow-wrap: anywhere` declaration. Normal
page geometry remains unchanged; the repeated full harness passes the enlarged
text case. [Normal mobile AI](./ai-public-320.png),
[footer with doubled root text](./public-footer-320-2x-text.png).

Physical devices, Safari, Firefox, Yandex Browser, assistive technology,
search-console ownership, indexing submissions and ranking changes are not
claimed by this evidence. `/events` entries remain runtime JavaScript/API data;
only its stable introduction is prerendered.
