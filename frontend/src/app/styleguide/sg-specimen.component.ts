import { ChangeDetectionStrategy, Component, ViewEncapsulation, input } from '@angular/core';

/**
 * One styleguide entry: what it is, when to use it, the live example (projected), the tokens it reads and, optionally,
 * the markup to copy. Styleguide-only; the examples inside are real app components and classes.
 */
@Component({
    selector: 'app-sg-specimen',
    encapsulation: ViewEncapsulation.None,
    template: `
      <article class="sg-specimen">
        <header class="sg-specimen-head">
          <h3>{{ title() }}</h3>
          @if (selector()) { <code>{{ selector() }}</code> }
        </header>
        <p class="sg-when"><strong>Когда использовать.</strong> {{ when() }}</p>
        <div class="sg-stage" [class.sg-stage--paper]="onPaper()"><ng-content /></div>
        @if (tokens().length > 0) {
          <p class="sg-tokens">Токены: @for (token of tokens(); track token) { <code>{{ token }}</code> }</p>
        }
        @if (usage()) {
          <details class="sg-usage">
            <summary>Разметка</summary>
            <div class="sg-scroll" tabindex="0" role="region" [attr.aria-label]="'Разметка: ' + title()"><pre><code>{{ usage() }}</code></pre></div>
          </details>
        }
      </article>
    `,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgSpecimenComponent {
    readonly title = input.required<string>();
    /** The class or component selector the example uses, e.g. `.button.primary` or `<app-usage-meter>`. */
    readonly selector = input('');
    readonly when = input.required<string>();
    readonly tokens = input<readonly string[]>([]);
    readonly usage = input('');
    /** Draw the stage on the page paper instead of a sheet. */
    readonly onPaper = input(false);
}
