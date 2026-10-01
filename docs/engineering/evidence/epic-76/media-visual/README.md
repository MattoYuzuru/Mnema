# Media viewer and upload visual check

Captured on 2026-09-28 from the production `NativeMediaImageComponent` and
`NativeMediaPlayerComponent` in a temporary Angular host. The host supplied a
synthetic architecture SVG and two-second MP3/MP4 fixtures; it did not replace
the production component template or stylesheet. Browser: Google Chrome 154,
headless DevTools Protocol, device scale factor 1. The temporary host was
separate from the application and used no account, API or object-store access.

`metrics.json` and the five PNGs record CSS widths 320, 390, 768 and 1440,
plus 768 with 200% CSS zoom. The measured document scroll width equals the
viewport width in every capture; all three media sections and both custom
players rendered. At 320, play, elapsed time, seek, duration and mute now fit
one row, while speed and fullscreen controls wrap into a second row. The PNGs
show the paper/indigo direction, legible focusable controls and diagram sizing.

The same host then rendered the production `NativeMediaUploadComponent` with an
empty document. `authoring-metrics.json` and five `authoring-*.png` screenshots
cover the same widths and 200% zoom. The drop surface, file picker, camera
capture and recording actions stay within the viewport. The upload panel
received extra space below its heading after the first 320/390 inspection.

These captures prove bounded component layout only. The authenticated
Browse/listening Study journey and two-tab refresh are recorded separately in
[`../integrated-browser/README.md`](../integrated-browser/README.md). No real
screen-reader, physical touch, or device camera/recording run is claimed.
