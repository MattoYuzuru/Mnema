import { ChangeDetectionStrategy, Component, computed, input, model, signal } from '@angular/core';

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
 * platform. The explanation of the selected option sits under the group and describes the fieldset. Until the reader changes the
 * choice it is a plain paragraph (visible, and the fieldset's description); a separate polite live region is there from the first
 * render but empty, and after the first change it carries the explanation and the plain paragraph goes. Nothing is read out when a
 * page draws many groups (the rows of a list), and the first change is announced reliably because the region already existed. Long explanations belong in a `Toggletip` projected after the
 * segments, never in a hover-only tooltip.
 */
@Component({
    selector: 'app-segmented-choice',
    template: `
      <fieldset class="segmented" [disabled]="disabled()" [attr.aria-describedby]="describedBy() === null ? describingId() : describingId() + ' ' + describedBy()"
        [attr.aria-invalid]="invalid() ? 'true' : null" [attr.aria-busy]="busy() ? 'true' : null">
        <legend [class.visually-hidden]="legendHidden()">{{ legend() }}</legend>
        <div class="row">
          <div class="segments">
            @for (option of options(); track option.value) {
              <label class="segment">
                <input type="radio" [name]="groupName()" [value]="option.value" [checked]="option.value === value()"
                  [disabled]="option.disabled === true" [attr.aria-disabled]="busy() ? 'true' : null" (click)="guard($event)" (change)="select(option.value)" />
                <span class="mark" aria-hidden="true"></span>
                <span class="text">{{ option.label }}</span>
              </label>
            }
          </div>
          <ng-content />
        </div>
        @if (!changed()) { <p class="hint" [id]="staticHintId">{{ hint() }}</p> }
        <p class="hint" [id]="hintId" aria-live="polite">{{ changed() ? hint() : '' }}</p>
      </fieldset>
    `,
    styleUrl: './segmented-choice.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SegmentedChoiceComponent<T extends string = string> {
    readonly legend = input.required<string>();
    /** The legend names the group for assistive technology but is not drawn (a row of a list where the title above already says what is chosen). */
    readonly legendHidden = input(false);
    readonly options = input.required<readonly SegmentedOption<T>[]>();
    /** The selected value; `null` while nothing is selected. */
    readonly value = model<T | null>(null);
    /** Radio group name; supply a stable one when the group takes part in a form, otherwise a unique one is generated. */
    readonly name = input<string | null>(null);
    readonly disabled = input(false);
    /** Ids of elements that describe the whole group (for example its error); they follow the live hint. */
    readonly describedBy = input<string | null>(null);
    /** The choice was refused (a missing answer). */
    readonly invalid = input(false);
    /** The choice is locked for a moment (a request is on its way) but stays in the focus order: `aria-disabled`, and every change is refused. */
    readonly busy = input(false);

    private readonly uid = `mn-segmented-${nextGroup++}`;
    protected readonly hintId = `${this.uid}-hint`;
    protected readonly staticHintId = `${this.uid}-static-hint`;
    protected readonly groupName = computed(() => this.name() ?? this.uid);
    /** The reader has changed the choice at least once: from then on the live region carries the explanation. */
    protected readonly changed = signal(false);
    /** What describes the group: the static explanation until the reader changes the choice, the live one after. */
    protected readonly describingId = computed(() => this.changed() ? this.hintId : this.staticHintId);
    protected readonly hint = computed(() => this.options().find(option => option.value === this.value())?.hint ?? '');

    protected select(value: T): void {
        if (this.busy()) return;
        this.changed.set(true);
        this.value.set(value);
    }

    /** A radio cannot be refused with `disabled` without losing the focus; cancelling the click keeps the old choice. */
    protected guard(event: Event): void { if (this.busy()) event.preventDefault(); }
}
