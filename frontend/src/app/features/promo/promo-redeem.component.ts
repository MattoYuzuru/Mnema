import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, input, linkedSignal, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { inject } from '@angular/core';

import { PromoApiService, promoProblem } from './promo-api.service';
import { promoMessage } from './promo-messages';
import { PromoRedemption } from './promo.models';

let nextRedeem = 0;

/**
 * The promo code field: a label, a text field that does not autofill or auto-correct and capitalizes as a code, and «Применить».
 * Enter submits (it is a form). A refusal is one calm sentence under the field (`aria-invalid` + `aria-describedby`, announced as an
 * alert) and keeps the typed code; a success is a status that says the plan and its end. A lost answer is retried with the same
 * `Idempotency-Key`, so pressing again can never redeem twice. The component grants nothing: the server does, and `redeemed`
 * tells the host to reload the entitlement.
 */
@Component({
    selector: 'app-promo-redeem',
    template: `
      <form class="field" novalidate (submit)="submit($event)">
        <label [for]="inputId">Промокод</label>
        <div class="field-row">
          <input #field [id]="inputId" name="promo-code" type="text" autocomplete="off" autocapitalize="characters" autocorrect="off"
                 spellcheck="false" enterkeyhint="go" maxlength="64" [value]="code()" [readOnly]="pending()"
                 [attr.aria-invalid]="error() === null ? null : 'true'" [attr.aria-describedby]="describedBy()"
                 (input)="onInput($event)" />
          <button type="submit" class="button" [attr.aria-disabled]="pending() ? 'true' : null">{{ pending() ? 'Применяем…' : 'Применить' }}</button>
        </div>
        <p class="hint" [id]="hintId">Промокод ничего не списывает. Доступ по нему не продлевается сам; скидка применяется только к будущей оплате.</p>
        <div [id]="errorId" role="alert">@if (error(); as message) { <p class="field-error">{{ message }}</p> }</div>
        <div role="status">@if (result(); as done) { <p class="notice success">{{ done.message }}</p> }</div>
      </form>
    `,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PromoRedeemComponent {
    private readonly api = inject(PromoApiService);
    private readonly destroyRef = inject(DestroyRef);
    /** A code to put into the field (a campaign link): typed, not applied. */
    readonly prefill = input('');
    /** A redemption succeeded: the host reloads the plan. */
    readonly redeemed = output<PromoRedemption>();

    private readonly uid = `mn-promo-${nextRedeem++}`;
    protected readonly inputId = `${this.uid}-input`;
    protected readonly hintId = `${this.uid}-hint`;
    protected readonly errorId = `${this.uid}-error`;
    protected readonly code = linkedSignal(() => this.prefill());
    protected readonly pending = signal(false);
    protected readonly error = signal<string | null>(null);
    protected readonly result = signal<PromoRedemption | null>(null);
    protected readonly describedBy = computed(() => `${this.hintId} ${this.errorId}`);
    private readonly field = viewChild<ElementRef<HTMLInputElement>>('field');
    /** Unknown commands keep their key even if the reader temporarily edits a different code. No code is persisted. */
    private readonly retries = new Map<string, string>();

    protected onInput(event: Event): void {
        this.code.set((event.target as HTMLInputElement).value);
        this.error.set(null);
    }

    protected async submit(event: Event): Promise<void> {
        event.preventDefault();
        if (this.pending()) return;
        const code = this.code().trim();
        if (code.length === 0) {
            this.error.set('Введите промокод.');
            this.field()?.nativeElement.focus();
            return;
        }
        // Server receipts fingerprint the normalized code: case, whitespace, dashes and underscores do not create a new command.
        const normalized = code.toUpperCase().replace(/[\s_-]/gu, '');
        const key = this.retries.get(normalized) ?? crypto.randomUUID();
        this.pending.set(true);
        this.error.set(null);
        this.result.set(null);
        try {
            const redemption = await firstValueFrom(this.api.redeem(code, key).pipe(takeUntilDestroyed(this.destroyRef)));
            if (this.destroyRef.destroyed) return;
            this.retries.delete(normalized);
            this.code.set('');
            const field = this.field()?.nativeElement;
            if (field !== undefined) field.value = '';
            this.result.set(redemption);
            this.redeemed.emit(redemption);
        } catch (failure) {
            if (this.destroyRef.destroyed) return;
            const problem = promoProblem(failure);
            if (problem.retryable) this.retries.set(normalized, key);
            else this.retries.delete(normalized);
            this.error.set(promoMessage(problem));
            this.field()?.nativeElement.focus();
        } finally {
            if (!this.destroyRef.destroyed) this.pending.set(false);
        }
    }
}
