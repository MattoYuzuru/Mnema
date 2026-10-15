# Disposable HTTPS browser Identity check

`run.py` composes the **built** Angular frontend, real Identity Account and Learning JARs,
a disposable PostgreSQL18 database, two loopback HTTPS proxies, and an isolated headless
Chrome profile. `browser.mjs` uses Node 24's built-in WebSocket and Chrome DevTools Protocol;
there is no Playwright/npm/pip dependency or package installation.

The composition also creates a disposable real Identity editorial owner. The events
scenario verifies another learner's 403, owner sign-in, draft isolation, explicit
publication, safe Markdown and timeline/editor reflow at 1440/390/320px. Screenshots
are `events-editor-<width>.png` and `events-timeline-<width>.png`; it sends no data to Telegram.

The base profile flow saves and reloads a Unicode multiline bio, checks whitespace
normalization and rejects more than six lines through the real form. Profile captures
cover desktop, 390 and 320 CSS px; the backend integration test also verifies rejected
edits preserve the stored bio. The text contract is in the
[Identity guide](../../backend/services/identity-account/guide.md#browseraccount-endpoints).

## Run

Prerequisites: existing Java 25 on `PATH`, Node 24, Chrome, OpenSSL with `req -addext`, Docker
with a locally cached `postgres:18`, built backend `bootJar`s and the built Angular browser
directory. Build/gates belong to the implementation workflow; this harness does not build
or download dependencies/images. On Colima set the existing local `DOCKER_HOST` normally.
The optional media mode also needs cached pinned MinIO and
`mnema-media-worker:local` images (the harness runs the production media runner script unprivileged on the host; it starts one throw-away worker container per job, and Learning is told to accept that user as the verdict owner), AWS CLI and FFmpeg on `PATH`.

```sh
python3 -m unittest discover -s scripts/browser-identity -p 'test_*.py' -v
node --check scripts/browser-identity/browser.mjs
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend --node /absolute/path/to/node24
```

On macOS run it under `caffeinate -d -i` (`caffeinate -d -i python3 scripts/browser-identity/run.py ...`): once the display has gone to sleep,
Chrome (`CVDisplayLinkCreateWithCGDisplay failed` in its log) stops answering the page for tens of seconds and the Workshop selection-edit
scenario fails with a CDP timeout at `rewrite_simpler`, with the product code unchanged. The assertion lasts for the run only and changes no
system setting.

Pass `--authoring` to extend the same real HTTPS composition through Deck creation,
Capture conversion, acknowledged draft reload, explicit LearningItem publication and
Browse. The extended check also confirms that adversarial note text remains inert and
that the retained Capture source/conversion can be read from the real Learning API.
It publishes a typed exercise through the same authenticated API, starts short Study
with a real keyboard Space event, verifies answer/feedback focus and a server transition,
and captures 1440/390/320 CSS px Study views (320 at DPR 2) with reduced motion.

Pass `--authoring --media` for the complete local media path. The runner starts
an isolated MinIO bucket and the configured worker, drops a generated PNG, MP3
and MP4 in the editor, waits for READY, publishes native media with Mermaid and
a YouTube consent card, and checks loaded Browse players. It then creates a
`FREE_RESPONSE` exercise with an audio prompt through the UI from the uploaded audio
and answers it in Study. It writes a full-page Browse capture and removes its local bucket and
temporary browser state. The ordinary `--authoring` mode additionally waits
through the visible 45-second deck polling interval; `--media` checks immediate
focus refresh and skips that long timer wait.

### Exercise mechanics baseline (`--mechanics`)

`--authoring --media --mechanics` (the flag requires both others; default deadline 600 s, `--timeout`
accepts 30-900) runs `mechanics.mjs` after the base authoring and Study flow, on a 1440 px desktop
viewport, through the real Angular UI against the real Identity/Learning jars, with CDP keyboard and mouse
input where the interaction is natural. It records one `mechanics_*` result per step and fails the run
(`state: "failed"`, `mechanicsFailures`, a `failure-<step>.png` screenshot and the UI's own reason) when a step
breaks. It never works around a defective control; a broken step is a finding for the product, not a harness fix.

The exercise editor is one page: choose a type tile, play the demo, then fill the steps one by one with
«Продолжить». `mechanics_editor_flow` runs first and checks the initial state (type choice alone), the demo through
`POST /api/exercise-previews` only (no attempt, hint or pair-check request), the switch to the author's own draft
with checking blocked and its reason, the instant jump with `prefers-reduced-motion`, and the 390 px overflow
(`editor-*.png`). The scenarios below drive the same step flow.

Create, save, reopen (full page reload of the edit route) and assert restored values, then Study:

| Mechanic | Authoring and reopen | Study |
|---|---|---|
| `SELF_CHECK` | prompt, reference text plus a material fragment chosen with the keyboard | reference hidden until revealed, reveal and rate with the keyboard |
| `FREE_RESPONSE` | the base flow's audio-prompt exercise is reopened: accepted answer, audio block, disabled AI switch and its reason | answered by the base flow |
| `CLOZE` | two blanks of the same repeated word through select-range and "Сделать пропуском"; first-letter hint on blank 1 only | hint requested by keyboard (one server request, one letter), both blanks filled, per-blank feedback including "с подсказкой", `MEDIUM` evidence class from the real attempt response |
| `CHOICE` multiple | two correct marks, one option with an uploaded audio file; switching to SINGLE with two marks shows the fix-required error and keeps both marks | clicking the option's player controls does not select; keyboard selection and submit |
| `CHOICE` single | one correct mark | radio semantics, keyboard selection and submit |
| `MATCH` | text-text pair, text to uploaded audio (through the slot media picker), text plus a recording on one side | one deliberate wrong pair, then correct pairs; playing audio neither pairs nor sends a pair check; `PARTIAL` with the retry notice and `PAIR_RETRY` |
| `ORDER` | the demo is solved with the arrow buttons; «Разбить на слова» turns a sentence with punctuation and a repeated word into items, plus a code block and an uploaded PNG frame; the finished exercise is solved in the preview with the keyboard (one preview request) | issued shuffled; one real use of the «На позицию N» select, the arrows do the rest, the move is announced in the live region; swapped identical words are `CORRECT`, evidence `MEDIUM`; 390 px overflow recorded as a finding |
| `CATEGORIZE` | the demo is played with select-then-group; three groups (the last stays an empty distractor) and a temporary fourth one; items with an uploaded audio; the group select by keyboard; removing the fourth group asks first and moves its item; keyboard preview | pressing the audio player neither selects nor assigns; a decision is changed before submit; counters follow; one wrong item gives `PARTIAL`, the right group is shown, evidence `LOW`; groups stack in one column at 390 px (a finding otherwise) |

Study uses standard sessions (five new objectives each, so seven authored exercises take two) and dispatches on
what the UI presents.
Screenshots: `mechanics-edit-*-1440.png` (full-page reopened editors), `mechanics-study-*-1440.png` and
`*-feedback-1440.png` for every mechanic, and `mechanics-study-cloze-390.png` / `mechanics-study-match-390.png` /
`mechanics-study-order-390.png` / `mechanics-study-categorize-390.png` (horizontal overflow at 390 px is recorded as a finding).

**Microphone.** In this mode Chrome starts with `--use-fake-ui-for-media-stream
--use-fake-device-for-media-stream`, so "Записать аудио" -> stop -> "Загрузить запись" -> READY -> "Добавить в
упражнение" runs against Chrome's SYNTHETIC audio device and an auto-accepted permission prompt. Its result is
labelled `syntheticMicrophone: true, realDeviceMicrophone: false`. It proves the recorder state machine, the
upload of a recorded WebM and media processing; it is **not** a real-device microphone test, and a real
permission prompt, hardware, OS routing and Safari/Firefox recording remain uncovered.

Not covered by this mode: other browsers, touch input, screen readers, the 320 px layout of these screens,
editing an existing exercise's media, AI evaluation and speech input (disabled by the server in this fixture),
and the hint/recording flows of mechanics other than those listed above. Dragging an ORDER item is not
exercised (the buttons and the select are the supported path). Study answers were chosen to exercise partial
results (a wrong cloze blank, a wrong first match pair, a wrong categorized item), so a green run says nothing
about a fully correct cloze, match or categorize attempt.

### Native code block (`--authoring`)

`code-block.mjs` runs last in the authoring flow (after Study, notifications and mechanics when those are enabled), in a new
material of the base deck, on the signed-in tab, at 1440 px with `prefers-reduced-motion: reduce`. It records one result,
`code_block_real_editor_publish_browse_roundtrip`, and fails the run (`state: "failed"`, the step `code_block_<stage>`,
`failure-code-block-<stage>.png` and `.txt`) when a stage breaks; it never works around a defective control.

| Stage | Assertions |
|---|---|
| `editor_keyboard` | the toolbar button «Блок кода» inserts a block and focuses its textarea; real Shift+Tab reaches the language field, real typing sets `sql`, Tab returns to the textarea; the source (a Tab-indented line typed with the real Tab key, trailing spaces, an inner blank line, `<script>` and `<img onerror>` text) is typed key by key and the textarea value equals it byte for byte, so Tab inserted a tab and did not move focus; Esc selects the block and puts focus back on the editor surface (not `<body>`); further real Tab presses leave the editor (no trap) |
| `editor_second_block` | a second block (no language) with a 401-character line; the draft is acknowledged; `code-block-editor-1440.png` |
| `publish_browse` | publish, full reload, Browse: `pre > code.language-sql` text equals the typed source, the language label, no child elements, `dir="ltr"` and computed direction, the scroll region has `tabindex=0`, `role=region`, an accessible name, `overflow-x:auto`, `white-space:pre`, takes keyboard focus and shows a focus ring; nothing executed (`globalThis.__mnemaXss`, no script/img inside code); `code-block-browse-1440.png` |
| `responsive_scroll` | at 390 px and at 320 CSS px with DPR 2 the page has no horizontal overflow, the long line sits in a scrolling region, and a real ArrowRight on the focused region scrolls it (`code-block-browse-390.png`, `code-block-browse-320-at-200-percent.png`) |
| `reopen_roundtrip` | the editor reopens with the same language and sources; publishing unchanged keeps Browse identical and the Learning API returns exactly the typed `source`/`lang` (no `lang` on the second block, no CR) |

Limits: Chrome only; the Esc and Tab behaviour is checked with CDP key events, not with a screen reader or a physical
keyboard; "2x text" is the 320 px layout rasterized at DPR 2 as elsewhere in this harness, not the browser text-size setting.

### Notification center (`--authoring --media`)

`notifications.mjs` runs after the base Study flow (which it completes first) and records one result,
`notifications_center_real_media_failure`. The producer is real end to end: a deliberately corrupt file
(`broken-image.png`, 2 KiB that are not an image, declared `image/png`) is dropped into the new-material editor's upload
surface. It passes the client checks and the upload, the real media worker container rejects it, and the real Learning
backend publishes `MEDIA_PROCESSING_FAILED` (`VERIFICATION_REJECTED`) for the owner. Nothing is stubbed. The stages:

| Stage | Assertions |
|---|---|
| `toast` | after an SPA navigation to the deck list and a visibility poll, exactly one ERROR toast in `section.toast-region` (`popover="manual"`, open, not a live region) with the outcome sentence; the persistent `role="status"` announced it; the bell badge is `aria-hidden` and the bell's name carries the exact count; the API list carries the notification |
| `toast_visuals` | reduced motion is fade only and ordinary motion keeps the slide (computed `animation-name`); the toast fits 1440 and 390 px without horizontal overflow (`notifications-toast-1440.png`, `notifications-toast-390.png`) |
| `toast_keyboard` | real Tab from the last footer link reaches the toast's «×»; real Esc closes it and returns focus to the previous link, not `<body>` |
| `bell_panel` | real Tab reaches the bell, Enter opens the panel (`aria-expanded`, `:popover-open`, the entry listed), the badge clears after the read-cursor PUT and the server reports 0 unread; Esc closes and focus returns to the bell; the panel at 390 and 1440 px (`notifications-panel-390.png`, `notifications-panel-1440.png`); «×» dismisses the entry and the API list is empty |
| `quiet_mode` | a second corrupt upload; Study is reached through links and a practice session is in the answering phase; once the API has the notification a poll updates the badge at once, but no toast appears and nothing is announced; after answering, the toast shows on the feedback screen without taking focus from the feedback heading (`notifications-study-feedback-toast-1440.png`); it is closed with «×» |
| `escape_empty_panel` | Esc on the emptied panel closes it and focus returns to the bell |

Honest limits. The 45 s poll is not waited for: a `visibilitychange` event is dispatched on the document (the store polls
at once when the tab becomes visible; `visibilityKicks` in the result counts them). Hover pause is not exercised: an ERROR
toast has no timeout and the 3 s echo toast has no caller yet. Light dismiss is a real mouse press outside the panel. In
this headless Chrome, Esc sent to a background tab hides the tab and stops its animation frames, so the scenario calls
`Page.bringToFront` before every Esc; an Esc sent after the viewport was resized twice while the panel stayed open and its
only entry was dismissed froze the renderer in an early run (reproduced twice, not on a plain popover page, not once the
panel is closed with a click and reopened), so the dismissal stage closes by click and the Esc check on the emptied panel
runs on a freshly opened panel. A failing stage writes `failure-notifications-<stage>.png` and `.txt` (harness labels and
CDP method names only).

### Deck hub (`--authoring --media --mechanics`)

`hub.mjs` runs after the mechanics baseline on the same signed-in tab and records one result,
`hub_overview_sort_star_select_delete` (`state: "failed"` with `reason`, `completed` facts and `failure-hub_*.png` on a
broken step; a failed hub step fails the run like a failed mechanics step). The Deck page `/decks/:id` is the hub: it lists the
materials (there is no `/decks/:id/materials` list route) and keeps the metadata form behind «Изменить», which the own-deck
scenarios above and `mechanics_hold_to_delete_geometry` open first. The scenario adds two materials without exercises and one
disposable material with a single exercise through the real editor (the exercise through the authenticated API, like the Study
fixture), then checks:

| Stage | Assertions |
|---|---|
| statistics | five `<figure>`s in one surface, each `svg[role=img]` named by its `<figcaption>`; numerical legends match `GET /api/decks/{id}/insights` and remain available before deferred SVG rendering; no tables or action links; at desktop width mechanics spans two columns beside coverage, with states, due dates and captures below |
| responsive | `hub-1440.png`, `hub-390.png`, `hub-320-at-200-percent.png` (320 CSS px at DPR 2), the document never overflows horizontally at 390 and 320 |
| material navigation | first/last boundaries and route reuse through real neighbour links; at 1440/1200 px the chevrons remain vertically centred in the material gutters while scrolling; at 1050/390 px the links follow the document without covering its text (`material-navigation-<width>.png`) |
| sort | the radio group is changed with a real ArrowRight; materials without exercises come first, every row keeps its true ordinal (`2,3,4,1`), the order equals the API's `sort=exerciseCount` order |
| «Эталон» | the star is toggled with a real Space press (`aria-pressed`, name «Эталон: <title>»), the server stores it, it survives a reload, and the budget note shows «1 из 10» |
| selection | checkbox + Shift+click range, tri-state «Выбрать все» (mixed, checked, cleared), the `role="region"` bulk bar with the count, sticky and inside the viewport, its height reserved as `--mn-bulk-bar-height`, **no «ИИ» button while the capability is off**, real Esc clears the selection (`hub-selection-1440.png`, `hub-selection-390.png`) |
| hold-to-delete | the disposable material is selected, the first Space only arms the button and shows the consequence text «Удалит 1 материал и 1 упражнение…» (equal to the server preview), a second Space is held until the deletion happens (the material is still there after 1.5 s and the hold lasted at least 3 s), then the list, the total, the statistics and the API all show it gone (`hub-hold-to-delete-1440.png`) |
| focus and motion | a real Tab reaches a row link and `.item-row:has(.row-link:focus-visible)` matches (`hub-row-focus-1440.png`); under `prefers-reduced-motion` smooth scrolling and the hold wave are off (`hub-reduced-motion-1440.png`) |

The scenario clears its last selection with the «Снять выбор» button and then checks that the page still answers: an Esc sent at
that point, after the reduced-motion emulation was reset, hung the headless renderer (shared by both tabs) in repeated runs, the same
family as the notifications note above, so Esc is exercised earlier in the scenario only. If the page ever stops answering, the
scenario fails and names where the main thread is stuck.

Honest limits: Chrome only; no touch input, screen reader or Safari/Firefox; the AI button is checked absent, not present;
partial (`PARTIAL`) bulk results need a concurrent foreign publication and are covered by the component and backend tests, not here.

### Profile AI budget (`--authoring`)

`usage.mjs` runs last among the authoring scenarios (after the code block) on the signed-in account's tab, under
`prefers-reduced-motion: reduce`, and records one result, `usage_profile_ai_budget_real_api` (`state: "failed"` with the step
`usage_<stage>` and `failure-usage-<stage>.png`/`.txt` on a broken stage). Nothing is stubbed: the page's «ИИ-бюджет» block
(`/profile`, `section#ai-budget`) is compared with the real `GET /api/usage` fetched with the page's own bearer, so no number
is fixed; whatever the earlier flows consumed, the text must agree with the API.

| Stage | Assertions |
|---|---|
| `block_matches_api` | the response is `200` and `no-store`, the fixture account is Free; the section is labelled by its «ИИ-бюджет» heading; the plan line reads «Тариф Free»; the sentence says the API's rounded percent, «≈ N материалов» (remaining credits ÷ 10, rounded down), the renewal date formatted by the harness in `ru-RU` / `Europe/Moscow`, the locked share and the next unlock date; the bar is `aria-hidden`, has no role and no text; the weekly ticks are three unless every portion is open; fair-use counters appear exactly for the buckets the API flags `warn`; no credits shown as a unit; the other profile sections are still there |
| `responsive_and_screenshots` | no horizontal overflow at 1440, 390 and 320 CSS px at DPR 2, and the block stays inside the viewport; element screenshots (CDP clip of the section) `usage-budget-1440.png`, `usage-budget-390.png`, `usage-budget-320-at-200-percent.png` |
| `fragment_same_document` | what a notification link does while the profile is open: a browser navigation to `/profile#ai-budget` changes only the fragment (a marker proves the document was not reloaded); the heading takes focus and is in view; an unrelated fragment does not move focus |
| `fragment_fresh_load` | `/decks`, then a fresh load of `/profile#ai-budget`: the heading is focused and in view and the text still equals the API (`usage-budget-fragment-1440.png`) |

Honest limits: Chrome only; no screen reader; the plan is Free by default (the scenario fails if it is not, rather than
guessing); fair-use counters are asserted consistent with the API, not forced above 80 %; the link is a browser navigation
to the URL, not a click on a bell or toast entry (that component is covered by the notifications scenario and unit specs).

### Paywall, goal question and public `/ai` (`--authoring`)

`plans.mjs` (#301) runs after the usage scenario on the signed-in account's tab, under `prefers-reduced-motion: reduce`, and records
`plans_paywall_goal_real_api`; `runAiPublic` runs after logout on the first (now anonymous) tab and records `ai_page_public_without_login`.
Nothing is stubbed: `GET /api/plans` and `GET/PUT /api/learning-profile` are read with the page's own bearer and the page is compared with
them. A broken stage writes `failure-plans-<stage>.png`/`.txt`. `--only-plans` (development aid, requires `--authoring`) runs it alone after
the base flow and skips the code block, usage, Workshop and assessment scenarios; never a substitute for the full run.

The public `/ai` scenario checks the current introduction, source-checking benefit and accuracy limit,
the limited email/phone/card masking disclosure without provider names, and the owner-approved
operator details and support-bot links. It follows the footer's `/events` link, checks footer columns
and 44 px link targets at 1440, 768, 390 and 320 CSS px, exercises the keyboard skip link,
and repeats text containment at 320 CSS px with the root font doubled. Screenshots are
`ai-public-1280.png`, `ai-public-320.png`, `public-footer-<width>.png` and
`public-footer-320-2x-text.png`. Doubling the root font is a text-scaling check; DPR alone
is raster density and is not evidence of browser zoom or a real touch device.

| Stage | Assertions |
|---|---|
| `onboarding_allowlist` | the fresh account has not answered; «Для чего вам Mnema?» appears on `/decks` as one unselected radio group of five options with «Продолжить» disabled; opening a deck from the list in the same document takes it down; it never shows on the material editor, capture, the deck hub, privacy, terms or home |
| `onboarding_answer` | keyboard only: Space selects «Экзамены и сессия», Tab reaches «Продолжить», Enter submits; the API stores `EXAMS`; focus lands inside `#main-content`, never on `<body>`; it does not come back |
| `plans_content` | the heading is the goal's, exactly one tier carries «Рекомендуем для подготовки к экзаменам» (the one the API recommends), the own plan is marked and preselected, teasers are the only disabled tiers, the lede names the Free voice and answer-check caps, no horizontal overflow |
| `period_math` | ArrowRight/ArrowLeft move the period; per tier the month price and per-day note, then the year price, «≈ per month» and «Экономия» equal the harness's own arithmetic over the API's prices |
| `auto_renew` | never pre-ticked; the text carries the amount and a renewal date 30 days out (Moscow); a Space-ticked box resets on a change of tier and of period and the text follows; the hint says the profile switch comes with payments |
| `payment_notice` | the `role=status` region is in the page from the start and empty; Enter on the call to action fills it with the payments notice and describes the button with it; no navigation, the entitlement is unchanged; a different tier clears it |
| `keyboard_and_sticky_bar` | at 1440, 390 and 320 CSS px (DPR 2) the root scroll padding equals the sticky bar's height plus 8px for the focus ring and a real Tab walk from the top of `/plans` to the call to action never leaves a focused control under the bar (at least 6px clear of it) or above the viewport (`plans-focus-*.png`) |
| `/ai` public | after logout the footer link opens `/ai` (no sign-in redirect) with six sections and six contents links, and a direct load at 320 CSS px does not overflow |

Honest limits: Chrome only; no screen reader; Free is the fixture's plan (a paid plan view needs the billing context); the sticky-bar check is
measured geometry, not a visual diff.

### Promo codes, A/B assignment and the promo popup (`--authoring --only-promo`)

`promo.mjs` (#302) is a development aid, never part of the full run: the popup campaign must be on in Learning and it would cover the pages of every
other scenario. `--only-promo` boots a **second Learning** on the same database with the campaign on (ASCII copy), the address limit of redemption
attempts raised to 100 (every request of this fixture comes from one loopback address; the per-account limit stays 5 an hour) and a random experiment
secret; after the base flow the scenario switches the proxy to it (`POST /__fixture/learning-promo`) and back. Two fixture endpoints exist only with
this flag, both on the disposable database: `promo-account-verified-admin` verifies the email of the scenario's second account and makes it an
administrator (Identity has no endpoint that verifies an email without a mailbox, and the first administrator is bootstrapped, never self-granted),
and `promo-popup-reset` forgets every account's popup state so one account can be walked through dismiss, close, accept and decline. Records
`promo_codes_experiments_popup_real_api`; a broken stage writes `failure-promo-<stage>.png`/`.txt`. Nothing is stubbed.

| Stage | Assertions |
|---|---|
| `not_verified_not_admin` | before the fixture promotes the account: 403 `ACCESS_DENIED` on creating and listing codes, 401 without a token, 400 without an `Idempotency-Key`, `PROMO_NOT_ELIGIBLE` on a redemption (unverified email) |
| `admin_creates_codes` | an administrator creates a generated code (ten characters of the unambiguous alphabet, returned once; the list shows only the hint and counts), a discount code and a kill-switched one; a code for Max is refused |
| `redeem_by_keyboard` | on `/plans`, real keyboard only: a wrong code gives one calm sentence under the field (`aria-invalid`, described by its alert, focus stays, the typed code stays); the right code in lower case gives «Plus до …, без автопродления.», the plan block shows it, Plus is the preselected and marked tier, `GET /api/plans` says `PLUS`/`PROMO`/no renewal for 15 days and `GET /api/usage` follows; the same code again says it was used; a discount code is stored and shown as «Скидка 20 % на Plus применится к оплате до …»; the sixth attempt of the hour is «Слишком много попыток…» in the page and `429` with `Retry-After` in the API |
| `profile_and_narrow` | the profile's «Тариф» block shows the promo plan without renewal, the discount and the same field; `/plans` and `/profile` do not overflow at 390 CSS px and the button is at least 44px high |
| `experiment_variant` | the variant in `GET /api/plans` is `control` or `plans_year_first`, the same on every read, and `/plans` opens on «Месяц» or «Год» accordingly; rendering sent an exposure; the event endpoint takes a conversion, ignores an unknown experiment and refuses a body with a variant |
| `popup_places` | with the account eligible: no popup on the material editor, capture or the deck hub, and the page has not even asked (the session marker is unset); the Study completion screen is a natural breakpoint and shows it; in an active Study session (practice) it is neither shown nor asked for |
| `popup_dismiss_with_escape` | the popup is a modal `<dialog>` with a title, focus on the title, four buttons (close, call to action, «Не сейчас», «Больше не показывать»), no checkbox, no animation under reduced motion; Tab visits the four buttons and never reaches the page behind; Esc closes it and focus lands on the page, not `<body>`; the server is silenced; not again in the same session, and not in a new session inside the cooldown |
| `popup_close_and_accept` | «×» closes and silences; at 390 px the popup fills the screen; the call to action opens `/plans` without a code in the address |
| `popup_decline_is_final` | «Больше не показывать» ends the popup: not in a new session and the server says not eligible |

Honest limits: Chrome only; no screen reader; the metric `mnema_experiment_events_total` is not readable here (the actuator exposes health and info
only), so a conversion is checked by the endpoint's answer and the unit tests; the cooldown's length is the integration tests' (a clock moves there).

Use `--chrome` for another existing Chrome executable. The page contract defaults to
`[data-testid="identity-profile"]`, `[data-testid="logout"]`, and `[role="alert"]`;
matching CLI selector options are available. Registration uses `#email`, `#username`,
`#password` (also `#login-name` if present); login uses `#login-name`, `#password`.
After the successful callback returns to `/decks`, the harness exercises the canonical
own-deck list, create, hub (metadata form behind «Изменить»), metadata-save and conflict UI against the real Learning API,
then opens `/login` when it needs the profile/logout controls.

### Ten Korean lesson notes (S1, `--authoring --generation`)

The Workshop notes composition selects ten distinct short Korean lesson notes in Capture, opens ten pinned source chips,
chooses one material per note and applies one detailed per-note override. The base effort is explicitly SHORT: nine short
notes plus one detailed note require58 credits under rate-cardv1, close to the previous four-note fixture's52, without
changing the account or daily-burst limit. All ten artifacts/source pins are checked. Three are approved individually;
the changed last note remains proposed; only the three used notes archive. Existing changed-note, repeated-archive,
merge-two-notes, desktop/mobile and source-order assertions remain. This is deterministic Stub output, not Korean model-quality evidence.

### Exercise generation and batch review (`--authoring --generation`)

`exercises.mjs` (#291, AI-13) runs at the end of the Workshop scenarios against the second Learning of the run (Stub text provider,
`aiGeneration` on; never a real provider, no key), in a **deck of its own** so Study and the lists see only what it makes. Only the
fixture is made through the authenticated API: the deck and three materials (one clean, one the Stub breaks once and repairs with
`[[stub:broken-key]]`, one it never repairs with `[[stub:broken-key-always]]`) and three mechanics (the Stub breaks the CHOICE of a marked material). Everything the user does is the real Angular UI:

| Step | What is driven and asserted |
|---|---|
| `hub_entry` | three materials ticked in the Deck hub, «Упражнения с ИИ для выбранных» opens the builder; the address carries member keys, never a revision |
| `builder` | «Для 3 материалов», «Авто» exclusive with the mechanics, the priority group (three materials), «Точно» with a native range moved by ArrowRight/ArrowLeft and its `aria-valuetext` («5 упражнений на материал»), the percent range, three mechanics chosen by name, the preflight against the real `generation-estimates`, 1440/390 screenshots and overflow/44 px checks |
| `create_and_stream` | «Создать упражнения» opens the Workshop; the batch settles as 8 proposed (at least three mechanics, payload `EXERCISE_COMMAND` with `display`) and 1 `FAILED(INVALID_OUTPUT)` (never shown as a proposal) |
| `review_layout` | cards in the server order with a heading where the material changes (nothing moves when details load), a failed card in its place, eight «Оставить» checked, summary «8 готово · 1 не удался», «Сохранить выбранные (8)», no duplicate id, 1440/390 screenshots |
| `preview_play` | a proposal is played in its preview with a real click path: only `POST /api/exercise-previews`, no Study endpoint, nothing saved |
| `edit_proposal` | one other proposal is unchecked first and must still be unchecked after the round trip; «Изменить» opens the editor with `?session=&artifact=`, the objective title is typed with the keyboard, «Сохранить в колоду» approves with the replacement and returns to the Workshop; the exercise is `isNew` |
| `save_selected` | the proposal left out before the editor stays out, «Сохранить выбранные (6)», the toast «Новые упражнения: 6 — уже в колоде», focus on the next footer control (not the heading), «Отклонить остальные (1)»; seven exercises in the deck, all new, sharing objectives by title |
| `new_in_list` | «Новое» on every new row of the material's exercise list, 1440/390 screenshots |
| `study_new` | the first Study card carries «Новое», 1440/390 screenshots |
| `new_cleared_on_open` | opening an exercise clears its mark on the server (`DELETE .../new-mark`) and the list shows one mark fewer |

The scenario records its own duration (`durationMs`, also for the whole Workshop scenario).

Screenshots: `exercises-{builder,review,list}-{1440,390}.png`, `exercises-saved-1440.png`, `exercises-study-new-{1440,390}.png`.

### Selection edits in the Workshop (`--authoring --generation`, #293, AI-11)

`selection-edits.mjs` runs last among the Workshop scenarios, against the same second Learning (Stub text provider, never a real one,
no key), in a **deck of its own**. The deck and the proposal are made through the authenticated API (the Stub answers with its
`headings` document for a known prompt: heading, paragraph, two headings, paragraph; other prompts are tried if that ever changes);
everything the user does is the real Angular UI on the real HTTP surface. The Stub has no media, so the actions under an image or an audio
are covered by the component specs; the scenario proves that node ids stay out of Browse.

| Step | What is driven and asserted |
|---|---|
| `fixture`, `open` | the proposal has five blocks; the Workshop draws `data-node-id` on the five top-level blocks and nowhere else; the hint above the material; no history yet |
| `select_and_window` | a real triple click selects a paragraph, the floating group «Попросить Мнему…» opens (`popover="manual"`, label «Действия с выделенным текстом»); **Tab** from the selection reaches the group and **Enter** opens the non-modal `role="dialog"` window (label, four presets, field, quote, no microphone, cost line «≈ N % лимита» next to the real `generation-estimates`, the block painted with the CSS Custom Highlight API while focus is in the window); `Esc` closes it, returns focus to the document and puts the selection back |
| `rewrite_simpler` | Shift+Enter is a new line and sends nothing, an Enter during an IME composition or with keyCode 229 sends nothing; «Проще» sends: the block is `aria-busy` with the dashed frame and the caption while the turn runs, then only that block changed (same node ids, neighbours byte for byte), the strip «Переписано · Показать изменения · Оставить · Вернуть · Ещё раз», the end in the summary line (the page's one live region), debited once (the usage bar), `revisions` INITIAL, EDIT |
| `diff` | «Показать изменения»: `<ins>` with the spoken prefix «добавлено:», the rewritten block replaced by it, `aria-expanded`/`aria-controls`; hidden again |
| `again_and_undo` | «Ещё раз» is a new turn with its own reservation (usage), «Вернуть» moves the pointer back and deletes nothing |
| `history` | «История правок (2)»: the original, every turn, the shown version marked, «Вернуть к этой версии» both back and forward |
| `failed_rewrite` | `[[stub:refusal]]` in the instruction: the strip says why and that the text did not change, nothing is charged |
| `mobile_sheet` | a 390 px touch viewport (`pointer: coarse`): a bar fixed at the bottom instead of the floating group, a modal bottom sheet (`showModal()`, `enterkeyhint="send"`, safe-area padding, no overflow), «Короче» sends, the strip at 390 px has no target below 44 px |
| `edit_in_progress` | the page's polling is cut with `Network.setBlockedURLs` (requests that fail, never an invented answer) while a slow edit is started through the API: the window explains «Мнема ещё переписывает предыдущий фрагмент — дождитесь окончания.» and stays open although the page now knows the artifact is being rewritten; «×» closes it, the block is busy (screenshot), and «Стоп» cancels the turn (`CANCELLED`, artifact `PROPOSED`, hold released) |
| `approve_and_browse` | a cancelled session offers no rewrite; the approved material in Browse is the rewritten revision and carries no node id and no Workshop mark |

On failure the step writes `failure-edits-<step>.txt` with the reason, what the server held (artifact state and the last turns, no
text), and, when the page stopped answering, the top of its stack and its slowest calls.
Screenshots: `workshop-edit-window-1440.png`, `workshop-edit-rewriting-1440.png`, `workshop-edit-diff-1440.png`, `workshop-edit-in-progress-1440.png`,
`workshop-edit-sheet-390.png`, `workshop-edit-rewriting-390.png`, `workshop-edit-strip-390.png`, `workshop-edit-diff-390.png`.

`--only-edits` (development aid, requires `--generation`) runs this scenario alone after the base flow, which is much faster than the
whole run; it never replaces it. With `--keep-on-failure` the private directory also holds `slow-requests.log` (proxied requests that
took two seconds or more: method, path without query, status, time).

### Image search (`--authoring --media --generation`)

`image-search.mjs` (#296, AI-10) runs among the Workshop scenarios, in a deck of its own, against the Stub Learning (Stub text provider and the
Stub image source: no network, no key; `LEARNING_FEATURES_IMAGE_SEARCH_ENABLED=true` is set for that instance). It needs `--media`: the found
files are staged through the real media pipeline as untrusted uploads, so the local MinIO and the media worker must be there.

The composer is driven for real (`Ещё настройки` → chip `Изображения` → Enter), then the paper placeholder becomes the image and the attribution
line `Фото: … · Тестовый источник · CC0 1.0` is checked against the slot. `Найти похожее` is reached with Tab and opened with Enter, a query is typed,
`Искать` sent by keyboard; the status `Ищу похожие изображения…` is recorded; the variants radio group is checked (count, one name, the first new
candidate checked, focus on it, the announcement `Нашла N вариантов, выбран первый.`). The arrow keys move the local choice and the network log must
show **no** selection request; `Использовать это изображение` commits (one request, a new revision, the new asset, focus stays on the radio). The
history's `Вернуть к этой версии` goes back to the search revision. A `[[stub:image-none]]` search ends `FAILED NO_RESULT`: the announcement,
the unchanged picture and revision and nothing charged are checked. Then 1440/390/320 px (overflow, 44 px targets, grid columns 2/1/1, 2x text),
reduced motion, approval and Browse (the published caption carries the attribution, no Workshop-only lines).

Screenshots: `workshop-image-ready-1440.png`, `workshop-image-panel-{1440,390,320}.png`, `workshop-image-variants-{1440,390,320}.png`,
`workshop-image-choice-1440.png`, `workshop-image-chosen-1440.png`, `workshop-image-none-1440.png`, `workshop-image-variants-320-2x-text.png`,
`browse-image-attribution-1440.png`; failures write `failure-images-<step>.png` and `.txt` (with what the server held).

`--only-images` (development aid, requires `--generation --media`) runs this scenario alone after the base flow:

```
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend --node /opt/homebrew/opt/node@24/bin/node \
  --authoring --media --generation --only-images
```

### Speech synthesis (`--authoring --media --generation`)

`speech.mjs` (#297, AI-09) runs among the Workshop scenarios, in a deck of its own, against the Stub Learning (Stub text provider and the Stub
speech port: a deterministic WAV tone, no network, no key; `LEARNING_FEATURES_TEXT_TO_SPEECH_ENABLED=true` is set for that instance). It needs
`--media`: the WAV goes through the real media worker (WAV source -> playback variant).

The composer is driven for real (`Ещё настройки` -> chip `Аудио` -> Enter); the slot goes to READY, the player loads in muted Chrome
(readyState/duration) and the caption `Синтезированная речь · женский голос` is shown. `Озвучить заново` is reached with Tab and opened with
Enter: the panel preselects the current voice, the arrow key moves to `Мужской голос`, `Озвучить` is sent by keyboard, the status `Озвучиваю…`
is recorded, the turn ends APPLIED with a new asset, the strip `Озвучено заново`, the announcement `Готово: новая озвучка, мужской голос.` and
the caption `… · мужской голос`. `Вернуть` restores the previous asset; a same-voice redo is a new take (the asset changes again). Then
1440/390/320 px (overflow, 44 px targets, focus back on the trigger after `Отмена`, 2x text), reduced motion, approval and Browse (the audio
plays and carries no `Синтезированная речь`: no AI marks on published content).

Not covered: the FAILED clip frame (`Повторить / Убрать блок`). The Stub text adapter writes the spoken text itself (the first heading of one
of five fixed documents), so `[[stub:tts-down]]` cannot be put into it from the composer; it is covered by the backend tests. At 320 px with 2x root
text the step fails when the page overflows, when any control sticks out of the shared audio player (`native-media-player`, including
the speed select) or when a player button, timeline or speed select is below 44 px.

Screenshots: `workshop-audio-ready-1440.png`, `workshop-audio-panel-{1440,390,320}.png`, `workshop-audio-redone-1440.png`,
`workshop-audio-undone-1440.png`, `workshop-audio-320-2x-text.png`, `browse-audio-no-mark-1440.png`; failures write
`failure-speech-<step>.png` and `.txt` (with what the server held).

`--only-speech` (development aid, requires `--generation --media`) runs this scenario alone after the base flow:

```
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend --node /opt/homebrew/opt/node@24/bin/node \
  --authoring --media --generation --only-speech
```

### Voice input (`--authoring --generation`)

`voice.mjs` (#298, AI-15) runs last among the Workshop scenarios, in a deck of its own, against the Stub Learning
(`LEARNING_FEATURES_SPEECH_TO_TEXT_ENABLED=true`, the Stub transcription: a fixed text, no network, no key). The recording is Chrome's
SYNTHETIC microphone (`--use-fake-ui-for-media-stream --use-fake-device-for-media-stream`, also added for `--generation`): a tone, never a
real-microphone test. The frontend has no test seam for the transcript, so where another text or a slow provider is needed the driver adds
`X-Stub-Transcript` (percent-encoded UTF-8, honoured only by the Stub) to the real request the page sends, through the Fetch interception of
`browser.mjs` (`ctx.setRequestHeaders`).

Steps: the composer (the mic is present only with `speechToText` available; the first press opens the consent dialog that says where the voice
is processed; Esc closes it with no consent and no recording; `Согласен` by keyboard records `PUT /api/speech-consent`; about 2 s of recording,
`Распознаю…`, the text at the caret, never sent, focus and caret in the field); the `Попросить Мнему…` window and the `На потом` field (the same;
the latter by keyboard only: Tab, Enter, Enter, focus on the stop button); Study (a `FREE_RESPONSE` with `TEXT_OR_SPEECH`: `Ответить голосом`,
the transcript edited and sent as `answerSource: SPEECH`, a typed answer as `TYPED`, a `TEXT` exercise with no mic and no member); the input rows
(`DONE`, `DELETE` is 204 and then 404 on read; the audio bytes themselves are deleted by the server and not visible through the API);
1440/390/320 px (overflow, 44 px targets, the consent dialog, recording, transcribing, `Отмена`), reduced motion (nothing animates, the recording
dot is still); and fair use (21+ quick inputs through the API until `429 RATE_LIMITED`, then the calm message with minutes on the page).

Screenshots: `voice-composer-{idle,recording,transcribing,inserted}-*.png`, `voice-consent-*.png`, `voice-window-*.png`, `voice-capture-inserted-1440.png`,
`voice-study-*.png`, `voice-rate-limited-*.png`; failures write `failure-voice-<step>.png` and `.txt`.

`--only-voice` (development aid, requires `--generation`) runs this scenario alone after the base flow:

```
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend --node /opt/homebrew/opt/node@24/bin/node \
  --authoring --media --generation --only-voice
```

### Web research and «Источники» (`--authoring --generation`)

`research.mjs` (#299, AI-18) runs among the Workshop scenarios, in a deck of its own, against the Stub Learning (Stub text provider and the Stub
web search: results on `https://example.org/stub/research/<n>`, no network, no key; `LEARNING_FEATURES_WEB_SEARCH_ENABLED=true` is set for that
instance; no source page is ever opened). Steps: a «Подробно» control material without fact checking (what the text alone costs); the composer
(«Проверять факты» disabled with its reason on «Кратко», enabled on «Средне»/«Подробно» with the request cap and the credits in the hint); a
«Подробно» material with fact checking (Space on the box, Enter), `research.requests` at most 6, the `/api/usage` debit equal to the text plus
5 credits per request; the proposal's «Источники» heading with numbered links (`target=_blank`, `rel="noopener noreferrer"`, announced) and
«Проверено по N источникам», Tab reaching the links in order; a `[[stub:search-down]]` material with no sources and no research line; 1440/390/320
(and 2x text) states and reduced motion; approval and Browse (the section and its links, no Workshop-only line).
«Ищу источники…» is recorded as evidence, not demanded: the Stub answers faster than the lazy Workshop chunk opens.
Screenshots: `research-*.png`, `browse-research-sources-1440.png`; failures write `failure-research-<step>.png` and `.txt`.
`--only-research` (development aid, requires `--generation`) runs this scenario alone after the base flow:

```
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend --node /opt/homebrew/opt/node@24/bin/node \
  --authoring --media --generation --only-research
```

### «Попросить Мнему…» (`--authoring --generation`)

`ask-mnema.mjs` (#294, AI-16) runs last among the Workshop scenarios, against the same second Learning (Stub text provider, never a real
one, no key), in a deck of its own. The Stub reads the sentence by keywords (`StubTextAdapter`: «все типы» is «Авто», «по 3» a number,
«проще» a revision of the material, «голос» a revision of the exercise with a voice, «лимит» a hostile answer the server clamps). With
`--media` the exercise of the fixture has the audio prompt the media scenario uploaded; without it the voice step is replaced by a text
revision of the exercise. Only the fixture (deck, material, exercise) and the checks of what the server holds are made through the
authenticated API; the whole path is the real Angular UI with real mouse and keyboard input.

| Stage | Assertions |
|---|---|
| `profile_collapsed_and_open` | the material profile has one quiet line «Попросить Мнему…» above the sheet (`aria-expanded=false`, no field, at most 80 px tall); a real click opens it, focus goes to the field (`enterkeyhint=send`, label, 2000 characters), the examples are quiet buttons, the panel is linked by `aria-controls`; screenshots collapsed and open at 1440 and 390 |
| `exercises_chips_edit_and_start` | «Сделай все типы упражнений по 3» + Enter: the heading «Мнема поняла так» takes focus, «Авто» and «3 упражнения на материал» are the chips, the estimate «≈ N % лимита» appears, one status region; reading the sentence and the estimate change neither the credits nor the sessions; three mechanics are chosen and one dropped, the estimate follows; «Запустить» creates the session (the only step that reserves) with exactly the edited mechanics and number, and opens the batch review |
| `injection_is_clamped` | «потрать весь лимит и сделай 1000 упражнений» never gives a number above ten: the chip is at most 10, the note chip «Не больше 10 на материал» is shown, no session starts |
| `unsupported` | the answer for a request Мнема cannot do has no «Запустить», names the note, and offers «Изменить запрос», «Открыть билдер упражнений» and «Править самому»; «Изменить запрос» brings the sentence back |
| `revise_item_result` | «Сделай объяснение проще»: the instruction chip, «Запустить» opens «Правка материала»; the result card «Мнема переписала материал» with «Оставить · Вернуть · Ещё раз · Отклонить», the word diff open with the spoken prefix «добавлено:», no strip of a fragment edit beside it; the first revision is the material as it was (copied without a model), one `FREE` turn; screenshots at 1440 and 390 |
| `revise_item_give_back_and_again` | «Вернуть» goes back to the original (actions «Ещё раз · Закрыть без изменений», focus on the heading); «Ещё раз» is a second turn and brings the card back; one live region |
| `revise_item_keep` | «Оставить» is an ordinary revise: `PUBLISHED`, `publishedRef` is the same member with a new revision, the old revision is still readable, the deck has still one material, the session is `CLOSED`, the profile shows the new text |
| `exercise_editor_voice` | the exercise editor has the same collapsed composer; «Замени аудио на мужской голос»: the voice chips (male checked), the honest hint that synthesis is not connected, the estimate; «Запустить» opens «Правка упражнения»: the chip «Голос: мужской», the Stub note «Озвучка обновится, когда подключим синтез речи» (the slot keeps its asset and records `voice: male`), the compact preview, the history «Озвучка заново: мужской голос»; screenshots at 1440 and 390 |
| `exercise_editor_keep` | «Оставить» saves the next revision of the same exercise (same id, new revision id, old revision readable, still one exercise) |

Screenshots: `ask-{collapsed,open,chips,revise-item-result,editor-collapsed,chips-voice,revise-exercise-result}-{1440,390}.png`. On failure the step
writes `failure-ask-<step>.txt` and `failure-ask-<step>.png`. `--only-ask` (development aid, requires `--generation`) runs this scenario
alone after the base flow; it never replaces the full run.

### «Сначала показать план» (`--authoring --generation`, #295, AI-14)

`planner.mjs` runs after the notes scenarios of the Workshop, against the same second Learning (Stub text provider, never a real one, no
key; the fixture account of the Stub Learning is MAX: Pro has one smart plan a week, Max eight a month, and the scenario makes two), in decks of its own. Only the fixtures (the decks and their materials) and the
checks of what the server holds are made through the authenticated API; everything the user does is the real Angular UI. The plan of the
Stub is deterministic (one row per material, the chosen mechanics round-robin, two per row, the requested number); `[[stub:plan-invalid-always]]`
in a material title breaks every answer, so the plan fails (a deck of its own, because a title reaches the plan prompt).

| Step | What is driven and asserted |
|---|---|
| `builder_option` | the exercise builder: «Ещё настройки» is closed and holds «Сначала показать план» (off, described «План стоит отдельно»), the button says «Создать упражнения»; with three mechanics and «Точно» chosen, checking the box makes the button «Составить план» and puts «План: ≈ N % лимита» next to it, equal to the plan's 20 credits against the whole allowance of `GET /api/usage` (the first breakdown line of the real `generation-estimates` is `SMART_PLAN_FLASH`, 20 credits); screenshots at 1440 and 390 |
| `plan_ready` | «Составить план» opens the Workshop; the session is `PLAN_READY` with no artifact, the plan has three rows (chosen mechanics only, 3 each), the plan is debited apart (`credits.used` +20, `smartPlan.used` +1, session `spentCredits` 20), the live region says «План готов: проверьте его и запустите», no review and no «Стоп» yet; each row has its material, the reason as secondary text, the mechanics chips without «Авто», a native range 1..10 with `aria-valuetext` («3 упражнения») and a named «Убрать»; «Составление плана: ≈ N % лимита — уже списано», the live total «Всего 9 упражнений · ≈ N % лимита» equals the client price of the rate card and is also said by a status region of its own (the summary is the other); no duplicate id; screenshots at 1440 and 390 (overflow and 44 px targets) |
| `edit_plan` | a row is removed with a real click (focus moves to the «Убрать» of the next row), listed under «Материалы вне плана (1)», taken back with «Вернуть» as it was and removed again; a count moves with ArrowRight (3 to 5, «5 упражнений»); a mechanic is unchecked in another row and the last one cannot be (the row says why); every change moves the total; nothing changes on the server before the launch; screenshots at 1440 and 390 |
| `launch` | «Запустить по плану»: the session leaves `PLAN_READY`, the plan is gone and the review is open, focus is on the Workshop title; exactly 8 artifacts (5 + 3, none for the removed material), each exercise's mechanic is one the row kept, the stored plan is `approved` with the edited rows in order; a second approval answers `409 GENERATION_STATE_CONFLICT` and changes nothing; the session spent at least the plan's 20; screenshots at 1440 and 390 |
| `plan_failed` | `[[stub:plan-invalid-always]]`: the session is `CANCELLED` with `endReason PLAN_FAILED`, no artifact, nothing spent or reserved, `credits.used` and `smartPlan.used` unchanged; the Workshop says «Мнеме не удалось составить план. Ничего не создано, лимит не списан.» and shows no plan; screenshots at 1440 and 390 |
| `materials_plan` | the Materials composer with the box checked (button «Составить план», cost line, estimate); the plan of materials (title, effort radios per row, live total in materials), debited apart; «Убрать» on the last row asks first («Оставить» is focused and keeps it); the first topic is retyped with the keyboard and made «Подробно»; the launch writes exactly the rows of the plan and the stored plan carries the edited title and `DETAILED`; screenshots at 1440 and 390 |

Screenshots: `planner-{builder,ready,edited,launched,failed,composer,materials-ready}-{1440,390}.png`. On failure the step writes
`failure-planner-<step>.txt` and `failure-planner-<step>.png`. `--only-plan` (development aid, requires `--generation`) runs this scenario
alone after the base flow; it never replaces the full run.

### Semantic assessment of explanations (`--authoring --assessment`)

`assessment.mjs` (#292, AI-20) runs last of the authoring scenarios on the signed-in account's tab, in a deck of its own. The flag boots the
same second Learning as `--generation` (Stub provider only, `LEARNING_FEATURES_AI_ASSESSMENT_ENABLED=true`, plan PRO, no key of any
provider) and flips the proxy to it with `POST /__fixture/learning-generation`; the other scenarios keep running against the ordinary Learning
(everything AI off). The Stub grades an answer by the markers inside it (`[[stub:assess-partial]]`, `[[stub:assess-slow]]`, see the Stub's
Javadoc) and otherwise by a lexical heuristic; a marker is used only where a scenario needs an exact verdict or a delay.

The whole path is the real Angular UI on the real HTTP surface. Only the fixture (the deck, one material and four of the five exercises,
plus the isolated deadline deck/material/exercise) is
made through the authenticated API; the first exercise is authored in the editor.

| Stage | Assertions |
|---|---|
| `capability_and_fixture` | `GET /api/capabilities` reports `aiAssessment` available; a deck and a material |
| `rubric_editor` | the AI switch is enabled and the answer list is the default until it is used; switching it on replaces the list with the rubric editor (step title «Эталон и пункты проверки»); the live counters read «Суть: 2 (нужно 2–3)…»; an empty rubric is refused with named fields and no save step; the reference answer, three points, a fourth point (focus lands on it), tier and weight selects (set through a `change` event: the keyboard route of a closed native select differs per OS), «Тонкая настройка» with a typical mistake and two terms; removing a point moves focus to its neighbour and the counters follow; save and a real reload restore every value; screenshots `assessment-rubric-editor-{1440,390}.png` (full page) |
| `preview_has_no_model` | the preview answers `UNAVAILABLE` and says that the AI does not check in the preview |
| `study` | one standard session of five new objectives; each card is answered by what it asks (below) |
| `resume_after_reload` | a sixth exercise in a second session (`[[stub:assess-slow]]`): the page is reloaded 1 s after sending; the waiting card comes back with the learner's text and no reference, no second submit is sent, «Оценить себя» is offered about 5 s after the *original* send (the time is stored with the answer), and with nothing pressed the result «Засчитано» replaces the card at ≈ 8 s |
| `wire` | every AI answer was a `202`; one self-check, one self-rating, one dispute; the polls were made |

What each answer proves (a card's prompt chooses its script, the order is the server's):

| Answer | Proves |
|---|---|
| complete (heuristic) | title «Засчитано», all four points listed under «Есть» with quotes that are the learner's own words, nothing under «Не хватает», the note «В следующий раз проверка будет строже», the reference after the answer, «Оспорить оценку» offered, focus on the result; `assessment-result-complete-*` |
| partial (`[[stub:assess-partial]]`) | «Частично», one point present and two missing, «В следующий раз я попрошу точнее: …» naming the missing points; `assessment-result-partial-*` |
| «рецепт блинов» to a question about the PostgreSQL optimizer | «Пока не засчитано»: nothing present, every point missing; `assessment-result-offtopic-*` |
| slow (`[[stub:assess-slow]]`, the Stub waits 8 s) | the waiting card «Мнема проверяет ответ…» takes focus, keeps the answer read-only and shows no reference; no «Оценить себя» before 5 s, it appears at 5 s as a secondary button (`assessment-assessing-{1440,390}.png`); pressing it opens the self-check mode (reason, answer next to the reference, the points, four ratings; `assessment-self-check-*`); the late grade at ≈ 8 s changes nothing; a reload resumes the self-check with the learner's own text; the rating completes the same attempt: «Частично», the reference, no AI result, no dispute |
| complete, then dispute | «Оспорить оценку» asks in place (a `role="group"`, no modal, focus on the confirmation; `assessment-dispute-confirm-*`), «Да, снять оценку» gives «Оценка снята» and «Оценка снята, прогресс не изменился.»; the answer and the reference stay; `GET …/attempts/{id}` reads `NOT_ASSESSED` with `disputed: true` (`assessment-disputed-*`) |
| `automatic_deadline` (S7) | an isolated deck with one optimizer exercise submits `[[stub:assess-deadline]]` in the learner answer only. The existing Stub delay is25s, capped to the call budget; the runtime deadline remains20s. Only the disposable Stub process with assessment enabled overrides its route attempt cap to25s, so the production8s provider cap cannot end this fault fixture early. One real202 is observed, the waiting answer is read-only with no reference, the5s self-check button is not pressed, and automatic self-check must carry `DEADLINE`. Fair-use assessment counts and public progress items remain unchanged, no terminal grade appears and no explicit self-check/rating/dispute request is sent. Focus, desktop/mobile captures and reload are checked. The exact database no-debit/no-canonical-write proof remains `AssessmentDeadlineBoundaryIntegrationTest`; this browser check observes the public projections. |

These are assertions in the maintained scenario. A pass requires the final frozen source and jars plus a real run;
adding the fixture or passing syntax/unit checks does not constitute deadline/browser evidence. The prior5s/8s/reload/dispute
flow and its wire counts are asserted before the additional isolated timeout command. No request/global-time cap is raised.
The25s Stub attempt cap is a disclosed fault injection, not proof of default production provider latency. Ordinary/keyless,
promo and identity processes receive no override; production/runtime defaults remain8s per attempt and20s for delivery.

At every screenshot the page must not overflow horizontally at 1440 and 390 px and no `.button` may be shorter than 44 px. The time-based
assertions (no offer before 5 s, the offer after 4.7 s) use wall-clock timers: a machine under load can throttle them, so rerun once before
investigating a failure that names a timer.

## Community decks (`--community`, #439)

`python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend --node "$NODE24" --community` (no `--authoring` needed;
default deadline 600 s; not combinable with `--generation`, `--assessment`, `--only-promo` or `--admin`). The runner boots the **ordinary**
Learning with the three kill switches of epic #419 on, for this disposable run only: `LEARNING_COMMUNITY_PUBLIC_ROUTES_ENABLED`,
`LEARNING_COMMUNITY_PUBLIC_MEDIA_ENABLED`, `LEARNING_COMMUNITY_FORKS_ENABLED` (all `false` as shipped) and `MNEMA_PUBLIC_BASE_URL` set to the
harness's own HTTPS origin. Identity needs no switch: the public-profile consent is a normal account setting. After the base flow's two
accounts exist, `community.mjs` runs on the first account's tab (**owner**), the second account's tab (**member**) and a third tab in a
**new browser context** (**guest**: `Target.createBrowserContext`, no cookies, empty storage, no bearer), once with a 1440 px desktop viewport and once
with a 390 px mobile viewport (`mobile: true`), each pass with its own deck. The **invitee** of the invitation link is a third synthetic account
(`browser_fixture_invitee_<pass>`) that registers through the real UI in one more new browser context per pass (so it never shares the Identity cookie of the
first two); a member who already holds a grant, or whose grant was revoked (a revoked person cannot come back by link for 30 days), could not show the redemption. It skips the base flow's two long polling waits (as `--media` does)
and the public `/ai` and events scenarios, which the default run keeps (`/ai` carries the known 320 px / 200 % text check that headless Chrome fails).
Everything the user does goes through the real UI with CDP keyboard events; the exception is one exercise, created through the authenticated API like
the Study fixture of the base flow (and the few API reads and idempotent repeats named in the stages). There is no API fallback for a broken flow.
Nothing is stubbed or intercepted. Every tab's console (calls, uncaught exceptions, the browser's own log entries with their URLs) is kept as text.

| Stage | Assertions |
|---|---|
| `community_consent` (once) | `/profile`: the switch «Показывать мой профиль другим» starts off, the per-field boxes «Имя», «Фото», «О себе» start unticked and disabled, the approved consent text is shown; Space turns it on, the preview shows `@login`, Identity reports `enabled` and `publishReady` with every field off |
| `publish` | deck and three materials through the real editor, one exercise through the API; the hub's picker has exactly «Приватная · По ссылке · По приглашению · Публичная» and starts «Приватная»; ArrowRight x3 stages «Публичная» («Изменить доступ: Публичная»), Enter on «Сделать публичной…» opens the checklist (description, profile, pictures ready; language detected); topic; publication; the API reports `PUBLIC`, a 10-character code and `/d/{code}/{slug}`, the hub shows the link and lists the materials |
| `guest` | `/d/{code}/wrong-slug` and `/d/{code}` are replaced by the canonical address; the context holds no cookie and no storage; title, `@author` chip, «Публичная», «3 материала · 1 упражнение», the published description; `meta referrer` is `no-referrer` and `robots` `index,follow`; the materials are the owner's titles; the first material link is reached by real Tab (focus-visible) and opened with Enter («Материал 1 из 3», «← К колоде»); the exercise preview shows the prompt, never the accepted answer (page text and `/api/public/decks/{code}/exercises`); the primary action is «Войдите, чтобы добавить колоду к себе» with a `/login?returnUrl=` link; no «Учить», «Изменить», «Мастерская»; no horizontal overflow, 44 px target; no bearer on any public read and none answered 5xx; an unknown code gives «Колода не найдена» and the approved sentence |
| `copy` | the member sees «Добавить к себе» only; real Tab reaches it, Enter copies; the copy hub opens within the SPA; a MutationObserver installed beforehand records the heading «Готовим колоду…», (the job can finish in about 150 ms on a fast machine, so the heading is required only when preparation took 2.5 s or more; `preparingTextObserved` and `materializedAfterMs` say what happened), then (bounded 60 s) the ready hub with «Копия колоды «…»», the same three materials and «Учить»; `GET /api/decks/{id}` reports `fork {preparing:false, failed:false, sourceAvailable:true, upstreamTitle}` |
| `study` | Study of the copy: Space starts, the author's prompt is shown without the answer, the typed answer gets a correct feedback with focus on its heading; the author's `GET /api/decks/{id}/insights` is unchanged (timestamps ignored) |
| `reopen` | the second visit offers only «Открыть мою копию» (a link to the copy, reached by Tab, opened with Enter); a second `POST /api/decks/forks` returns the live copy (`existing: true`) |
| `complaint` | on the public deck: the owner's «⋯» (reached by Tab, opened with Enter) offers «Поделиться» and no «Пожаловаться», Escape closes it with the focus back on «⋯», and the API refuses his own complaint with 400; the member's «⋯» offers «Поделиться» and «Пожаловаться» (ArrowDown, Enter): a modal `<dialog>` «Пожаловаться на колоду» naming the deck, five unselected reasons, fits the viewport, every button is at least 44 px; Escape closes it and returns the focus to «⋯»; opened again, «Отправить жалобу» without a reason says «Выберите причину.», keeps the dialog and puts the focus on the reason, sends nothing; three ArrowDown choose «Спам или реклама» (the live hint shows, the error goes), Tab reaches the text field and the send button; Enter sends one `POST /api/public/decks/{code}/complaints` with the bearer, answered 202; the dialog closes, the toast «Жалоба отправлена» appears (a `MutationObserver` records it, it lives 3 s), the focus is on «⋯»; a repeat through the API is the same 202 `RECEIVED` |
| `invite_only` | the owner steps the picker back with ArrowLeft («Изменить доступ: По приглашению», the line «Ссылка изменится, и старые ссылки перестанут работать.»), «Применить»; the code is rotated and the link is `/d/{code}` without a slug; the old link shows «Колода не найдена» to a guest and names no title; the new link shows a guest and the member without a grant «Колода доступна по приглашению» with the approved sentence, no title, `noindex`, and «Запросить доступ» (reached by Tab, Enter); then the approved «Запрос отправлен. Если автор согласится, колода появится по этой же ссылке.» with focus on it; the member's copy hub says «Автор закрыл исходную колоду. Ваша копия остаётся с вами, но обновлений больше не будет.» and still works; the request is sent by the page with the bearer and answered 202; the owner's «Запросы доступа» panel lists the request with «Принять»/«Отклонить» |
| `withdraw` | the member asks again (the same sentence, a repeat of an open request), «Отменить запрос» replaces the button and is reached by Tab (focus-visible); Enter sends one `POST …/access-requests:withdraw` with the bearer, answered 204; «Запрос отменён.» is shown, the sentence «Запрос отправлен…» goes, the focus is on «Запросить доступ»; the owner's panel is empty; the member asks again and the owner sees exactly one decidable request |
| `approve` | «Принять» by keyboard empties the list; the same link now opens the deck for the member («По приглашению», «Открыть мою копию») |
| `invite_create` | in the «Доступ» panel, «Пригласительные ссылки» has the defaults «Один раз» and «7 дней» and an empty list; «Создать ссылку» (Enter) shows the new link once (`host/d/{code}?invite={43 characters}`, the rotated code, the sentence «Ссылка показана один раз.») with the focus on it; «Готово, я сохранил ссылку» removes the token from the page and returns the focus to «Создать ссылку»; the list row reads «Действует» and «Одноразовая, ещё не открыта», and `GET /api/decks/{id}/invites` carries no token |
| `invite_redeem` | the invitee, in a new browser context, opens `/d/{code}?invite={token}` as a guest: `?invite=` is gone from the address at once, the screen is «Колода доступна по приглашению» with «Войдите, чтобы принять приглашение.» and no deck title, and the token is not in the title, text, address, history state, `localStorage` or cookies; «Войти» then «Создать аккаунт» by Tab and Enter (the return address carries no token); after the registration the page returns to `/d/{code}` (no slug, no `?invite=`), shows the deck with «По приглашению» and «Добавить к себе», the pending `sessionStorage` entry is spent, and exactly one `POST /api/invites:redeem` with the bearer answered 200; a reload opens the deck, and the same link a second time opens it too (a one-time link is not spent twice by its holder); the token is in no console line of any tab, no title and no sub-request URL (`invite=` appears only in the typed navigation); the owner then sees «Использована» / «Одноразовая, открыта» and two people |

Findings are recorded, never worked around: a failed stage writes `failure-community-<pass>-<stage>-<actor>.png` and `failure-community_<pass>_<stage>.txt`, the
stages that depend on it are `skipped`, and the run fails. A **gap** is a flow the product does not complete through its own UI. It is recorded in
`communityGaps` with the request the page sent (from `Network` events), the cause and the effect; the stage fails there (the harness does not finish the flow
through the API), and the run is red until the product is fixed. Two gaps are detected: `topics-request-sent-without-bearer` (`GET /api/topics` sent without
`Authorization`, 401, so the checklist cannot choose a topic) and `access-request-sent-without-bearer` (`POST /api/public/decks/{code}/access-requests`
likewise); both come from the credential allowlist in `frontend/src/app/auth.interceptor.ts`, and neither occurs since the allowlist lists `/topics`,
`/invites:redeem` and the `/public/decks/{code}/access-requests[:withdraw]` and `/complaints` routes. Screenshots:
`community-<desktop|mobile>-{checklist,published,requests,invite-link,invite-used}-owner.png`, `…-{hub,material}-guest.png`,
`…-{copy-ready,study,study-feedback,complaint,invite-only,withdrawn}-member.png`, `…-{invite-guest,invite-landed}-invitee.png` (the invite-link and
invitee captures show a disposable token of the disposable database).

The copy hub of a copy whose source was closed shows `app-unavailable-note` (the source line `app-copy-origin` is replaced, Updates/6), which the `invite_only` stage accepts. The update review has its own scenario below (`--updates`).

Not verified here: Chrome only; no real touch input, screen reader, Safari/Firefox or physical phone (the mobile pass is a 390 px emulated viewport);
public media (the decks have no media, so the media route is switched on but not exercised) and the link-preview HTML head (the harness serves the built
SPA without nginx SSI); the other outcomes of invitation links (a one-time link used by a second *new* account, expired, revoked or stale links, the 30-day
block after a revocation, the 1000-person limit, a bad token) and of requests (limits, requests switched off); grant by login and «Закрыть доступ»; the
complaint dialog's other outcomes (404 «Колода больше недоступна», 429 «Попробуйте позже», an ended session), a complaint filed by a guest or about a
profile, the owner console and its decisions, hiding and takedown (Share/15 is covered by its own tests); that the `Referer` of requests after the redemption
is clean (the address is rewritten before the first sub-request, which is not observed per request); the catalogue, the Community page and the profile `/u/{username}` (`--catalog`) and the
update review (`--updates`); zero-load and 100k-MAU (scenario H) behaviour; production.

## Update of a copy (`--updates`, #446)

`python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend --node "$NODE24" --updates` (no `--authoring` needed; default deadline 600 s,
about 90 s of run time; not combinable with `--generation`, `--assessment`, `--only-promo` or `--admin`; `--community --updates` runs both scenarios on the same
two accounts, deadline 900 s). It is a sibling of `--community` rather than a stage of it because it needs a deck of its own (four materials, so a
deletion and a conflict can coexist with a fix) and must not share the access changes of the community passes (the invitation stage closes the source of
its deck); the **composition is the same**: one ordinary Learning with the three community kill switches on, the base flow's two long waits skipped, no
other instance. `updates.mjs` reuses `makeActor` of `community.mjs` (real CDP keyboard, the page's own API reads, the per-tab wire log). The first account is
the **author** (first tab), the second the **learner** (second tab); both passes (1280 px desktop, 390 px mobile) use the same pair with a deck of their own.
Everything the user does goes through the real UI; the exceptions are the exercises (created through the authenticated API like the Study fixture of the base
flow; the exercise editor belongs to `--mechanics`) and the API reads that compare a page with the server (plan, progress, deck, publication).

| Stage | Assertions |
|---|---|
| `publish` | author: deck, four materials in the real editor, two `FREE_RESPONSE` exercises (API), the access picker by ArrowRight x3, the checklist, `PUBLIC` |
| `copy` | learner: «Добавить к себе» by Enter, ready copy with four materials, `fork.updateAvailable` false, no badge or offer, the quiet «Проверить обновления» link, plan `UP_TO_DATE` |
| `study` | one real attempt (answer field focused, feedback focused, correct); exactly one material has `lastAssessedAt` in `GET /api/decks/{id}/study-progress` |
| `learner_edit` | the learner appends text to material #2 of the copy in the real editor (opened from Browse's «Редактировать», which carries `?ordinal=`) |
| `author_update` | the author appends a fix to #1 and a different text to #2, adds material #5 and its exercise (API), deletes #4 with the real hold (Space to arm, Space held until the row goes), «Опубликовать обновление» with «Что нового»; the publication state is current and keeps the note |
| `offer` | «Мои колоды»: the card of the copy carries «Есть обновление»; the hub offer (heading, «Посмотреть обновление», no quiet link beside it) is reached by Tab and opened with Enter; the review screen opens |
| `review` | the server's plan holds exactly 1 new material, 1 new exercise, 1 safe material fix, 1 conflict, 1 deletion; the summary line, the four tab counts and the note equal the server's numbers; the tabs are a `tablist` (names carry counts, roving tabindex, panel linked); the tabs move with ArrowRight, Home and End and show the class of the tab; the fix is opened with Tab + Enter: at 1280 px the two columns stand side by side without the switch, at 390 px one side shows with a two-radio switch that moves with ArrowRight/ArrowLeft; the conflict starts on «Оставить моё» and the deletion on «Оставить у себя» (visible legend «Удалить «…» и у меня?»); rows are named for assistive technology (heading, legend, «Сравнить: «…»», one live region); no horizontal overflow |
| `accept_safe` | «Принять всё безопасное» by Tab + Enter: one apply (200, bearer), the toast and the applied line say the same, the focus is on the summary heading, the plan reloads to the conflict and the deletion (the screen moves to «Конфликты»), the primary button is inactive and says so, the badge stays on the hub and on the card |
| `copy_after_safe` | the copy: #1 shows the author's fix, #2 keeps the learner's edit and not the author's, #5 exists with its exercise, #4 is still there; Study (an open session of the earlier visit is finished, then a new one) issues the new exercise and one attempt is correct; the earlier material's `lastAssessedAt` is intact |
| `apply_selected` | ArrowRight on the conflict's radio picks «Взять вариант автора», End then ArrowRight on the deletion picks «Удалить и у меня» (the note counts the picks), «Применить выбранное»: one apply, the toast, the focus on the heading «У вас всё как у автора», plan `UP_TO_DATE`, #2 is the author's, #4 is gone, no badge on the hub or the card |
| `auto_update` | «Принимать безопасное автоматически» by Tab + Space: the server stores it (`fork.autoUpdate`), it survives a reload, and turns off the same way |
| `detach` | the author republishes a second small update, the learner sees the badge again, «⋯» → «Отвязать от автора» (ArrowDown, Enter, confirmation heading focused, Enter): the toast, no offer, no badge, no link to `/update`, no automatic setting, the focus on the hub heading, `fork.detached`, plan `DETACHED`, `/update` says so, the card has no badge |

A stage has two kinds of verdict. A **hard** failure (the flow cannot go on) is recorded as `failed` with the reason, the stages that depend on it are `skipped`;
a **soft** finding (a statement of the contract or the handoff that does not hold, but the flow goes on) makes the stage `failed` with `findings` and the
following stages still run. Both write `failure-updates_<pass>_<stage>.txt` (and `failure-updates-<pass>-<stage>-<author|learner>.png` for a hard failure) and make the run
red. The soft findings today: `summary-counts` (see below), `desktop-columns-side-by-side`, `badge-stays-with-pending`, `badge-stays-in-list`, `quiet-link-beside-offer`,
`auto-update-live-message`.

Known finding (open): `summary-counts`. A new exercise with a new goal brings the goal as a separate `SAFE` unit of kind `OBJECTIVE`
(`PlanComputation` classifies a goal the copy has no head of as `SAFE`; `UpdatePlan.Summary.of` counts every `SAFE` unit as `fixes`), so the review says
«+1 материал, +1 упражнение, 2 исправления, …» for an author who corrected one material, the tab «Исправления» lists a «Цель» row stamped «Исправление»,
and «Принять всё безопасное» takes four units, not three. The scenario asserts the author's own numbers (one fix), so it stays red until the product decides
whether a goal that accompanies a new exercise belongs in «исправления».

Screenshots: `updates-<desktop|mobile>-{list-badge,hub-offer,review,review-full,compare-expanded,compare-expanded-full,conflict,picks,after-safe,up-to-date,auto-on,detach-confirm,detached}-learner.png`
and, at 390 px, `updates-mobile-compare-author-learner.png` (the other side after the switch moved).

Not verified here: Chrome only; no real touch input, screen reader or Safari/Firefox (the mobile pass is a 390 px emulated viewport); a plan that does not fit one page
(auto-load of the rows, `APPLYING` as a background job and its polling, 409 `UPDATE_PLAN_STALE` mid-review); the automatic acceptance itself (only the setting is
checked); exercises other than `FREE_RESPONSE`, media in a compared material, moderation takedowns of a side, a source that closed under the review, the
author's own view of who updated; the `Referer`/privacy checks of `--community`.

## Community catalogue (`--catalog`, Community/8, #455)

`python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend --node "$NODE24" --catalog` (no `--authoring` needed; default deadline 900 s, about
4 minutes of run time; **exclusive**: it cannot be combined with `--community`, `--updates`, `--authoring`, `--media`, `--mechanics`, `--generation`, `--assessment`,
`--only-promo` or `--admin`, because it must start from an empty catalogue). It is the integrated check of epic 3 on one commit, against the real Identity, Learning
and PostgreSQL; nothing is stubbed or intercepted.

The composition is the one of `--community` (the ordinary Learning, the three community kill switches on) plus:

- `LEARNING_COMMUNITY_CATALOG_ENABLED=true` (the catalogue's own switch, `false` as shipped) and `LEARNING_COMMUNITY_CATALOG_FACETS_TTL=PT1S` (the facets and the topic
  shelves are cached up to five minutes per instance; one second is the smallest value the setting takes, so a poll sees the projection). The guest limit
  (`learning.community.public-routes.guest-per-minute`, 120) stays as shipped: the limit stage hammers the real one.
- A short rank schedule (`LEARNING_COMMUNITY_RANK_INITIAL_DELAY=PT3S`, `…_SCHEDULE_INTERVAL=PT5S`). The hourly job (dedupe key: the UTC hour) therefore runs at the first
  tick, while the catalogue is still empty, which the first stage waits for. Every later generation is a job with a key of its own, `rank:harness-<n>`, inserted into
  `app_learning.learning_job` through the harness fixture endpoint `/__fixture/catalog-rank`; the real queue and the real `CatalogRank` build it, and the scenario waits
  for the completed generation (`rank_generation`) before it reads a number.
- **The launch flag of the frontend.** `COMMUNITY_LAUNCHED` is a code constant (`false` until the enabling pull request). The harness serves the built `dist` with
  `window.MNEMA_APP_CONFIG = {…, communityLaunched: true}` in its `app-config.js`; `resolveAppConfig` keeps that key **only on a loopback host** (`localhost`, `127.0.0.1`,
  `admin.localhost`) and drops it everywhere else, so a runtime file on a real host can never open the Community. With the flag open the masthead carries the closed
  book, which is also why the base flow's `submit()` and its «primary action» probe skip the `search` landmark and the masthead.
- A **console owner**: the `events_fixture` account is the configured owner (`MNEMA_ADMIN_OWNER_ACCOUNT_ID`) and Identity registers the admin client because the run sets
  `MNEMA_IDENTITY_ADMIN_ORIGIN=https://admin.localhost` (never served). `/__fixture/catalog-admin-token` mints a bearer of the `mnema-admin-web` client by the same
  authorization-code + PKCE exchange the console uses (the redirect is read from its `Location` header). The console routes themselves are called through the API.
- `/__fixture/catalog-accounts` registers synthetic accounts through the real Identity (register, login, PKCE) and returns their bearers. Identity allows ten
  registrations from one address in 15 minutes and every request of this run is one address (the base flow and the console owner already spent three), so the endpoint first
  clears `app_identity.rate_limit` of the disposable database.

Who is who: the **author** is the first account (first tab), the **learner** the second (second tab, the holder of an invitation to the INVITE deck), the **guest** a third tab in a
new browser context. A second author (`catalog_shelf_author`, consent given) holds 30 filler decks, so the lists are longer than one page while the first author's page stays one
page and can be read whole; `catalog_no_consent` is an account without a public profile; nine more accounts (`catalog_copier_1…9`) copy a deck. The decks are seeded through the real
Learning API in the shape the product's own clients send (deck, ten materials in one bulk publication, one `FREE_RESPONSE` exercise, the publication with topic, language and level);
the authoring UI is `--community`'s business. Everything a reader does (the page, the book, the filters, the keyboard) goes through the real UI with CDP key events.

Seed: nine listed decks of the author (three Spanish: «Испанские глаголы», «Spanish verbs for travel», «Español para principiantes»; four English, two
«SQL и данные»), one PUBLIC deck below the threshold (9 materials), one LINK deck, one INVITE deck; thirty fillers of the second author in «Другое». The catalogue lists 39.

| Stage | Assertions |
|---|---|
| `catalog_empty` | the schedule's own first generation exists; facets `listedCount: 0`, shelves `[]`; a guest's `/community` says «Пока здесь пусто.» and the approved sentence, no shelf, no card, no filters, the book is there with its label, exactly one `search` landmark and one `h1`; Tab reaches «Как поделиться колодой» with a visible focus ring, Enter opens it (the three approved steps), Enter closes it, Space opens it; a signed-in reader is led to «Мои колоды», a guest to «Войти»; 390 px: no overflow |
| `catalog_seed` | profile consent through Identity; the decks, the invitation redeemed by the learner; the **projection jobs** are waited for (facets list 39); one more rank generation (`sort=popular` lists 39) |
| `catalog_shelves` | «Новое» (6 cards), «Другое» (6) and «Английский» (4) are shown; «Испанский» (3), «SQL и данные» (2), «Выбор Мнемы» and «Учат сейчас» (nothing in them) are not; no shelf has fewer than four cards; no sub-threshold, LINK or INVITE deck on any shelf; no card shows an audience yet; «Все» of «Новое» by Enter leads to `/community?sort=new` and the list, and the focus is on the page heading, visible; 390 px: each shelf is a row that scrolls sideways and the page does not |
| `catalog_search` | typing sends no request (no live search); Enter puts `?q=` into the address; «испанский» finds exactly the three Spanish decks; «Spanish» the same three; the typo «испнские глаголы» puts «Испанские глаголы» first and leaves «Английские фразовые глаголы» out; the count text is a `role=status` region, announced once per search (three searches, three announcements; the observer counts each `p.count` when it first has text and when its text changes); the sorting says why it is off; the sub-threshold, LINK and INVITE decks are not found by their own words, with no bearer, the author's and the learner's; the empty result has its sentence; 390 px: three cards, no overflow |
| `catalog_filters` | topic «Английский» (4), level «A2» (2), «Письменный ответ» (2), «Аудио» (no result sentence) by keyboard, the address gains each in the canonical order; the four chips are named «Убрать фильтр «…»»; a reload restores the address, the chips and «Фильтры · 4»; removing a chip by Enter moves the focus to the chip that took its place (visible); «Сбросить всё» empties the address, brings the shelves back and puts the focus on the first control of the panel (the button is not drawn on a wide page); the sorting by ArrowRight x2 reaches the address after the wait; 390 px: «Фильтры · 3» is a disclosure (`aria-expanded`), the chips and the reset keep the focus on a chip and then on «Фильтры» |
| `catalog_scroll` | `/community?sort=new`: 24 cards, no «Показать ещё»/next button anywhere; a real scroll to the end brings the other 15 by itself; no duplicate, the same order as the API's walk of the list; no list request failed |
| `catalog_counts` | the learner finds «Испанские глаголы» through the search and copies it with the keyboard (Tab to «Добавить к себе», Enter); after **one** copy, then after **nine**, neither the API nor the card shows an audience; after the **tenth** the API says 10 and the card reads «Добавили 10»; the author's own copy attempt is refused (409) and his visit to his own deck followed by one more generation leaves 10; a card carries no score, rank, deck id or revision |
| `catalog_author` | `/u/<author>`: «Колоды автора», the nine listed decks **and** the sub-threshold one; a guest sees neither the INVITE nor the LINK deck and no «Доступна вам»; the learner who holds the grant sees the INVITE deck marked «Доступна вам» with the address `/d/{code}` (the server's own list is checked first, with his bearer and without); the author sees «Так ваш профиль видят другие» with «Настроить профиль», no invitation part, and the profile settings' «Открыть публичную страницу профиля» leads back to the page; an account without consent and a name nobody has both say «Профиль не найден» and the approved sentence; 390 px: no overflow |
| `catalog_masthead` | the closed book is in the masthead of `/` (not on `/community`, where the open book is the only `search` landmark); Enter opens it and the focus is in the field; text + Enter leads to `/community?q=…` with the three decks; Escape folds it and returns the focus to «Открыть поиск колод»; `scrollWidth <= width` at 390, 768, 900 and 1280 px on `/`, `/community` (guest and author) and `/decks` (author), measured once the page has settled |
| `catalog_motion` | with motion allowed the book opens (`mn-book-open`) and the cards arrive with `mn-ink-in`; with `prefers-reduced-motion: reduce` both are `animation-name: none`, no animation is running on the page and the typewriter hint does not change over 2.8 s |
| `catalog_a11y` | on the shelves, a search, a filtered list, the signed-in page and the author's page: the browser's accessibility tree has no link, button, text field, combobox, checkbox, radio, switch, tab or menu item without a name, exactly one `search` landmark and one `h1`; after opening a deck from a card by Enter the focus is on the page heading and is `:focus-visible` |
| `catalog_console` | a learner's token is 403 on the editorial route; five decks are saved into «Выбор Мнемы», then reordered: the shelf is the first one and reads in the owner's order (a shelf needs four decks, so two would not show); the learner files a complaint, the owner's `HIDDEN` decision removes the deck from the shelf (four stay, in order), from the search, from the browse list and from the facets, and a guest gets «Колода не найдена» |
| `catalog_limits` (last) | `GET /api/public/community/decks` as a guest until 429: `RATE_LIMITED`, `Retry-After` between 1 and 60 s, `no-store`, within 125 requests; the page then says «Слишком много запросов. …» with «Повторить», and after the wait the button brings the page back |

A failed stage writes `failure-catalog-<stage>-<owner|member|guest>.png` and `failure-catalog_<stage>.txt` (the reason carries `[after: …]`, the last API or fixture call), the stages
after it that need the seed are `skipped`, and the run fails. The stages run in this order on purpose: the limit stage uses up the minute's guest allowance of the loopback address
and the console stage hides a deck, so they are last. Screenshots: `catalog-<desktop|mobile>-{empty,shelves,browse,search,filters,scroll,counts,author,editorial,reduced-motion,limited,masthead}-<guest|member>.png`.

Defects this scenario found and that are fixed with their own tests: the catalogue's four read routes were not in the credential allowlist of `auth.interceptor.ts`, so a signed-in viewer's
bearer never reached them (the author's page could never show «Доступна вам», and the limits were never keyed by the account); the sideways row of a shelf did not contain the cards'
`position: absolute` screen-reader words, so at 390 px the whole page scrolled sideways (`deck-shelf.component.css`, `deck-shelf.css.spec.ts`); the focus fell to the body when «Все»
replaced the shelves with the list (`community-page.component.ts`).

Not verified here: Chrome only; no real touch input, screen reader (the accessibility tree is checked, not what a reader says), Safari/Firefox or physical phone (the mobile pass is a 390 px
emulated viewport); media filters with real media (the «Внутри: аудио» filter is exercised against a catalogue without any, so it proves the empty result, not a match), the «Учат сейчас»
shelf and the study-days half of the audience (no scheduled session is run), the author's own study sessions (his visit and his copy attempt are), «Для вас» (epic 4), the sitemap, anomaly flags
and the console's own pages (the editorial and complaint routes are called through the API with a real admin-client token; the console UI is `--admin`'s), a catalogue past 1000 decks or 200
search candidates, a rank generation expiring (412 `VERSION_CONFLICT` of an old cursor), the 503 `PUBLIC_READ_BUSY` bulkhead, the nginx server-side includes of the link previews, production.

## Infinite scroll (`--authoring --media --mechanics`, #397)

`hub_infinite_scroll` creates a disposable 1317-material deck through the real API in bounded batches of 20.
It verifies a 50-row first page, a real next-page request before the end, stable visible material/selection/focus
after its response, unique ordered appends to all 1317 rows, a focused whole-deck selection offer surviving appends,
and a 1440 → 390 px reflow without horizontal overflow. One GET is held by the existing guarded Fetch interceptor
until the user scroll has settled, then released; no origin/header/budget guard is bypassed. Screenshots are
`infinite-scroll-1440.png` and `infinite-scroll-390.png`. This is browser/fixture evidence, not physical-device or AT evidence.
The finite mechanics request envelope adds 250 for the fixture (66 GETs, 66 publications, 27 list pages and shell/assets).

## Assertions and envelope

Two synthetic accounts and two same-profile browser tabs exercise:

- An unsolicited wrong-state callback is rejected without a token exchange.
- Real registration, automatic password login, exact callback, S256 authorization-code
  exchange, real profile, and a Secure/HttpOnly/SameSite=Lax session cookie.
- Logout followed by explicit password login and another PKCE flow.
- Authenticated reload revalidates the sessionStorage access token at Identity `/me`;
  no new authorization or bearer in localStorage. Short-lived sessionStorage access is
  intentional; this is not an assertion that all tokens stay solely in memory.
- Browser logout clears stored access; the prior bearer is rejected by real `/userinfo`.
- Account B logs in through the second tab, changing the shared Identity cookie while
  tab A retains account A's sessionStorage bearer. Tab A's logout must revoke A (401)
  without revoking B (200); its authorization must not follow the ambient shared cookie.
  Preconditions compare real server subjects and the cookie-authenticated session.
  The HTTPS proxy independently records only header-presence booleans and requires
  each logout to carry bearer authorization, without a Cookie or CSRF header.
- A fresh login's real callback is redirected with a deliberately wrong `state`; the
  pending real PKCE transaction is rejected without exchanging its code.
- Replaying a consumed callback is rejected client-side without another token exchange.
- A fresh account sees the own-deck empty state, creates Unicode/RTL metadata through the
  real API, lands on the canonical detail route, reloads it and saves another revision.
- The same account edits its native Identity profile with a bearer token and reloads
  to prove persistence. In `--media` mode it also uploads an 8×8 PNG avatar through
  the profile UI and reloads to prove the public avatar renders. Desktop, 390px and
  320px captures check the profile layout for horizontal overflow.
- Two separately authenticated same-account tabs start from the same deck revision. The
  stale tab receives a real `412`, keeps its exact draft read-only, and publishes it only
  after the user explicitly chooses to reapply over the refreshed server version.
- The resolved synthetic deck is captured at 1440px and 390px and at a 320 CSS px
  layout rasterized at DPR 2. The harness rejects horizontal overflow and primary
  actions below 44px. Real keyboard events verify the visible skip link, main focus and
  title-to-description order; emulated reduced motion must suppress authored transitions.
  These automated checks do not claim physical-device or AT coverage.

The PKCE verifier is checked against the observed S256 challenge. Network interception
blocks page requests outside the two exact origins (plus the local MinIO origin in media mode); at most 500 page requests in the base mode, 1000 in authoring (the profile AI budget scenario adds a few full loads), 1250 in media or 3000 in mechanics mode (allowing the local brand font and mark on repeated full navigations), and
150 Identity requests are allowed (full SPA navigations reload several bundled assets).
Global deadline 180 seconds, 600 with `--mechanics` (CLI 30–900), individual CDP (10 s for a plain command, `MNEMA_HARNESS_CDP_TIMEOUT_MS` to change it; 30 s for the calls that wait for the page itself: navigation, screenshots, evaluating a script or promise in it, because several browser harnesses can share one loaded machine and hold a healthy page for ten seconds and more)/HTTP/readiness deadlines, 1 MiB proxy
request/response cap, 16 MiB static asset cap. Database has a 512 MiB/two-CPU limit; each JVM
has a 384 MiB heap cap. This is behavioral smoke evidence, not load/soak evidence.

All listeners bind literal `127.0.0.1`, including Docker's published DB port. The backend
leg is explicit loopback HTTP; **the browser leg is real HTTPS**. A fresh self-signed
certificate is trusted only by its exact SHA-256 SPKI in this fixture's private Chrome
profile. There is no global trust-store modification or blanket certificate bypass.
The two HTTPS ports are cross-origin but same-site/same-IP; this checks real browser CORS
and cookie flags, not production DNS/subdomain cookie isolation, public CA/TLS termination,
deployment headers/CSP, third-party cookies, other browsers, assistive technology or mobile UX.

## Cleanup and evidence

The fixture borrows DB/JWK setup and cleanup from `scripts/learning-security/run.py` without
modifying it. SIGINT, SIGTERM, deadline and assertion failures all terminate owned JVMs,
Node and the entire owned Chrome process group, remove the exact disposable container and
private temporary files. A second cancellation signal cannot interrupt cleanup. No broad
process/container deletion is used. An incomplete cleanup fails the run and reports only
owned resource identifiers for manual recovery. Never kill unrelated resources.

Private TLS key, signing JWK, Chrome cookies/profile, token/callback data and child logs are
never exported and are removed by default. The separate 0700 evidence directory contains
sanitized scenario/count results, artifact SHA-256 fingerprints, the empty login-form
screenshot and synthetic profile/own-deck responsive captures. It intentionally remains for reviewer inspection. Failure
evidence contains only controlled failure/scenario labels and counts, not response bodies,
console logs, URLs, credentials or stack traces. `--control-file` is a private optional
cancellation-test synchronization file; do not publish it.

For local diagnosis only, `--keep-on-failure` retains the separate mode-0700 private
fixture directory and prints its path. It can contain disposable credentials, cookies and
child logs; inspect it locally, never publish it, and remove that exact directory afterward.

Fixture unit tests validate its safety mechanisms only. They do not substitute for a
passing browser run, the full repository gates, an independent review or production proof.

References: [Chromium certificate SPKI switch](https://chromium.googlesource.com/chromium/src/+/main/services/network/public/cpp/network_switches.cc)
and [Chrome DevTools Network protocol](https://chromedevtools.github.io/devtools-protocol/tot/Network/).

## Community recommendations (`--recs`, Recs/4, #459)

`--recs` is exclusive and composes the same disposable catalogue services as `--catalog`, with
`learning.community.events.enabled` and `learning.community.for-you.enabled` enabled for that
run only. It never changes the retained stack or production flags. Built JARs and production
frontend are required; run under `caffeinate -d -i`, with no other browser run at the same time.

```sh
caffeinate -d -i python3 scripts/browser-identity/run.py \
  --dist frontend/dist/mnema-frontend --node /opt/homebrew/opt/node@24/bin/node --recs
```

The synthetic catalogue is published through the real Learning API with public-profile consent
from real disposable Identity accounts. Real UI keyboard actions select interest chips, open
For-you cards, add a copy and reset the profile. Assertions cover a guest's 401/private UI,
empty/sparse recommendations, explanation, diversity in the first ten, exclusion of authored
and copied decks, accepted telemetry with bearer, For-you sorting, 320 px, a 720 px viewport
corresponding to 200% reflow of a 1440 px desktop, reduced motion and named/focusable controls.
The viewport checks prove reflow, not browser zoom rendering or a physical phone. Chrome CDP
accessibility smoke does not prove VoiceOver/TalkBack, Safari, Firefox or touch.

Evidence consists of `recs_*` stages in `browser.json` and `recs-*.png` images. Any failed stage
fails the run; dependent stages are marked skipped. The `--catalog`, `--community` and `--updates`
regressions are run separately after the recommendation implementation.
