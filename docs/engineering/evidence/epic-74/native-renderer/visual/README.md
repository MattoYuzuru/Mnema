# #183 native renderer — isolated component visual evidence

Status: PASS for the bounded component-harness screenshot acceptance gap.

This evidence renders the production `NativeDocumentRendererComponent` from repository
HEAD `630580e754a3825d2c80862e6f823270ebaf1b6d` inside a temporary Angular host. It is
not a production route, a real device run, an assistive-technology run, or an IME run.
The temporary host adds only the required embedding `main` and screen `h1`.

## Environment and provenance

- Google Chrome `153.0.8010.36`, headless, DevTools Protocol, device scale factor 1.
- Node `22.23.2`, npm `10.9.8` from the approved recovered runtime.
- Angular `22.1.5`, Angular CLI/build `22.1.7`; existing linked `node_modules`, no install.
- Scratch worktree: `/tmp/mnema-epic74-resume-nQqPGB/renderer-visual-scratch`.
- Active `/Users/m.ryabushkin/Projects/personal/Mnema-epic74-native-renderer` was not edited.
- Production renderer source hashes used by the build:
  - component TS: `ea39843ad497a096ebcf8f59cd9419054f9963674a5b6d6c0f68e271fb0bb2ac`
  - template: `5cd8e034c018a2b0941dfa6f3e4636b333d40b787003ec3873b2bcf320a2b98e`
  - CSS: `e061860e3c4c4c60b39d9795b2e5c0da3a79b6f97ee386289b2375e3a36fc4c7`

The valid fixture contains long Russian text, a 203-character unbreakable token,
authored newline and double space, marked text, Japanese ruby, Arabic RTL text, one
HTTPS link, block/list-item/inline opaque nodes, ordered-list start 7, a quote and a
divider. The failure fixture contains a `javascript:` link and must fail closed.

## Artifacts

| File | CSS viewport / captured PNG | State and visual observation | SHA-256 |
|---|---|---|---|
| `renderer-ready-320.png` | 320x900 / 320x1421 | Full narrow layout; long Russian and unbreakable text wrap without horizontal clipping; ruby, RTL, lists and opaque placeholders remain legible. | `445fbdd652a8f19e849d6e787a487f2254b53eaf283b8a62d0711a4343f1cbbc` |
| `renderer-ready-390.png` | 390x900 / 390x1171 | Wider mobile-width layout; same mixed document and preserved list numbering. | `ff5af016f0167668f9c6e5fd7750d097369d09089e39f42541645ef00a9febf6` |
| `renderer-ready-1440.png` | 1440x900 / 1440x1128 | Desktop layout keeps the reader column bounded and centered rather than stretching prose. | `b006db06c08825bf318463b8bf2c5d4bd3944665ca07b7bc3bcab56770780055` |
| `renderer-link-focus-390.png` | 390x900 / 390x1171 | Real CDP `Tab` focuses the HTTPS link; the three-pixel indigo focus outline is visible. | `c44d2176f08c8ec0c3fe1959ed821cae70ae9f0329a58b61df4f2770ca7b8d36` |
| `renderer-invalid-390.png` | 390x900 / 390x900 | Fixed Russian failure message only; no partial article, link, or payload reflection. | `05bcef768a6be615d64d17a7b391d7dd906d07f12538117eac630c781219e178` |

`metrics.json` (`ca3628c73d124912896b4724a02034033f23f951be2b0a386919f885495f3cb2`)
records the evaluated DOM and layout for each capture. All three valid widths report:

- `renderState: ready`, an article, exact `https://example.test/source`, ruby text
  `漢字(かんじ)`, and `dir=rtl` on the Arabic paragraph;
- two block/list placeholders and one inline placeholder;
- zero executable `script`, `iframe`, `img`, or `form` descendants in the renderer;
- `documentScrollWidth === viewport.width` and `horizontalOverflow: false`.

The invalid capture reports `renderState: invalid`, no article or href, and zero
renderer executable descendants. The focus harness contains no focus API or focus
query parameter: `capture.mjs` opens the ordinary ready URL and sends one CDP `Tab`.
The capture hard-fails unless the resulting active element is an `A.native-link` with
the exact fixture href. It passed and has a different image hash from ordinary 390.

Reproduction sources retained with the artifacts:

- `harness/renderer-visual-harness.component.ts`
  (`475592d493794ce6ec13c90f79ae8a158c0b07e1142d3347a82eb33f764aec88`):
  valid/invalid fixture and host screen, with no focus hook;
- `harness/main.ts`
  (`6e51225463d92f6deea588a798ce79374246453782c1bbde0783d4d6d221af98`):
  isolated Angular bootstrap replacement;
- `capture.mjs` (`0b4696a064bbe2ba022b4d87aaa88e546c1ac4d0b0387a2ade340f8180540a06`):
  CDP viewport, keyboard, DOM/layout assertions and PNG capture.

## Reproduction

The temporary worktree was detached directly at the reviewed head, then existing
dependencies were linked without changing or installing packages. Only scratch
`frontend/src/main.ts` and the untracked scratch harness component differ from HEAD.
The checked-in index requests `app-config.js`; the transient static build received an
empty harness-local `window.MNEMA_APP_CONFIG = {}` because this component does not use
runtime application configuration. All final HTTP asset requests returned 200/304.

```text
git worktree add --detach /tmp/mnema-epic74-resume-nQqPGB/renderer-visual-scratch 630580e754a3825d2c80862e6f823270ebaf1b6d
ln -s /Users/m.ryabushkin/Projects/personal/Mnema-epic74-native-renderer/frontend/node_modules /tmp/mnema-epic74-resume-nQqPGB/renderer-visual-scratch/frontend/node_modules
PATH=/tmp/mnema-epic175-node22-recovery.a4W4JI/runtime/node-v22.23.2-darwin-arm64/bin:$PATH ./node_modules/.bin/ng build --configuration production --output-path /tmp/mnema-epic74-resume-nQqPGB/renderer-visual/site
python3 -m http.server 43183 --bind 127.0.0.1 --directory /tmp/mnema-epic74-resume-nQqPGB/renderer-visual/site/browser
/tmp/mnema-epic175-node22-recovery.a4W4JI/runtime/node-v22.23.2-darwin-arm64/bin/node /tmp/mnema-epic74-resume-nQqPGB/renderer-visual/capture.mjs
```

The production build passed: initial bundle 196.59 kB raw / 56.28 kB estimated
transfer, completed in 2.312 seconds. Those numbers describe the isolated harness,
not the renderer's incremental production-route cost.

An initial capture accidentally served the parent build directory and produced a
directory listing. DOM checks caught `article: false`; those PNGs were overwritten.
Only the hashes above and current `metrics.json` are accepted evidence.
The transient `site/` production-build directory was moved out of this retained
artifact directory after capture; the PNGs, metrics, harness sources, capture script
and this report are the retained artifacts.

## Residual scope

- No production route exists yet, so app-shell integration, route loading and real
  authenticated content are unverified.
- Widths are exact responsive CSS viewports in desktop Chrome, not physical phones.
- No real screen reader, accessibility-tree audit, IME, touch/coarse-pointer,
  forced-colors, OS text scaling, or cross-browser run was performed.
- Screenshots support visual review; semantic/security claims remain grounded in the
  existing component tests and are not replaced by pixels.
