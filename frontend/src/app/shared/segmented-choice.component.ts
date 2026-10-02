import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

/** One choice of a {@link SegmentedChoiceComponent}. `hint` is the live explanation shown under the group while selected. */
export interface SegmentedOption<T extends string = string> {
    readonly value: T;
    readonly label: string;
    readonly hint?: string;
    readonly disabled?: boolean;
}

let nextGroup = 0;

/**
 * A single choice among a few short options, drawn as joined segments. It is a native radio group
 * (`fieldset` + `legend` + `input[type=radio]`), so arrow keys, Space, Tab and the accessible name come from the
 * platform. The explanation of the selected option sits in a polite live region under the group and is announced
 * on every change; the fieldset is described by it. Long explanations belong in a `Toggletip` projected after the
 * segments, never in a hover-only tooltip.
 */
@Component({
    selector: 'app-segmented-choice',
    template: `
      <fieldset class="segmented" [disabled]="disabled()" [attr.aria-describedby]="hintId">
        <legend>{{ legend() }}</legend>
        <div class="row">
          <div class="segments">
            @for (option of options(); track option.value) {
              <label class="segment">
                <input type="radio" [name]="groupName()" [value]="option.value" [checked]="option.value === value()"
                  [disabled]="option.disabled === true" (change)="select(option.value)" />
                <span class="mark" aria-hidden="true"></span>
                <span class="text">{{ option.label }}</span>
              </label>
            }
          </div>
          <ng-content />
        </div>
        <p class="hint" [id]="hintId" aria-live="polite">{{ hint() }}</p>
      </fieldset>
    `,
    styleUrl: './segmented-choice.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SegmentedChoiceComponent<T extends string = string> {
    readonly legend = input.required<string>();
    readonly options = input.required<readonly SegmentedOption<T>[]>();
    /** The selected value; `null` while nothing is selected. */
    readonly value = model<T | null>(null);
    /** Radio group name; supply a stable one when the group takes part in a form, otherwise a unique one is generated. */
    readonly name = input<string | null>(null);
    readonly disabled = input(false);

    private readonly uid = `mn-segmented-${nextGroup++}`;
    protected readonly hintId = `${this.uid}-hint`;
    protected readonly groupName = computed(() => this.name() ?? this.uid);
    protected readonly hint = computed(() => this.options().find(option => option.value === this.value())?.hint ?? '');

    protected select(value: T): void { this.value.set(value); }
}
