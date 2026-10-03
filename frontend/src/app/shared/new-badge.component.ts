import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The «Новое» mark of a generated exercise that was saved recently and not opened or answered yet (AI-13, #291). It is text in
 * a bordered tag, never colour alone, and it keeps its border in forced-colors mode.
 */
@Component({
    selector: 'app-new-badge',
    template: `<span class="tag">Новое</span>`,
    styles: [`
      :host { display: inline-flex; margin-inline-start: .5rem; vertical-align: middle; }
      .tag { border: 1px solid var(--mn-ink); padding: 0 .5rem; color: var(--mn-on-ink); background: var(--mn-ink); font: 700 .75rem/1.6 var(--mn-font-mono, ui-monospace, monospace); letter-spacing: .08em; text-transform: uppercase; white-space: nowrap; }
      @media (forced-colors: active) { .tag { border-color: CanvasText; color: CanvasText; background: Canvas; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NewBadgeComponent {}
