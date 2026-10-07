---
artifact:
  id: public-feedback-browser-2026-10-07
  type: verification-evidence
  title: "Public footer, AI copy and events editor acceptance"
  status: historical
  created_at: "2026-10-07"
  updated_at: "2026-10-07"
  owners: ["project-owner"]
---

# Public pages and events acceptance

Local code revision: `52a14ed5331c91eed0df2242e3f8bb242a631775`.
This is local verification, not Mnema production deployment. Current behavior/operations
are owned by [architecture](../../../architecture/public-updates-and-feedback.md) and
the [events contract](../../../../contracts/events/README.md).

## Real browser

```sh
python3 scripts/browser-identity/run.py --dist frontend/dist/mnema-frontend \
  --node /opt/homebrew/opt/node@24/bin/node --authoring --media --mechanics
```

Chrome 154.0.8037.98, Node24, JDK25 and disposable real Identity/Learning/PostgreSQL18
with HTTPS proxies. All **48 scenarios passed**, browser runtime errors **0**.
The composition never sent data to Telegram or an AI provider. Its retained screenshots
use synthetic accounts/content; operator information is the owner-approved public footer.

- `/ai`: requested copy/contact present; provider names and removed onboarding sentence absent.
- Footer: correct operator details, Telegram link and `/events` navigation. At 1440/768/390/320px:
  4/2/1/1 columns, no overflow, all links at least 44px. Root text at 200% also contained;
  keyboard skip reaches main. This text-size check is distinct from device pixel ratio.
- Events: a different real learner receives 403; owner uses real sign-in, saves a draft,
  verifies it is absent from public feed, previews safe Markdown and explicitly publishes.
  The public page then displays the title/date/body immediately. Timeline and editor
  fit 1440/390/320px; adversarial HTML remains text.
- Existing authoring, media and seven exercise mechanics remained green. Three logout requests
  used bearer only, without cookies or CSRF headers. No external request/browser error was observed.

The first integrated run found that a cached empty public feed delayed publication by up
to 60 seconds. The source revision above fixes this with `no-store`; the complete run
was repeated and passed. Failed-run private diagnostics were removed.

## Other local verification

Frontend lint, **2081 tests**, production build and dev-only styleguide exclusion passed.
Coverage: statements 89.85%, branches 85.16%, functions 89.83%, lines 93.71%; all floors passed.
Documentation/action/security/image policies, 244 repository Python tests, 39 browser fixture
tests, 24 CI shell contracts, 61 smoke tests and 5 recovery unit tests passed. Real cross-service
security covered 25 scenarios; cancellation unit/real-signal proofs passed.

The backend's standard live-provider/evaluation/media opt-in tests require separate configuration;
their absence does not claim live AI quality. Media was exercised through the browser composition.
Safari, Firefox, assistive technology and physical touch devices were not tested. No legal
compliance guarantee or account-proof integration is asserted by this evidence.

## Screenshots

- [Footer desktop](./public-footer-1440.png), [footer narrow](./public-footer-390.png).
- [Public timeline desktop](./events-timeline-1440.png), [editor narrow](./events-editor-390.png).

The timeline/editor text includes an intentionally inert HTML string used by the security
scenario. It is disposable test content and is not a suggested first public release note.
