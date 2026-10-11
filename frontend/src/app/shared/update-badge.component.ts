import { ChangeDetectionStrategy, Component } from '@angular/core';

import { UPDATE_TEXT } from './copy-text';

/**
 * «Есть обновление»: the mark of a copy whose author published something new, on its card in «Мои колоды» and in the hub. It is the
 * shared `.stamp.solid` with a small diamond in front, so it is words first and never a bare dot; the diamond is decoration
 * (`aria-hidden`) and the text is the whole accessible content, read in place with the title of the card. It is not a live region:
 * a list of cards must not announce every badge, and a page that wants to say so once does it in its own `role="status"`. In
 * forced-colors mode the stamp's text takes `CanvasText` on `Canvas`, and the diamond opts out of the adjustment
 * (`forced-color-adjust: none`) and is painted `CanvasText`, so it stays visible; the border is the stamp's own.
 */
@Component({
    selector: 'app-update-badge',
    template: `<span class="stamp solid"><span class="mark" aria-hidden="true"></span>{{ text.badge }}</span>`,
    styles: [`
      :host { display: inline-flex; min-inline-size: 0; vertical-align: middle; }
      .stamp { gap: .4rem; white-space: normal; }
      .mark { flex: none; inline-size: .5rem; block-size: .5rem; background: currentColor; transform: rotate(45deg); }
      /* Forced colours: the stamp's text and fill are replaced by system colours, so the diamond is painted in one on purpose. */
      @media (forced-colors: active) { .mark { forced-color-adjust: none; background: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UpdateBadgeComponent {
    protected readonly text = UPDATE_TEXT;
}
