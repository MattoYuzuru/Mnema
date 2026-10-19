import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input, signal, viewChild } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';

let nextCode = 0;

/**
 * The personal code of the reward of «Путь» (OB-402) as text the learner can read, select and copy: a labelled, read-only field in monospace
 * that selects itself whole when it is focused or pressed, and a «Скопировать» button. The copy is the learner's own press; when the browser
 * refuses it (no clipboard, an insecure page) the code is left selected and the status says to copy it by hand. The code is a field, not a
 * picture, so a screen reader reads «Ваш промокод» and the characters. It sits on paper (`tone="paper"`) or on the night (`tone="night"`).
 */
@Component({
    selector: 'app-reward-code',
    host: { '[class.night]': 'tone() === "night"' },
    template: `
      <div class="reward-code">
        <label class="label" [for]="fieldId">{{ label }}</label>
        <div class="row">
          <input #field class="code" [id]="fieldId" readonly spellcheck="false" autocomplete="off" [value]="code()" [style.--chars]="chars()"
            (focus)="selectAll()" (click)="selectAll()" />
          <button type="button" class="button small" (click)="copy()">{{ copyLabel }}</button>
        </div>
        <!-- Present before its text changes, so the result of the copy is heard. -->
        <p class="status" role="status">{{ status() }}</p>
      </div>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; max-inline-size: 100%; color: inherit; --reward-code-edge: var(--mn-ink); }
      :host(.night) { --reward-code-edge: var(--mn-on-night); }
      .reward-code { display: grid; gap: .4rem; justify-items: start; min-inline-size: 0; }
      :host(.night) .reward-code { justify-items: center; }
      .label { color: inherit; font: 700 .75rem/1.4 var(--mn-font-mono); letter-spacing: .12em; text-transform: uppercase; }
      .row { display: flex; flex-wrap: wrap; gap: .5rem; align-items: stretch; min-inline-size: 0; max-inline-size: 100%; }
      :host(.night) .row { justify-content: center; }
      /* The field is as wide as the code: each character is at most .62em wide in a monospace face, plus the .08em letter-spacing, and the padding and the border (the size attribute ignores the spacing and cuts the last character, and the ch unit depends on the face that happens to load). */
      .code { box-sizing: border-box; inline-size: calc(var(--chars, 14) * .72em + 1.9rem); min-inline-size: 0; max-inline-size: 100%; border: 1px solid var(--reward-code-edge); border-radius: var(--mn-radius); padding: .55rem .8rem;
        color: inherit; background: transparent; font: 600 1.15rem/1.3 var(--mn-font-mono); letter-spacing: .08em; text-align: center; user-select: all; }
      .code:focus-visible { outline: 3px solid var(--mn-focus); outline-offset: 2px; }
      :host(.night) .code:focus-visible { outline-color: var(--mn-star-lavender); }
      :host(.night) .button { border-color: var(--mn-on-night); color: var(--mn-on-night); }
      .status { min-block-size: 1.4em; margin: 0; color: inherit; font-size: .9rem; line-height: 1.5; }
      @media (forced-colors: active) { .code { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class RewardCodeComponent {
    /** The code in its display form, with dashes. */
    readonly code = input.required<string>();
    readonly tone = input<'paper' | 'night'>('paper');

    protected readonly label = onboardingText('reward.code.label');
    protected readonly copyLabel = onboardingText('reward.code.copy');
    protected readonly fieldId = `mn-reward-code-${nextCode++}`;
    protected readonly status = signal('');
    protected readonly chars = computed(() => Math.max(this.code().length, 8));
    private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');
    private readonly document = inject(DOCUMENT);

    protected selectAll(): void {
        const field = this.field().nativeElement;
        field.setSelectionRange(0, field.value.length);
    }

    protected async copy(): Promise<void> {
        const clipboard = this.document.defaultView?.navigator.clipboard;
        try {
            if (clipboard === undefined) throw new Error('No clipboard.');
            await clipboard.writeText(this.code());
            this.status.set(onboardingText('reward.code.copied'));
        } catch {
            // The browser would not copy: the code stays where the learner can take it.
            this.field().nativeElement.focus();
            this.selectAll();
            this.status.set(onboardingText('reward.code.manual'));
        }
    }
}
