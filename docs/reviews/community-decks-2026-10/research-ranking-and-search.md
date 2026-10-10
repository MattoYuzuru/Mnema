---
artifact:
  id: community-decks-research-ranking-search
  type: architecture-research
  title: "Catalog ranking, recommendation foundation and PostgreSQL 18 search"
  status: historical
  created_at: "2026-10-10"
  owners: ["project-owner"]
  source_tasks: ["community decks RFC input"]
  evidence_accessed: "2026-10-10"
---

# Ranking, recommendations and search for the community catalog

Condensed research report for the [community decks RFC](./rfc.md).

Tags:

- **[F]** — verified in the linked source.
- **[I]** — inference or estimate. All thresholds and formula constants are [I] and
  need tuning from logged data.
- **[U]** — unverified.

## 1. Ranking formulas

| Formula | Fit |
|---|---|
| Hacker News `(P−1)^0.8/(T+2)^1.8 × penalties` ([thread](https://news.ycombinator.com/item?id=1781013), [Shirriff](https://www.righto.com/2013/11/how-hacker-news-ranking-really-works.html)) | Item-age decay suits news, not evergreen decks. Borrow only the penalty multiplier. |
| Reddit hot ([source](https://github.com/reddit-archive/reddit/blob/master/r2/r2/lib/db/_sorts.pyx)) | "Newest wins". Borrow only the log damping. |
| Wilson lower bound ([Evan Miller](https://www.evanmiller.org/how-not-to-sort-by-average-rating.html)) | Binary ratio with a denominator. Ranks tiny n near zero. |
| Bayesian average ([IMDb](https://help.imdb.com/article/imdb/track-movies-tv/ratings-faq/G67Y87TFYYP6TWAV)) | Best for tiny data: shrinks toward a prior. |
| GitHub Trending | Unpublished; third parties describe "star velocity". |

Gaming resistance [I]:

- count distinct users;
- exclude the author;
- count "activated" learners (≥3 study days);
- give young or unverified accounts a trust weight;
- damp with `ln(1+S)`;
- recompute in batch and hide the exact score;
- apply a moderation penalty multiplier;
- run a nightly anomaly query.

Recommender spam attacks are a known literature ([survey](https://arxiv.org/pdf/2406.01022)).

Recommended base score [I]:

```
c_u   = trust_u × (1.0·forked30 + 2.0·activated30) × 2^(−days_since_event/14)
S     = Σ_u c_u
Q     = (activated + 10·p0) / (forks + 10),  p0 = 0.25 initially
fresh = 0.6 + 0.4·2^(−days_since_meaningful_update/120)
base  = ln(1+S) × (0.5+Q) × fresh × rank_penalty
```

## 2. Recommendation architecture, scaled down

**Production patterns.**

- **YouTube** ([paper](https://static.googleusercontent.com/media/research.google.com/en//pubs/archive/45530.pdf)).
  A candidate generation → ranking funnel. It corrects for item age, caps the
  examples taken from each user, and notes that offline metrics do not always
  predict A/B results.
- **Amazon item-to-item CF** ([paper](https://www.cs.umd.edu/~samir/498/Amazon-Recommendations.pdf)).
  An offline similar-items table with cosine similarity. Online cost depends only
  on the user's history.
- **Netflix** ([blog](https://netflixtechblog.com/system-architectures-for-personalization-and-recommendation-e081aa94b5d8)).
  Splits work into offline, nearline and online layers.
- **Pinterest.** Retrieval → scoring → ranking → blending with a diversity step
  [U: summaries only].
- **Spotify BaRT** ([RecSys'18](https://research.atspotify.com/publications/explore-exploit-explain-personalizing-explainable-recommendations-with-bandits)).
  Bandits for explore/exploit.

**Mnema mapping [I].**

- **Candidates:**
  - top-N for the user's top three topics;
  - item-item neighbours (later);
  - a fresh pool;
  - the global top.
- **Ranking:** `base × (1 + 0.8·interest(topic)) × (language ok ? 1 : 0.3)`.
- **Re-ranking:**
  - dedupe forks of the same upstream;
  - at most two decks per author or topic in the top 10;
  - exclude decks the user already owns;
  - two explore slots out of 12.

| Stage | Worth it at | Cost |
|---|---|---|
| Popularity per topic/language | Day 1 | Trivial hourly SQL |
| Interest profile over a small taxonomy | ~1k users | ≤20 rows/user; nightly recompute + incremental upsert on fork |
| Item-item co-occurrence | ~2–5k active users, hundreds of public decks | Nightly self-join on `deck_engagement`, per-user cap 50, cosine, top 20 neighbours |
| Embeddings (pgvector) | >20–50k decks or cross-lingual semantic search | Needs an embedding source and a custom image. Filtered ANN can return too few rows ([pgvector](https://github.com/pgvector/pgvector)) |

**Events to collect now [I].**

- An append-only `catalog_event` table (`uuidv7()` is available in PG18,
  [release notes](https://www.postgresql.org/docs/18/release-18.html)) with
  `kind`, `surface`, `position`, `rank_version`.
- Exposure counts kept as daily aggregates.
- `deck_engagement(deck_id, user_id, kind, first_at, last_at, study_days)` as the
  source of counts.

Privacy [I]:

- first-party data only;
- the interest profile is visible to the user and resettable;
- raw events are kept about 13 months, then aggregated;
- counts are shown in buckets;
- 152-ФЗ coverage needs a legal check.

## 3. Topics and languages

- **What others do:**
  - Anki uses about 20 categories plus author tags, with no native-language field
    ([forum](https://forums.ankiweb.net/t/tagging-shared-decks-to-identify-native-language-in-ankiweb/42472));
  - Quizlet goes Subject → Languages → language;
  - Udemy uses three levels and prunes them over time
    ([changelog](https://teach.udemy.com/announcing-taxonomy-improvements/)).
- **Recommendation [I]: faceted.**
  - A curated two-level topic tree (≈12 L1 / 60–100 L2).
  - `content_language` (BCP-47), auto-detected and confirmed by the author.
    Detection on short text is unreliable
    ([Lingua](https://github.com/pemistahl/lingua)), so detect on the title,
    description and a sample of items.
  - Optional `target_language` and `level` (CEFR/JLPT/HSK).
  - Up to five free tags, used for search only.
- **Synonyms:** `topic_alias(topic_id, alias_norm, locale)`, for example
  испанский / spanish / español / espanol / es.

## 4. PostgreSQL 18 search

**Full-text search.**

- The `russian` configuration stems Russian and stems ASCII words with English.
- Add a `simple + unaccent` configuration for Spanish and other Latin-script
  languages ([unaccent](https://www.postgresql.org/docs/18/unaccent.html)). The
  bundled rules map ё→е and leave й intact.
- Use STORED generated tsvector columns with an explicit config. PG18 defaults to
  VIRTUAL, and virtual columns allow built-in functions only
  ([generated columns](https://www.postgresql.org/docs/18/ddl-generated-columns.html)).
- Wrap `unaccent` in an IMMUTABLE function before using it in an expression [U/I].
- Weights: A title, B description, C tags. Use `ts_rank_cd` with normalization 32
  ([controls](https://www.postgresql.org/docs/18/textsearch-controls.html)).
- Ranking reads the vector of every match, so cap the candidate set.
- `websearch_to_tsquery` never raises syntax errors.
- Use GIN indexes; GiST is lossy ([indexes](https://www.postgresql.org/docs/18/textsearch-indexes.html)).

**pg_trgm** ([docs](https://www.postgresql.org/docs/18/pgtrgm.html)).

- `word_similarity` with GIN `gin_trgm_ops` for the `<%` operator.
- **Locale risk [F].** Under a C locale there are no trigrams for Cyrillic or CJK
  ([report](https://github.com/19-84/redd-archiver/pull/119),
  [Tom Lane](https://www.postgresql.org/message-id/21256.1365349406%40sss.pgh.pa.us)).
  Production uses an Alpine image with no explicit locale. Check
  `SELECT show_trgm('Привет')` before relying on trigrams.
- CJK: trigrams are weak on 1–2 character terms. pg_bigm or PGroonga would need a
  custom image.

**Relevance + popularity [I]:**

```
final = (0.6·ts_rank_cd(..., 1|32) + 0.2·word_similarity + 0.2·popularity_percentile) × language factor
```

**Pagination.**

- Browse: a rank snapshot (generation) with cursor `(generation, rank)`.
- Topic filters: index `(generation, topic_id, score DESC, deck_id DESC)`.
- Search: top-K (≈200) candidates, cursor `(score, deck_id)`. It is stable because
  the score is deterministic per query and snapshot.
- Keyset pagination needs a total order ([Use The Index, Luke](https://use-the-index-luke.com/no-offset)).
- PG18 skip scan is not a substitute for ordering across groups.

**When to leave Postgres [I].** More than ~100k public decks, p95 above 200 ms
after tuning, or a need for typo-tolerant as-you-type search, morphology or CJK
segmentation. Options:

- In-Postgres: `pg_textsearch` (BM25, PostgreSQL License,
  [repo](https://github.com/timescale/pg_textsearch)).
- External:
  - Manticore — `stem_ru`, `lemmatize_ru`, GPLv3
    ([morphology](https://manual.manticoresearch.com/Creating_a_table/NLP_and_tokenization/Morphology));
  - Meilisearch — Russian segmentation; stemming not stated
    ([languages](https://www.meilisearch.com/docs/learn/resources/language)).

## 5. Precompute and serving

- **Rank generations instead of a materialized view.**
  - `REFRESH MATERIALIZED VIEW CONCURRENTLY` needs a unique index and allows one
    refresh at a time ([docs](https://www.postgresql.org/docs/18/sql-refreshmaterializedview.html)).
  - Instead, insert a new generation and flip a pointer. Keep the previous
    generation for 24 hours so open cursors stay valid.
- **Counters.** One `deck_engagement` row per user, deck and kind, plus a single
  writer job for `deck_stats`. Never put hot counters on the deck row.
- **Distinct counts.** Exact `COUNT(DISTINCT)` is fine up to roughly millions of
  rows. HyperLogLog ([postgresql-hll](https://github.com/citusdata/postgresql-hll),
  custom build) is needed only much later.

## Evolution path [I]

1. **v1:** popularity per topic + interest profile + PostgreSQL FTS/trigram.
2. **v2** (~2–5k active users / >500 public decks): co-occurrence neighbours,
   explore slots driven by exposure logs, A/B testing of ranker versions.
3. **v3** (>20–50k decks or semantic search): pgvector or BM25.
4. **v4** (>100k decks or p95 above 200 ms): an external engine.

## Gaps

- HN and Reddit blog originals could not be fetched; source code and the HN thread
  were used instead.
- Netflix, Spotify and Pinterest come from summaries.
- Not researched: OpenSearch, Coursera, Russian-hosted engines, Docker or extension
  availability in Russia, 152-ФЗ.
