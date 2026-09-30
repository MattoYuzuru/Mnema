# Production brand assets

The Mnemosyne image comes from the checked-in [design master](../../../../design/prototype/assets/mnemosyne-engraving.png). Its creation and face refinement are recorded in the [provenance note](../../../../design/prototype/assets/mnemosyne-provenance.md). Production variants retain the original crop and proportions: 1024/640 px AVIF, 1024/640 px WebP, and the original PNG fallback. The browser picks a supported format and size through `<picture>`; the text and primary action do not depend on the image.

`cormorant-garamond-latin-cyrillic.woff2` is a subset of the [prototype variable font](../../../../design/prototype/assets/CormorantGaramond.ttf), converted with `fonttools[woff]` and Brotli. It keeps Latin, Cyrillic and common punctuation. The adjacent [SIL Open Font License](OFL-CormorantGaramond.txt) applies. Other scripts use the CSS fallback chain. The original TTF remains the editable master, and the 174 KB WOFF2 is served locally with `font-display: swap`.

Brand colors and typography roles are set in [`tokens.css`](../../theme/tokens.css); asset files do not contain translatable UI text.

The sunburst SVG is the shared header/favicon mark. `app-icon-512.png` is its 512 px paper-backed app icon; `../og-image.png` is a 1200×630 social preview built from the same portrait, type and palette. These static graphics encode the current ink/paper colors, so regenerate them when those token values change.

## Provider placeholders (2026-09-30)

Local, inert sign-in placeholders reuse vendor assets, without a remote SDK or request.
Google's light square SVG is from the [official sign-in asset archive](https://developers.google.com/static/identity/images/signin-assets.zip),
linked by its [branding guide](https://developers.google.com/identity/branding-guidelines).
Yandex's small logo is from its [button design guide](https://yandex.ru/dev/id/doc/ru/codes/buttons-design)
([original SVG](https://doc-binary.s3.yandex.net/src/dev/id/ru/files/small-logo.svg)).
GitHub's mark is the [official asset](https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png);
see the [brand guide](https://brand.github.com/foundations/logo). Vendor marks retain their original geometry/colors.
All three are explicitly unavailable, and do not assert configured OAuth integrations.
