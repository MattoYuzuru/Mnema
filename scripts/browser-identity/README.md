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

Use `--chrome` for another existing Chrome executable. The page contract defaults to
`[data-testid="identity-profile"]`, `[data-testid="logout"]`, and `[role="alert"]`;
matching CLI selector options are available. Registration uses `#email`, `#username`,
`#password` (also `#login-name` if present); login uses `#login-name`, `#password`.
After the successful callback returns to `/decks`, the harness exercises the canonical
own-deck list, create, detail, metadata-save and conflict UI against the real Learning API,
then opens `/login` when it needs the profile/logout controls.

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
blocks page requests outside the two exact origins (plus the local MinIO origin in media mode); at most 500 page requests in the base mode, 750 in authoring, 950 in media or 3000 in mechanics mode (allowing the local brand font and mark on repeated full navigations), and
150 Identity requests are allowed (full SPA navigations reload several bundled assets).
Global deadline 180 seconds, 600 with `--mechanics` (CLI 30–900), individual CDP/HTTP/readiness deadlines, 1 MiB proxy
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
