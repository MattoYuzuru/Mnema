import { ChangeDetectionStrategy, Component } from '@angular/core';

import { LEGAL_OPERATOR } from './legal-operator';
import { SupportContactComponent } from './support-contact.component';

/** The operator's identity and contacts, rendered from the one requisites file so both legal documents always agree. */
@Component({
    selector: 'app-legal-operator-block',
    imports: [SupportContactComponent],
    template: `
      <dl class="operator-block" aria-label="Реквизиты оператора">
        <div><dt>Оператор</dt><dd>{{ operator.name }}</dd></div>
        <div><dt>ИНН</dt><dd>{{ operator.inn }}</dd></div>
        <div><dt>ОГРНИП</dt><dd>{{ operator.ogrnip }}</dd></div>
        <div><dt>Электронная почта</dt><dd><a [href]="'mailto:' + operator.email">{{ operator.email }}</a></dd></div>
        <div><dt>Поддержка</dt><dd><app-support-contact /></dd></div>
      </dl>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .operator-block { margin: 0 0 var(--mn-space-4); border-inline-start: 3px solid var(--mn-ink); padding: var(--mn-space-2) 0 var(--mn-space-2) var(--mn-space-4); }
      .operator-block div { display: grid; grid-template-columns: minmax(0, 11rem) minmax(0, 1fr); gap: var(--mn-space-3); align-items: center; padding-block: var(--mn-space-1); }
      dt { color: var(--mn-muted); }
      dd { margin: 0; overflow-wrap: anywhere; }
      a { color: var(--mn-ink); text-underline-offset: .22em; }
      a:focus-visible { outline: 3px solid var(--mn-focus); outline-offset: 3px; }
      @media (max-width: 36rem) { .operator-block div { grid-template-columns: minmax(0, 1fr); gap: 0; } }
      @media (forced-colors: active) { .operator-block { border-color: CanvasText; } a:focus-visible { outline-color: Highlight; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class LegalOperatorBlockComponent {
    protected readonly operator = LEGAL_OPERATOR;
}
