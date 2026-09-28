---
artifact:
  id: frontend-brand-visual-evidence-2026-09-28
  type: implementation-evidence
  status: current
  created_at: "2026-09-28"
---

# Frontend brand restoration — browser evidence

These are screenshots of the production Angular build, not the interactive design
prototype. The public landing was captured in headless Chrome 154 from a local static
build. The account, deck, editor, Browse and Study screens were captured by the
disposable real HTTPS Identity + Learning browser harness with synthetic data.
The PR records the exact head SHA used for review. The 320 px captures include a
200% root text-size check; the landing image shows the first 2800 vertical pixels
of that long reflowed page. All measured page `scrollWidth` values stayed within the
CSS viewport. Emulation and screenshots do not replace a physical-device or
assistive-technology check.

| Screen | Width / state | Evidence |
|---|---|---|
| Landing | 1440 px, full page | [home-1440.png](home-1440.png) |
| Landing | 390 px, full page | [home-390.png](home-390.png) |
| Landing | 320 px, 200% text | [home-320-text-200.png](home-320-text-200.png) |
| Profile | 390 px | [profile-390.png](profile-390.png) |
| Own deck | 320 px, 200% text | [own-deck-320-text-200.png](own-deck-320-text-200.png) |
| Material editor | 390 px | [editor-390.png](editor-390.png) |
| Published media + Mermaid | 1440 px, full page | [browse-media-1440.png](browse-media-1440.png) |
| Study with audio | 390 px | [study-390.png](study-390.png) |

The HTTPS harness passed registration, login, own-deck conflict/recovery, draft
reload, media upload/processing, Mermaid, YouTube consent, an audio exercise and
logout. It reported zero browser runtime errors and blocked external requests.
The browser request cap is 850 in media mode; this run used 800 requests, including
local font and brand assets on repeated full navigations.
