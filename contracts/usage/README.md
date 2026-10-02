# Usage contract v1 (`usage-v1`)

Credits, the versioned rate card, plan allowances, the usage API and the estimate that the composer shows before it spends
anything. **Status: implemented** in `app.mnema.learning.usage` by AI-01 ([#281](https://github.com/MattoYuzuru/Mnema/issues/281)); the
paywall, entitlement inbox and promo codes are AI-19 ([#301](https://github.com/MattoYuzuru/Mnema/issues/301)) and
AI-21 ([#302](https://github.com/MattoYuzuru/Mnema/issues/302)); payments are #79.

Authority: [AI generation platform §10](../../docs/architecture/ai-generation-platform.md) and
[AI layer product contract §5](../../docs/product/ai-layer-2026-10.md). **AI-01 (#281) owns `estimateGeneration`**: the endpoint, the
rate-card math and the reservation. The generation module supplies the spec interpretation (which operations and counts a spec
implies) and the personal-data scan hooks as they land (AI-04 and later). The endpoint is declared in
[`../generation/http.json`](../generation/http.json); its shapes live here.

| File | Content |
|---|---|
| [`rate-card-v1.json`](rate-card-v1.json) | `rateCardVersion: "rc-v1"`; credit weights per operation, copied from the product contract; estimation rules; edit action to operation mapping |
| [`allowances-v1.json`](allowances-v1.json) | Plans FREE, PLUS, PRO, MAX: credit bar and schedule, fair-use buckets, count caps, calendar zone |
| [`usage.json`](usage.json) | `GET /api/usage`, estimate request and response, reservation, ledger, error shape, with examples |

Conventions are those of the existing contracts: private/no-store, quoted decimal versions, RFC 9457 Problem Details with a
stable `code`. Credits are integers; timestamps are UTC RFC 3339.

## Model

- **Credit** — an internal cost-weighted unit: 1 credit = 0.10 RUB of p95 cost. The user sees percentages and
  approximate counts ("about 40 materials a month"), never credits as money. Weights are **not** a tariff contract.
- **Rate card** — versioned. Weights are revised after **14 days of measurements** and a revision publishes a new
  `rateCardVersion`; it **never applies retroactively** to ledger entries or reservations that already carry `rc-v1`.
  It holds worst-case weights only.
- **Estimate.** `p95` = the sum of the rate card weights of the operations the spec implies (what a reservation holds).
  `p50` = the same formula with typical weights = weight × 0.6 per operation, rounded up once on the sum, until measured
  weights exist (the 0.6 is an rc-v1 placeholder). Exercises are pro-rated per exercise: `ceil(8 × n / 5)`. The estimate prices only
  the media counts the spec declares, and the MBM compiler rejects excess (at most 8 media directives per artifact).
- **Edit action → operation:** `REWRITE`, `FREE` → `EDIT_SELECTION`; `IMAGE_SEARCH` → `IMAGE_SEARCH`; `IMAGE_GENERATE` →
  `IMAGE_GENERATE_*`; `AUDIO_REGENERATE` → `TTS_CLIP_30S`; `REMOVE_MEDIA` → free (0, no reservation).
- **Bar.** One "AI budget" bar for creation. Paid plans get the whole month at once; the **daily burst** limits the **debits** of one
  calendar day to 35% of the bar (not reservations): admission checks the monthly availability only, a running step whose debit
  would exceed the burst completes, and further steps that day wait in `READY` with `next_attempt_at` at the next day start
  (`USAGE_UPDATED.deferredUntil`). No rollover; the "+50% boost" is not v1.
- **Fair-use buckets** outside the bar: speech-to-text (metered in **seconds**, shown as whole minutes rounded up; MAX is
  `limit: null` with a `velocityPerDay` of 120) and AI answer checks, with monthly and daily limits; quiet counters that appear
  above 80%. **Count caps** for podcasts, quality images, high-effort fact check and smart plans.
- **Ledger** — append-only (`GRANT`, `DEBIT`, `REFUND`, `ADJUSTMENT`, `EXPIRE`) with `costMicros`, `rateCardVersion` and `periodId`;
  the balance is materialized in the same transaction.
- **Entitlement** — consumption lives in Learning; purchases, promo codes and periods live in the future billing context,
  which publishes an entitlement snapshot (plan, period, allowances, `valid_until`) idempotently. Until then an
  `EntitlementSource` port has a configuration implementation (`source: CONFIG`). A browser return URL never changes
  entitlements.
- "Spend X% on this deck": `budget = X% × current remaining` becomes the reservation cap and the planner input.

### Reservation lifecycle

A reservation is a hold on the balance, scoped to one admission (`SESSION`, `TURN` or `STEP`), with a `periodId`.

- The **session reservation covers the initial batch only.** When the session enters `REVIEW` (no `QUEUED` or `GENERATING` artifact)
  the unspent remainder is released: the reservation becomes `SETTLED` (it recorded at least one debit) or `RELEASED` (none).
- **Every later chargeable action** (an edit turn, `retryArtifact`, a media redo, `AUDIO_REGENERATE`, an `IMAGE_SEARCH` edit)
  creates **its own small reservation** at admission. If it does not fit, the answer is `409 USAGE_LIMIT_REACHED` **before any state
  change**. `REMOVE_MEDIA` is free and needs none.
- **Renewal.** `UsageLedger.renew(owner, reservationId)` keeps a live hold alive: it sets `expiresAt = min(now + learning.usage.reservation-ttl,
  period end)`, never shortens it and never moves it past its period, so repeating it is harmless. The step scheduler (AI-04) calls it for
  a session whose steps are deferred by the daily burst or still running; on a hold that has ended it fails with
  `ReservationNotActiveException`. A hold the scheduler stops renewing expires by the sweep as an orphan.
- A reservation carries `periodId`; its debits draw from that period; it never outlives its period
  (`expiresAt = min(learning.usage.reservation-ttl, period end)`); at period rollover every `ACTIVE` hold is released and the remaining
  steps re-reserve in the new period. A session that is `CLOSED`, `CANCELLED`, `EXPIRED` or deleted after at least one debit settles
  (`SETTLED`), otherwise it is `RELEASED`.
- **Never negative.** Admission is one conditional update on `usage_balance` inside the admission transaction
  (`UPDATE … SET reserved = reserved + :hold, row_version = row_version + 1 WHERE available >= :hold AND row_version = :v`); zero rows
  updated is `409`. Two concurrent admissions cannot both pass.
- Refusals the caller must handle are not all alike: `USAGE_LIMIT_REACHED` rolls the admission transaction back (nothing may change), while the
  over-run (`EstimateExceededException`) and a debit on an ended hold (`ReservationNotActiveException`) are thrown before any write and leave
  the caller's transaction usable, so it can record the failure and commit.
- An over-run of the hold fails the artifact with `ESTIMATE_EXCEEDED` (a retry re-reserves); a limit that cannot cover the call fails
  with `USAGE_LIMIT`. A provider-side failure is not debited; a repair inside a successful step is.

### Error shape

`USAGE_LIMIT_REACHED` (409) carries `bucket`, `window` (`DAY|WEEK|MONTH`), `unit` (`CREDITS|MINUTES|COUNT`), `limit`, `used`, `required`,
`offered` (`false`: the bucket does not exist on this plan, waiting never helps), `renewsAt` (null when waiting will not help),
`fitsAfterRenewal` and `plan`. When `canStart` is `false` the estimate returns the same entries as `blockingBuckets[]`. The client
never disables "Create" in advance: pressing it explains the options. See
[`../generation/errors.json`](../generation/errors.json) and the examples in [`usage.json`](usage.json). Problem extension members
are added through a small typed `ProblemExtension` map in `platform.api`; AI-01 is its first user.

## Owner decisions (2026-10-02)

Final; each value lives in a config key so a change is a configuration change.

| Decision | Value | Key |
|---|---|---|
| Free weekly portions | 13, 13, 12, 12 credits (sum = the 50 bar). They **accumulate within the calendar month** and nothing carries over to the next month. The first unlocks on the 1st of the period, the next on each following Monday 00:00 Europe/Moscow until the bar is fully unlocked (a month with a fifth Monday gets no extra). Unlocked but unspent credits stay usable until the period ends. An operation larger than the unlocked balance is `409 USAGE_LIMIT_REACHED` with `renewsAt` = next unlock and `fitsAfterRenewal` computed (for example `MATERIAL_DETAILED` 22 fits after the second portion) | `learning.usage.free-weekly-portions` |
| Daily burst (paid) | Limits actual debits per calendar day; admission checks monthly availability only; steps that would exceed it wait for the next day start | `learning.usage.daily-burst-fraction` = `0.35` |
| Calendar zone | Europe/Moscow for every day, week and month boundary | `learning.usage.calendar-zone` = `Europe/Moscow` |
| Exercise generation | Explicit refusal, no silent clamp: at most 20 targets, 1 to 10 exercises per target, at most 60 exercises per session; above that `422 RESOURCE_LIMIT_EXCEEDED` with the limits; the client offers to split into several sessions (uncovered materials first) | `learning.generation.max-exercise-targets`, `max-exercises-per-target`, `max-exercises-per-session` |

Implementation default (not an owner decision): `percentUsed` for Free is `(used + reserved) / 50`, holds included.

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
| `SMART_PLAN_FLASH` | 20 | smartPlan | AVAILABLE | Smart plan (Flash with thinking) |
| `SMART_PLAN_PRO` | 75 | smartPlan | AVAILABLE | Smart plan (Pro with thinking) |
| `STT_MINUTE` | fair-use | stt | AVAILABLE | One minute of speech-to-text |
| `TTS_CLIP_30S` | 10 | - | AVAILABLE | Text-to-speech clip of 30 seconds (about 400 characters) (0 on a cache hit) |
| `PODCAST_3MIN` | 75 | podcasts | AVAILABLE | Podcast of 3 minutes |
| `IMAGE_SEARCH` | 1 | - | AVAILABLE | Licensed image search |
| `IMAGE_GENERATE_ECONOMY` | 15 | - | LATER | Economy generated image |
| `IMAGE_GENERATE_QUALITY` | 60 | qualityImages | LATER | Quality generated image |
| `FACTCHECK_LOW` | 15 | - | AVAILABLE | Fact check, low effort (15 queries) |
| `FACTCHECK_HIGH` | 120 | highFactcheck | AVAILABLE | Fact check, high effort (15 queries) |
| `VIDEO_5S` | 250-500 | video | DEFERRED | Video clip of 5 seconds |

## Plans

Product numbers copied verbatim; `0` means not offered. Max is a teaser behind a feature toggle until TTS and images exist.

| Plan | Price RUB/month | Credits/month | STT min/month (per day) | Answer checks/month (per day) | Podcasts | Quality images | High fact check | Smart plan |
|---|---|---|---|---|---|---|---|---|
| `FREE` | 0 | 50 | 60 (10) | 50 (5) | 0 | 0 | 0 | none |
| `PLUS` | 449 | 360 | 300 (30) | 500 (40) | 2 | 0 | 2 | 4 / month |
| `PRO` | 990 | 820 | 600 (60) | 1000 (80) | 6 | 10 | 5 | weekly |
| `MAX` | 1900 | 1780 | unlimited (velocity 120/day) | 1500 (120) | 15 | 25 | 12 | weekly + 4 Pro |

Other columns of the product table (low fact check on every plan; images: search only, later generation) are in
[`allowances-v1.json`](allowances-v1.json). The guarantee behind the numbers: the sum of caps stays at most 25% of the price after
income tax (NPD 4%) and acquiring (about 3%); after two cohorts the bar is re-based on measured p95 (x1.35-1.5).

## Implementation notes (AI-01)

- `learning.usage.reservation-ttl` defaults to `PT2H` (at least the `PT1H` bound of one step run plus margin), always capped at
  the period end.
- The ledger is internal (no HTTP read). `entryFields` describe its information, not a column list: `sessionId`, `stepId` and
  `attempt` are stored in one opaque `reference` token (for example the debit key `debit:{stepId}:{attempt}`), so the row
  carries no domain foreign keys.
- Only `GRANT` and `DEBIT` entries are produced in v1; balances are per period, so nothing needs an `EXPIRE` entry at period end.
  `REFUND` and `ADJUSTMENT` are accepted by the schema for billing (#79) and support corrections.

## Open questions

- A trial is undecided in the product contract; promo codes cover trial-like offers (AI-21).
