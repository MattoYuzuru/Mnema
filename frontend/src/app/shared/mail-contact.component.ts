import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { operatorMailto } from './legal-operator';
import { MAIL_NAVIGATOR } from './mail-navigator';
import { MailGlyphComponent } from './mail-glyph.component';

/**
 * «Написать на почту»: the same quiet action as the Telegram contact, for places that must not print the address.
 * It is a real button (Enter and Space work natively); the address is built on activation, never rendered. After activation a
 * polite status points to the policy, where the address is printed, for visitors without a mail program.
 */
@Component({
    selector: 'app-mail-contact',
    imports: [MailGlyphComponent, RouterLink],
    template: `
      <button type="button" (click)="open()">
        <app-mail-glyph />
        <span class="label">Написать на почту</span>
      </button>
      <p class="fallback" aria-live="polite">
        @if (opened()) {
          Если почтовая программа не открылась, адрес указан в <a routerLink="/privacy" fragment="general">Политике обработки персональных данных</a>.
        }
      </p>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      button { display: inline-flex; align-items: center; gap: var(--mn-space-2); max-inline-size: 100%; min-block-size: var(--mn-touch-min); margin: 0; border: 0; padding: 0; color: var(--mn-ink); background: none; font: inherit; text-align: start; cursor: pointer; overflow-wrap: anywhere; }
      .label { min-inline-size: 0; text-decoration: underline; text-underline-offset: .22em; }
      button:focus-visible, a:focus-visible { outline: 3px solid var(--mn-focus); outline-offset: 3px; }
      .fallback { margin: 0; color: var(--mn-muted); font-size: .875rem; line-height: 1.5; }
      .fallback:empty { display: none; }
      a { color: var(--mn-ink); text-underline-offset: .22em; }
      @media (forced-colors: active) { button:focus-visible, a:focus-visible { outline-color: Highlight; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class MailContactComponent {
    private readonly navigate = inject(MAIL_NAVIGATOR);
    protected readonly opened = signal(false);

    protected open(): void {
        this.navigate(operatorMailto());
        this.opened.set(true);
    }
}
