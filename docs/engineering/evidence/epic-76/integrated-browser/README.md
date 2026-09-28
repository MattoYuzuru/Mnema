# Epic #76 integrated local browser acceptance

On 2026-09-28, `scripts/browser-identity/run.py --authoring --media` ran the
checked-in Angular app, Identity and Learning services, PostgreSQL, pinned
MinIO, isolated Docker media worker and Chrome 154 over real local HTTPS.
`media-browser.json` is the sanitized browser result; the fixture generated
synthetic PNG, MP3 and MP4 bytes. No user file, credential, object key or
temporary signed URL is included in this directory.

| Path | Observed result |
| --- | --- |
| Authoring | Drag/drop PNG and audio/video bytes, signed upload to MinIO, worker READY, insert into native document, acknowledged draft reload, publish |
| Browse | Render Mermaid from source, YouTube consent and external link, loaded PNG and shared audio/video controls without page refresh; `browse-media-1440.png` shows the full page |
| Listening | Create `LISTEN_TYPE` through the exercise UI using the uploaded audio, start scheduled Study by keyboard, load shared player, submit correct answer and receive server feedback; `listening-study-390.png` shows the mobile exercise |
| Cross-tab | Independent accounts and sessions; focus refresh fetches changed deck once, with no periodic hidden-tab requests; the non-media `--authoring` run also measures the visible 45-second interval |
| Security/UX | Browser reported zero runtime errors or blocked external requests; no unsafe captured markup executed; Study remained within 320/390/1440 CSS px, 200% raster scale and reduced-motion emulation |

This is a local integration test. Browser fixtures are two-second generated
clips; the separate [phone-derived five-minute worker check](../phone-worker.md)
covers the larger processing envelope. Component captures at 320, 390, 768,
1440 and 200% CSS zoom are in [media-visual](../media-visual/README.md).
Real VoiceOver/NVDA announcements, physical touch and mobile camera/microphone
permission sheets were not exercised on a physical device. The browser test
uses keyboard/focus/layout assertions and Angular component tests for those
controls; this is not a claim of manual assistive-technology verification.

Reproduction (local only): build `mnema-media-worker:local` and backend jars,
run frontend `npm run build`, then use
`python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend --node /absolute/path/to/node22 --authoring --media`.
The runner creates and removes isolated certificates, accounts, databases and
MinIO objects. It does not connect to a shared or production environment.
