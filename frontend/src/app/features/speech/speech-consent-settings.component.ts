import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { SpeechInputApiService } from './speech-input.api';

/** Withdrawal uses the unconditional, idempotent DELETE: the GET disclosure is capability-gated and must not hide this control. */
@Component({
    selector: 'app-speech-consent-settings',
    template: `
      <p class="hint">Можно отозвать согласие на распознавание речи. Для нового голосового ввода понадобится согласие снова; уже начатая обработка может завершиться.</p>
      <button type="button" class="button" [attr.aria-disabled]="busy() ? 'true' : null" (click)="withdraw()">{{ busy() ? 'Отзываем согласие…' : 'Отозвать согласие на распознавание' }}</button>
      <p class="hint" role="status" aria-atomic="true">{{ notice() }}</p>
      @if (error()) { <p class="field-error" role="alert">{{ error() }}</p> }
    `,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SpeechConsentSettingsComponent {
    private readonly api = inject(SpeechInputApiService);
    protected readonly busy = signal(false);
    protected readonly notice = signal('');
    protected readonly error = signal('');

    async withdraw(): Promise<void> {
        if (this.busy()) return;
        this.busy.set(true);
        this.notice.set('');
        this.error.set('');
        try {
            await firstValueFrom(this.api.withdrawConsent());
            this.notice.set('Согласие на распознавание отозвано. Для нового голосового ввода понадобится согласие снова.');
        } catch {
            this.error.set('Не удалось отозвать согласие. Попробуйте ещё раз.');
        } finally {
            this.busy.set(false);
        }
    }
}
