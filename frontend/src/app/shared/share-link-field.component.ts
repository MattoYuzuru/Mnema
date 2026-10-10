import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, inject, input } from '@angular/core';

let nextField = 0;

/**
 * The link as selectable text: what «Поделиться» shows when the browser would not let it copy. A read-only URL field,
 * so the learner can select it and copy it by hand; the label names it and the hint describes it. It appears only after
 * a failure, so once rendered it takes the focus and selects the whole link: the learner who asked to share lands on
 * the thing to copy (and a screen reader reads its name and hint) instead of being left on a button that did nothing.
 * Every screen that offers «Поделиться» uses this one component for the failure.
 */
@Component({
    selector: 'app-share-link-field',
    template: `
      <div class="field">
        <label [for]="fieldId">{{ label() }}</label>
        <input #field type="url" readonly spellcheck="false" autocomplete="off" [id]="fieldId" [value]="url()"
          [attr.aria-describedby]="hintId" (focus)="selectAll()" />
        <p class="hint" [id]="hintId">Браузер не дал скопировать ссылку сам. Выделите её и скопируйте.</p>
      </div>
    `,
    styles: [`:host { display: block; min-inline-size: 0; } input { min-inline-size: 0; }`],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ShareLinkFieldComponent {
    readonly url = input.required<string>();
    readonly label = input('Ссылка');

    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly uid = `mn-share-link-${nextField++}`;
    protected readonly fieldId = this.uid;
    protected readonly hintId = `${this.uid}-hint`;

    constructor() {
        afterNextRender(() => this.host.nativeElement.querySelector('input')?.focus());
    }

    protected selectAll(): void {
        const field = this.host.nativeElement.querySelector('input');
        field?.setSelectionRange(0, field.value.length);
    }
}
