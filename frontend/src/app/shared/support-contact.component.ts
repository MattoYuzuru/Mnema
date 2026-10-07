import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { SUPPORT_CONTACT } from './support-contact';
import { TelegramGlyphComponent } from './telegram-glyph.component';

@Component({
    selector: 'app-support-contact',
    imports: [TelegramGlyphComponent],
    template: `
      @if (contact; as telegram) {
        <a [href]="telegram.url" target="_blank" rel="noopener noreferrer">
          <app-telegram-glyph />
          <span>Написать в Telegram<span class="sr-only"> (откроется в новой вкладке)</span></span>
        </a>
      } @else {
        <span class="pending-contact">Telegram-бот поддержки готовится к запуску.</span>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      a { display: inline-flex; align-items: center; gap: var(--mn-space-2); min-block-size: var(--mn-touch-min); color: var(--mn-ink); text-underline-offset: .22em; overflow-wrap: anywhere; }
      .pending-contact { color: var(--mn-muted); line-height: 1.6; }
      .sr-only { position: absolute; inline-size: 1px; block-size: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SupportContactComponent {
    protected readonly contact = inject(SUPPORT_CONTACT);
}
