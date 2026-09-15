# Local frontend verification capabilities

Read-only inventory captured 2026-09-06 at base
`33a71f814185a16e922923e034518e25baeadbb8`. This is capability evidence, not a
candidate test result.

Inventory commands (read-only):

```bash
git rev-parse HEAD
uname -a
sw_vers
node --version
npm --version
'/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin/node' --version
java -version
docker version --format 'client={{.Client.Version}} server={{.Server.Version}}'
'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' --version
defaults read /Applications/Safari.app/Contents/Info CFBundleShortVersionString
safaridriver --version
npm ls @playwright/test playwright puppeteer --depth=0
(cd frontend && npm ls @playwright/test playwright puppeteer axe-core --depth=0)
find "$HOME/Library/Caches/ms-playwright" -maxdepth 2 -type d
xcrun simctl list devices available
adb devices -l
```

## Available

| Capability | Observed | Useful evidence |
|---|---|---|
| Host | Apple arm64, macOS 26.6.2 (25G83) | Desktop native browser/VoiceOver and responsive emulation. |
| Google Chrome | `/Applications/Google Chrome.app`, 152.0.7977.82 | Karma ChromeHeadless; manual DevTools; headless screenshots/DOM/network/console; viewport, reduced-motion, and network emulation. |
| Safari | Safari 26.6.2 | Manual Safari behavior, native text/IME/bidi/ruby, keyboard, and VoiceOver checks. |
| SafariDriver | Included with Safari 26.6.2 | Potential WebDriver automation after Remote Automation is enabled; enablement was not changed or verified. |
| VoiceOver | Built-in VoiceOver Utility present | Real manual macOS screen-reader transcript in Safari and Chrome. User interaction is required; no result was claimed during inventory. |
| Docker | Client 29.7.2, server 29.5.2/Colima | Real PostgreSQL 18 Testcontainers, nginx contract, recovery integration, and disposable purge rehearsal. |
| Exact CI Node | Node 22.23.2/npm 10.9.8 under `/tmp/mnema-143-resume/node22/node-v22.23.2-darwin-arm64/bin` | Exact frontend gate while this disposable task runtime remains present; recheck before use. |
| One-off Playwright runtime | Python Playwright is importable from existing non-repository skill virtual environments; Chromium/headless-shell caches 1223/1228/1234 exist | Can support bounded local screenshots/traces without changing Mnema dependencies after validating the exact runner/browser pairing. This is not a checked-in Mnema E2E harness. |
| Existing prototype evidence | `design/prototype/screenshots/` at 390/1440 plus documented 320/390/768/1440 checks | Visual-direction reference only; not production Angular, API, auth, persistence, or current-candidate evidence. |

Chrome desktop viewport emulation can exercise 320, 390, 768, and 1440 CSS px,
orientation, zoom/text scaling, `prefers-reduced-motion`, offline/latency profiles,
and long Russian/RTL fixtures. It does not become real mobile-device evidence.

VoiceOver manual evidence should record browser/version, speech/braille output as a
short transcript, navigation mode, steps, expected/actual names/roles/states, and
focus order for landing, deck list, capture, editor/save/conflict, unsupported node,
and permission-loss paths. Do not infer this result from ARIA markup or an automated
scanner.

## Missing or unverified

| Capability | State | Consequence |
|---|---|---|
| Durable exact CI Node installation | Local default is Node 26.3.0/npm 11.16.0 and no version manager was found; exact Node 22 currently lives in disposable `/tmp` | Revalidate the path before each gate or supply another exact runtime; do not call a Node 26 run CI parity. |
| Repository-owned Playwright/Puppeteer harness | Not installed at repository root or frontend | No maintained route E2E, multi-browser automation, trace, or screenshot harness exists. One-off external Playwright is available, but adding packages to Mnema is a dependency decision. |
| axe/pa11y/Lighthouse CLI | Not installed | Automated accessibility/performance reports are unavailable locally without approved tooling; manual/browser-native evidence remains possible. |
| Firefox | Application/CLI absent | Firefox rendering/keyboard/accessibility result remains unverified unless another environment supplies it. |
| Xcode/iOS Simulator | Xcode absent; `simctl` unavailable | No simulator evidence. Safari desktop emulation is not iOS hardware/simulator proof. |
| Connected iPhone/iPad | None observed in USB inventory | Real iOS device testing remains unverified. |
| Android Studio/ADB/emulator/device | Absent; no ADB device | No Android Chrome or TalkBack evidence. This is a named release gap, not satisfied by a 390 px desktop viewport. |
| Accessibility Inspector | Standalone app absent (Xcode absent) | Native accessibility-tree inspection is limited to browser DevTools and manual VoiceOver. |
| Safari Remote Automation | SafariDriver binary exists, enablement not verified | Do not promise automated Safari runs until a safe local session confirms it. |

## Minimum evidence allocation on a stable candidate

Local environment can supply:

1. Chrome connected E2E/manual evidence for desktop and responsive emulation.
2. Safari manual smoke for authoring, IME, RTL/ruby rendering, reload, and conflicts.
3. VoiceOver manual keyboard/focus/name/state transcript on Safari, with a smaller
   Chrome cross-check.
4. Chrome console/network/security and performance traces.
5. Docker-backed API/DB integration and policy gates.

Another explicit environment/owner must supply real TalkBack/Android and real iOS or
simulator evidence. Firefox is also unverified unless it is added to the supported
browser matrix or explicitly excluded by a documented product support decision.

## Manual browser matrix

| Surface | Chrome desktop | Chrome responsive emulation | Safari desktop | VoiceOver/Safari | Real Android/TalkBack | Real iOS |
|---|---:|---:|---:|---:|---:|---:|
| Landing/shell and lazy first useful screen | Required | Required | Required | Required | Blocked locally | Blocked locally |
| Deck list/create/cursor/error/forbidden | Required | Required | Required | Required | Blocked locally | Blocked locally |
| Capture → convert → save → reload → edit | Required | Required | Required | Required | Blocked locally | Blocked locally |
| Autosave/recovered/conflict/retry/leave | Required | Required | Required | Required | Blocked locally | Blocked locally |
| Long Russian + IME + RTL/LTR + ruby | Required | Required | Required | Required | Blocked locally | Blocked locally |
| Unknown/malicious/unsupported renderer states | Required | Required | Required | Required | Blocked locally | Blocked locally |
| 200% zoom/text, 320 px, landscape, reduced motion | Required | Required | Required | Required where applicable | Blocked locally | Blocked locally |

For every manual finding, record environment, exact steps, expected, actual, evidence,
severity, and confidence. A successful build/lint, one Chromium viewport, or the old
prototype cannot substitute for these rows.
