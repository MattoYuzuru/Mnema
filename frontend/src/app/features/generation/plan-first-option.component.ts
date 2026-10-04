import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

let nextOption = 0;

/**
 * «Сначала показать план» (AI-14, #295): the option of the Materials composer and of the exercise builder. Checked, the session starts
 * as a plan the owner edits and launches; the plan costs on its own, said next to the box from the estimate («План: ≈ 1 % лимита»).
 * It owns no state: the host passes the value and the cost line and gets the next value back.
 */
@Component({
    selector: 'app-plan-first-option',
    template: `
      <div class="group">
        <label class="check">
          <input type="checkbox" [checked]="checked()" [attr.aria-describedby]="uid + '-hint' + (checked() && cost() !== null ? ' ' + uid + '-cost' : '')"
            (change)="checked.set($any($event.target).checked)" />
          <span>Сначала показать план</span>
        </label>
        <p class="hint" [id]="uid + '-hint'">Мнема покажет, что собирается создать: вы уберёте лишнее, поправите числа и запустите план. План стоит отдельно и списывается сразу.</p>
        @if (checked() && cost(); as line) { <p class="hint cost" [id]="uid + '-cost'">{{ line }}</p> }
      </div>
    `,
    styles: `
      :host { display: block; min-inline-size: 0; }
      .group { display: grid; gap: .5rem; min-inline-size: 0; }
      .check { display: inline-flex; align-items: center; gap: .6rem; min-block-size: var(--mn-touch-min, 2.75rem); color: var(--mn-body); font: 600 .95rem/1.3 var(--mn-font-body, system-ui, sans-serif); cursor: pointer; }
      .check:has(:focus-visible) { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 2px; }
      .hint { margin: 0; color: var(--mn-muted); font-size: .9rem; line-height: 1.5; overflow-wrap: anywhere; }
      .cost { color: var(--mn-ink); font-weight: 600; }
    `,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PlanFirstOptionComponent {
    readonly checked = model(false);
    /** «План: ≈ 1 % лимита» from the estimate; `null` while it is not known. */
    readonly cost = input<string | null>(null);
    protected readonly uid = `mn-plan-first-${nextOption++}`;
}
