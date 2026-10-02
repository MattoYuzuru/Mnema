# Usage contract v1 (`usage-v1`)

Credits, the versioned rate card, plan allowances, the usage API and the estimate that the composer shows before it spends
anything. **Status: contract only** — AI-01 ([#281](https://github.com/MattoYuzuru/Mnema/issues/281)) implements it; the
paywall, entitlement inbox and promo codes are AI-19 ([#301](https://github.com/MattoYuzuru/Mnema/issues/301)) and
AI-21 ([#302](https://github.com/MattoYuzuru/Mnema/issues/302)); payments are #79.

Authority: [AI generation platform §10](../../docs/architecture/ai-generation-platform.md) and
[AI layer product contract §5](../../docs/product/ai-layer-2026-10.md). Estimate is requested through the generation
contract ([`http.json`](../generation/http.json) `estimateGeneration`); its shapes live here.

| File | Content |
|---|---|
| [`rate-card-v1.json`](rate-card-v1.json) | `rateCardVersion: "rc-v1"`; credit weights per operation, copied from the product contract |
| [`allowances-v1.json`](allowances-v1.json) | Plans FREE, PLUS, PRO, MAX: credit bar, schedule, fair-use buckets, count caps |
| [`usage.json`](usage.json) | `GET /api/usage`, estimate request and response, reservation, ledger, error shape, with examples |

Conventions are those of the existing contracts: private/no-store, quoted decimal versions, RFC 9457 Problem Details with a
stable `code`. Credits are integers; timestamps are UTC RFC 3339.

## Model

- **Credit** — an internal cost-weighted unit: 1 credit = 0.10 RUB of p95 cost. The user sees percentages and
  approximate counts ("about 40 materials a month"), never credits as money. Weights are **not** a tariff contract.
- **Rate card** — versioned. Weights are revised after **14 days of measurements** and a revision publishes a new
  `rateCardVersion`; it **never applies retroactively** to ledger entries or reservations that already carry `rc-v1`.
  The card prices a *session*, not a call: the first two artifacts at prefix-cache miss, the rest at hit.
- **Bar** — one "AI budget" bar for creation. Paid plans get the whole month at once, with at most 35% of the bar per day;
  Free unlocks a quarter of the bar every Monday and it does not accumulate; there is no rollover; the "+50% boost" is not v1.
- **Fair-use buckets** outside the bar: speech-to-text minutes and AI answer checks, with monthly and daily limits; quiet
  counters that appear above 80%. **Count caps** for podcasts, quality images, high-effort fact check and smart plans.
- **Reservation → debit → release.** Starting a session reserves the p95 estimate in the transaction that creates it
  (insufficient: `409 USAGE_LIMIT_REACHED` with the remaining amount and the renewal date). Before a provider call the
  remaining hold bounds `max_tokens`. A debit is written idempotently in the transaction that stores a step result
  (`debit:{stepId}:{attempt}`). A provider-side failure is not debited; a repair inside a successful step is. A user never
  goes negative. Orphaned holds expire by TTL.
- **Ledger** — append-only (`GRANT`, `DEBIT`, `REFUND`, `ADJUSTMENT`, `EXPIRE`) with `costMicros` and `rateCardVersion`;
  the balance is materialized in the same transaction.
- **Entitlement** — consumption lives in Learning; purchases, promo codes and periods live in the future billing context,
  which publishes an entitlement snapshot (plan, period, allowances, `valid_until`) idempotently. Until then an
  `EntitlementSource` port has a configuration implementation (`source: CONFIG`). A browser return URL never changes
  entitlements.
- "Spend X% on this deck": `budget = X% × current remaining` becomes the reservation cap and the planner input.

## Rate card rc-v1

Weights in credits, from the product contract table. `fair-use` rows are not debited from the bar.

| Operation | Credits | Cap bucket | Availability | Meaning |
|---|---|---|---|---|
| `MATERIAL_SHORT` | 4 | - | AVAILABLE | Short material |
| `MATERIAL_MEDIUM` | 10 | - | AVAILABLE | Medium material |
| `MATERIAL_DETAILED` | 22 | - | AVAILABLE | Detailed material |
| `EXERCISES_PER_MATERIAL` | 8 | - | AVAILABLE | Five exercises for one material |
| `EDIT_SELECTION` | 4 | - | AVAILABLE | Edit by selection or chat turn |
| `ASSESSMENT_ANSWER` | fair-use | assessment | AVAILABLE | AI check of one answer |
| `SMART_PLAN_FLASH` | 20 | - | AVAILABLE | Smart plan (Flash with thinking) |
| `SMART_PLAN_PRO` | 75 | - | AVAILABLE | Smart plan (Pro with thinking) |
| `STT_MINUTE` | fair-use | stt | AVAILABLE | One minute of speech-to-text |
| `TTS_CLIP_30S` | 10 | - | AVAILABLE | Text-to-speech clip of 30 seconds (0 on a cache hit) |
| `PODCAST_3MIN` | 75 | podcasts | AVAILABLE | Podcast of 3 minutes |
| `IMAGE_SEARCH` | 1 | - | AVAILABLE | Licensed image search |
| `IMAGE_GENERATE_ECONOMY` | 15 | - | LATER | Economy generated image |
| `IMAGE_GENERATE_QUALITY` | 60 | qualityImages | LATER | Quality generated image |
| `FACTCHECK_LOW` | 15 | - | AVAILABLE | Fact check, low effort (15 queries) |
| `FACTCHECK_HIGH` | 120 | highFactcheck | AVAILABLE | Fact check, high effort (15 queries) |
| `VIDEO_5S` | 250-500 | video | DEFERRED | Video clip of 5 seconds |

## Plans

Product numbers copied verbatim; `0` means not offered. Max is a teaser behind a feature toggle until TTS and images exist.

| Plan | Price RUB/month | Credits/month | STT min/month (per day) | Answer checks/month (per day) | Podcasts | Quality images | High fact check |
|---|---|---|---|---|---|---|---|
| `FREE` | 0 | 50 | 60 (10) | 50 (5) | 0 | 0 | 0 |
| `PLUS` | 449 | 360 | 300 (30) | 500 (40) | 2 | 0 | 2 |
| `PRO` | 990 | 820 | 600 (60) | 1000 (80) | 6 | 10 | 5 |
| `MAX` | 1900 | 1780 | unlimited within fair use (velocity limit 120/day) | 1500 (120) | 15 | 25 | 12 |

Other columns of the product table (low fact check on every plan; smart plan: none, 4 a month, weekly, weekly plus 4 Pro;
images: search only, later generation) are in [`allowances-v1.json`](allowances-v1.json). The guarantee behind the numbers:
the sum of caps stays at most 25% of the price after income tax (NPD 4%) and acquiring (about 3%); after two cohorts the
bar is re-based on measured p95 (x1.35-1.5).

## Errors

`USAGE_LIMIT_REACHED` (409) with `bucket`, `requiredCredits`, `remainingCredits`, `renewsAt`, `plan`; see
[`../generation/errors.json`](../generation/errors.json) and the example in [`usage.json`](usage.json). The client never disables
"Create" in advance: pressing it explains the options.

## Open questions

- Weekly unlock rounding: 1/4 of 50 credits is 12.5.
- `percentUsed` basis for FREE (`used / total` is used in the examples; `used / unlocked` is the alternative).
- Default `learning.usage.reservation-ttl` and the p50 definition (cache-hit typical cost) are AI-01 decisions.
- A trial is undecided in the product contract; promo codes cover trial-like offers (AI-21).
