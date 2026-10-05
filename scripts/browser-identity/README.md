# Disposable HTTPS browser Identity check

`run.py` composes the **built** Angular frontend, real Identity Account and Learning JARs,
a disposable PostgreSQL18 database, two loopback HTTPS proxies, and an isolated headless
Chrome profile. `browser.mjs` uses Node 24's built-in WebSocket and Chrome DevTools Protocol;
there is no Playwright/npm/pip dependency or package installation.

## Run

Prerequisites: existing Java 25 on `PATH`, Node 24, Chrome, OpenSSL with `req -addext`, Docker
with a locally cached `postgres:18`, built backend `bootJar`s and the built Angular browser
directory. Build/gates belong to the implementation workflow; this harness does not build
or download dependencies/images. On Colima set the existing local `DOCKER_HOST` normally.
The optional media mode also needs cached pinned MinIO and
`mnema-media-worker:local` images, AWS CLI and FFmpeg on `PATH`.

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
| statistics | five `<figure>`s drawn when scrolled into view (`@defer (on viewport)`), each `svg[role=img]` named by its `<figcaption>`; every figure has a `<details>` with a real table opened by a real click; all five tables carry exactly the numbers of `GET /api/decks/{id}/insights`; coverage shows materials without exercises; no vanity metric; each widget ends in an action |
| responsive | `hub-1440.png`, `hub-390.png`, `hub-320-at-200-percent.png` (320 CSS px at DPR 2), the document never overflows horizontally at 390 and 320 |
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

Use `--chrome` for another existing Chrome executable. The page contract defaults to
`[data-testid="identity-profile"]`, `[data-testid="logout"]`, and `[role="alert"]`;
matching CLI selector options are available. Registration uses `#email`, `#username`,
`#password` (also `#login-name` if present); login uses `#login-name`, `#password`.
After the successful callback returns to `/decks`, the harness exercises the canonical
own-deck list, create, hub (metadata form behind «Изменить»), metadata-save and conflict UI against the real Learning API,
then opens `/login` when it needs the profile/logout controls.

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

The whole path is the real Angular UI on the real HTTP surface. Only the fixture (the deck, one material and four of the five exercises) is
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

At every screenshot the page must not overflow horizontally at 1440 and 390 px and no `.button` may be shorter than 44 px. The time-based
assertions (no offer before 5 s, the offer after 4.7 s) use wall-clock timers: a machine under load can throttle them, so rerun once before
investigating a failure that names a timer.

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
