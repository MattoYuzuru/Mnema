# Media viewer visual check

Captured on 2026-09-28 from the production `NativeMediaImageComponent` and
`NativeMediaPlayerComponent` in a temporary Angular host. The host supplied a
synthetic architecture SVG and two-second MP3/MP4 fixtures; it did not replace
the production component template or stylesheet. Browser: Google Chrome 153,
headless DevTools Protocol, device scale factor 1. The temporary host was
separate from the application and used no account, API or object-store access.

`metrics.json` and the five PNGs record CSS widths 320, 390, 768 and 1440,
plus 768 with 200% CSS zoom. The measured document scroll width equals the
viewport width in every capture; all three media sections and both custom
players rendered. At 320, play, elapsed time, seek, duration and mute now fit
one row, while speed and fullscreen controls wrap into a second row. The PNGs
show the paper/indigo direction, legible focusable controls and diagram sizing.

This proves the bounded component layout only. It does not establish a full
authenticated Browse/Study journey, two-device refresh, real screen-reader
output, physical touch behavior, or device camera/recording UX. Automated
component and API tests cover the relevant control and ACL behavior; the full
local path is tracked by #242.
