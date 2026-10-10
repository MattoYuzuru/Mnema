---
artifact:
  id: community-decks-research-sharing-legal
  type: product-research
  title: "Shared study decks: copy/update models, update review UX, share URLs, Russian legal baseline"
  status: historical
  created_at: "2026-10-10"
  owners: ["project-owner"]
  source_tasks: ["community decks RFC input"]
  evidence_accessed: "2026-10-10"
---

# Shared decks: products, update review, URLs, legal baseline

This is a condensed research report for the [community decks RFC](./rfc.md).

- Facts carry a URL.
- "Inference" marks the researcher's reasoning.
- "Unverified" marks claims not confirmed from a primary source. Some vendor help
  pages (Quizlet, Stack Overflow) blocked the fetcher, so search summaries were
  used there.
- This is not legal advice.

## 1. Comparable products

| Product | Visibility | Copy vs subscribe | Update propagation | Learner review of updates | Public metrics |
|---|---|---|---|---|---|
| AnkiWeb shared decks | Public listing; private sharing = file export | Copy (import .apkg) ([contrib](https://docs.ankiweb.net/contrib.html)) | Author re-shares; counts/ratings kept; no automatic update | Re-import updates notes if note type unchanged; author-deleted cards not removed; 23.10+ choose overwrite/never/merge ([packaged decks](https://docs.ankiweb.net/importing/packaged-decks.html)) | Thumbs up/down, updated date, size |
| AnkiHub | Paid subscription ([FAQ](https://www.ankihub.net/faq)) | Subscribe | Maintainer merges suggestions → all subscribers update on next sync ([maintaining](https://community.ankihub.net/t/maintaining-a-deck/103687)) | No per-change review; local edit protects the field and then receives no updates ([thread](https://community.ankihub.net/t/protect-extra-field-but-only-update-with-new-anking-illustrations/483760)); "reset local changes" escape hatch | Unverified |
| Quizlet | Everyone / classes / password / just me (help, via search) | "Make a copy", independent | None | None | Ratings (web) |
| Knowt | Public / Org / Private / Password ([help](https://help.knowt.com/en/articles/10298070-how-can-i-share-my-flashcards-with-others)) | Duplicate; link recipients can study, not edit | None found | None | "Studied by N", rating, term count (definition unknown) |
| RemNote | Community, link-only, group ([help](https://help.remnote.com/en/articles/6030805-sharing-rems)) | Copy | Re-share updates the shared snapshot at the same link | None | Unverified |
| Memrise community courses | Public courses | Join the course (no copy) | Live author edits | — | Learner counts; popularity-sorted search |

Lessons:

- **Memrise.** It moved community courses off its apps in 2024 because quality
  varied ([blog](https://www.memrise.com/blog/changes-to-the-memrise-app)). Curation
  and moderation are a real cost.
- **AnkiWeb.** It added a login wall after three searches to make bulk mirroring
  harder ([forum](https://forums.ankiweb.net/t/shared-decks-the-current-state-of-ankiweb/45904)).
- **AnkiWeb deck matching.** If the author renames or moves a deck, the update
  can stop matching ([contrib](https://docs.ankiweb.net/contrib.html)). Stable
  identity, which Mnema has, avoids this.

## 2. Update review outside learning

| Analogue | Granularity | Avoiding version explosion | Local edits |
|---|---|---|---|
| Figma libraries | Per component/style or "update all"; "side by side" and "overlay" review ([help](https://help.figma.com/hc/en-us/articles/360039234193-Review-and-accept-library-updates)) | Explicit publish; edits are not live to consumers ([guide](https://help.figma.com/hc/en-us/articles/360041051154-Guide-to-libraries-in-Figma)) | Undocumented; users report "update all" losing overrides ([forum](https://forum.figma.com/suggest-a-feature-11/review-updates-tool-issues-improvement-suggestions-22880)) |
| Figma Community files | Whole-file snapshot; updates do not reach duplicates ([help](https://help.figma.com/hc/en-us/articles/360040035974-Publish-files-to-the-Figma-Community)) | — | Users ask for "get latest" |
| GitHub fork | Branch; "Sync fork" lists upstream commits; conflicts → PR ([docs](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/working-with-forks/syncing-a-fork)) | Git DAG | Conflicts block a clean sync |
| Google Drive "Request access" | Per file. The owner gets requester name, email, file and an optional message ([help](https://support.google.com/drive/answer/16722399)) | — | Rate limits undocumented |

Patterns (inference):

1. Accept/ignore works per entity, with bulk "update all" offered alongside.
2. An explicit publish step keeps consumers from seeing every author edit.
3. Local edits are the unsolved part. Anki protects the edited field, GitHub raises
   a conflict, Figma documents nothing.
4. None of the products found shows a learner a rendered side-by-side diff.

## 3. Share URLs and search engines

| Site | Pattern |
|---|---|
| Quizlet | `/{numeric-id}/{slug}-flash-cards/` |
| Notion | `Title-{32 hex}`, ID = trailing 32 characters ([dev docs](https://developers.notion.com/docs/working-with-page-content)) |
| YouTube | 11 characters from a 64-symbol alphabet (secondary sources) |
| Figma Community | `/community/file/{id}/{slug}` |
| GitHub | `/{owner}/{repo}`. Rename redirects until the old name is reused ([docs](https://docs.github.com/en/repositories/creating-and-managing-repositories/renaming-a-repository)) |
| Stack Overflow | `/questions/{id}/{slug}`. The ID-lookup-plus-redirect behaviour was not verified first-party |

Search engines:

- **Google.**
  - Prefer readable words to long IDs. Transliteration is acceptable. Use hyphens.
    Percent-encode non-ASCII characters ([URL structure](https://developers.google.com/search/docs/crawling-indexing/url-structure)).
  - Redirects and `rel=canonical` are strong signals, a sitemap is weak. Use a
    self-referencing absolute canonical ([canonicalization](https://developers.google.com/search/docs/crawling-indexing/consolidate-duplicate-urls)).
  - Keywords in URLs are reported to be a "very small ranking factor" (secondary
    report).
- **Yandex.**
  - Recommends human-readable URLs ([hub](https://yandex.ru/dev/hubs/webmasters/)).
    Cyrillic is allowed and transliteration is optional; be consistent
    ([B2B explainer](https://b2b.yandex.ru/adv/edu/materials/chto-takoe-url-adres-sayta)).
  - Treats a canonical as a recommendation and ignores it in chains, on another
    domain and similar cases. A 301 is the stronger signal ([canonical](https://yandex.ru/support/webmaster/ru/robot-workings/canonical)).
  - Recommends `rel="ugc"` for user links ([links](https://yandex.ru/support/webmaster/en/recommendations/links)).

Recommended pattern for a UUID-keyed entity (inference):

- Route `/d/{shortId}/{slug}`.
- `shortId` is random and stored in its own unique column. Do not derive it from a
  UUIDv7, which would leak creation time.
- A missing or wrong slug → 301 to the canonical URL, plus a self-canonical.
- The slug is derived from the title at render time.
- Private and restricted URLs: ID only, `noindex`.
- Deleted IDs return 404/410 and are never reused.

## 4. Russian legal baseline (design input, not legal advice)

**152-ФЗ, art. 10.1 — data the subject allows to be disseminated.**

- Consent is executed separately from other consents. The subject chooses data
  per category and may set restrictions the operator must honour
  ([КонсультантПлюс](https://www.consultant.ru/document/cons_doc_LAW_61801/591acc70f577873c1ee54765eda110b7a0271eaf/),
  via summary).
- Without explicit "no restrictions", the data may not be made public.
- The operator publishes the conditions within three working days.
- Dissemination stops on demand.
- Content requirements: RKN Order No. 18 of 24.02.2021 (the full text was not
  read).
- An RKN reply letter treats login and nickname as personal data. This is an
  individual reply, not a binding act.
- Inference: a public profile needs a separate consent that is unchecked by
  default, set per field, withdrawable and recorded. Counsel must review the
  wording.

**149-ФЗ.**

- **Social network status (art. 10.6)** starts at more than 500k daily users from
  Russia. Rules must set a complaint review period of at most 30 days
  ([art. 10.6](https://www.consultant.ru/document/cons_doc_LAW_61798/a3cba9a7c2ac9aa487df2d4172734dd5139376f5/)).
  Mnema is far below the threshold, but the 30-day period is a sensible template.
- **ОРИ (art. 10.1).** Practitioners say messaging or comment features can make a
  site an organizer of information dissemination
  ([MMDC](https://mmdc.ru/blog/2021/09/13/kto-takie-ori/), interpretation). A
  catalog without comments or messages is lower risk.

**ГК РФ art. 1253.1 (information intermediary).** No liability without knowledge,
provided the platform acts promptly on a rights holder's written notice
([Garant](https://base.garant.ru/10164072/fe5a21d3389ec24123913f91955ccffc/), via
summary).

**MVP minimum (inference):**

- a report button on decks and profiles;
- a complaint address in the rules, with a review timeline;
- a takedown log;
- owner hide/unpublish controls;
- a separate public-profile consent;
- no comments or messaging at first.

## 5. Social proof numbers

- **Observed:** studiers (Knowt), ratings (Quizlet, Knowt), thumbs and updated date
  (AnkiWeb), likes and duplicate counts (Figma), learner counts (Memrise). Anki and
  Figma keep counts across author updates.
- **Popularity feedback.** Showing download counts raised inequality and
  unpredictability of success ([Salganik, Dodds, Watts 2006](https://www.science.org/doi/10.1126/science.1121066)).
- **Ratings.** Online ratings are J-shaped and self-selected
  ([Hu, Pavlou, Zhang](https://dl.acm.org/doi/10.25300/MISQ/2017/41.2.06)).
- **Gap.** No rigorous evidence was found on which learning-product counts predict
  outcomes.

## 6. Implications (ranked by confidence)

1. **High.** Short random ID + derived slug + 301 + self-canonical. ID-only and
   `noindex` for private and restricted decks.
2. **High.** An explicit author publish step. Record the source deck and revision
   at fork time.
3. **High.** Ship the legal minimum before the first public deck.
4. **Medium-high.** Accept per item or exercise, side by side, with "accept all"
   and "skip". Locally edited items become explicit conflicts rather than blocking
   updates.
5. **Medium.** Show few counts at first (copies, updated date). Defer ratings.
6. **Medium.** Restricted access through login ACL and "request access", with
   throttles.
7. **Medium.** Protect the catalog from bulk scraping (rate limits).
8. **Medium-low.** Defer comments and messaging until counsel answers the ОРИ
   question.
