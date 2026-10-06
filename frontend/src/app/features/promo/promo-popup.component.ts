import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, inject, input, output, viewChild } from '@angular/core';

import { PromoCampaign } from './promo.models';

let nextPopup = 0;

/**
 * The promo popup: a modal `<dialog>` that nearly fills a phone and is a calm sheet on a wide screen. The platform traps focus,
 * makes the rest inert and closes on Esc (`cancel`), which this component turns into «dismissed» instead of closing behind the
 * host's back. Three ways out, all explicit and equal in reach: the primary action, «Не сейчас» (silent for a while), «Больше не показывать»
 * (final). There is no checkbox, so nothing is ticked for the learner. The host renders it with `@if` and removes it to close it.
 * Motion is a short fade only with no reduced-motion preference, and none otherwise.
 */
@Component({
    selector: 'app-promo-popup',
    template: `
      <dialog #surface class="promo-popup" [attr.aria-labelledby]="titleId" [attr.aria-describedby]="bodyId" (cancel)="onCancel($event)">
        <div class="promo-popup-body">
          <button type="button" class="promo-popup-close" aria-label="Закрыть" (click)="dismissed.emit()"><span aria-hidden="true">×</span></button>
          <p class="eyebrow">Предложение</p>
          <h2 #title [id]="titleId" tabindex="-1">{{ campaign().title }}</h2>
          <p [id]="bodyId" class="promo-popup-text">{{ campaign().body }}</p>
          @if (campaign().code; as code) { <p class="hint">Промокод: <strong class="promo-popup-code">{{ code }}</strong>. Он не применяется сам: введите его на странице тарифов.</p> }
          <div class="promo-popup-actions">
            <button type="button" class="button primary" (click)="accepted.emit()">{{ campaign().cta }}</button>
            <button type="button" class="button" (click)="dismissed.emit()">Не сейчас</button>
            <button type="button" class="button quiet" (click)="declined.emit()">Больше не показывать</button>
          </div>
        </div>
      </dialog>
    `,
    styleUrl: './promo-popup.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PromoPopupComponent {
    readonly campaign = input.required<PromoCampaign>();
    /** The primary action (the call to action). */
    readonly accepted = output<void>();
    /** Closed without a decision: «Не сейчас», «×» or Esc. */
    readonly dismissed = output<void>();
    /** «Больше не показывать»: never again. */
    readonly declined = output<void>();

    private readonly uid = `mn-promo-popup-${nextPopup++}`;
    protected readonly titleId = `${this.uid}-title`;
    protected readonly bodyId = `${this.uid}-body`;
    private opened: HTMLDialogElement | null = null;
    private readonly surface = viewChild.required<ElementRef<HTMLDialogElement>>('surface');
    private readonly title = viewChild.required<ElementRef<HTMLElement>>('title');

    constructor() {
        afterNextRender(() => {
            const dialog = this.surface().nativeElement;
            this.opened = dialog;
            if (!dialog.open) dialog.showModal();
            this.title().nativeElement.focus();
        });
        // Taking the element away does not run `close()`, which is what hands focus back to what had it before the popup: do it first.
        inject(DestroyRef).onDestroy(() => {
            if (this.opened?.open) this.opened.close();
        });
    }

    /** Esc closes a modal dialog natively; the host takes the popup away instead. */
    protected onCancel(event: Event): void {
        event.preventDefault();
        this.dismissed.emit();
    }
}
