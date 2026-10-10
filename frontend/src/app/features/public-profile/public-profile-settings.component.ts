import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, computed, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AccountProfile, AccountProfileApi } from '../../account-profile.api';
import { ToastService } from '../../core/notifications/toast.service';
import { PUBLIC_PROFILE_TEXT_VERSION, PublicProfileConsent, PublicProfileConsentUpdate } from '../../public-profile';
import { AuthorChipComponent } from '../../shared/author-chip.component';

type ConsentField = 'enabled' | 'showDisplayName' | 'showAvatar' | 'showBio';

const SAVE_FAILED = 'Не удалось сохранить настройки. Проверьте соединение и попробуйте ещё раз.';
const USERNAME_REQUIRED = 'Чтобы включить публичный профиль, задайте имя пользователя в разделе «О вас» и сохраните профиль.';
const TEXT_OUTDATED = 'Текст согласия обновился. Обновите страницу, чтобы прочитать новый текст, и повторите.';

/**
 * «Публичный профиль» of /profile (#423): the separate, per-field, revocable consent to show the profile to other people
 * (152-FZ art. 10.1). Every change is saved at once as the complete state; nothing is ticked beforehand and a field is never
 * implied by the switch. While a save is on its way the controls stay focusable (`aria-disabled`, never `disabled`, so
 * focus is not thrown out) and further changes are ignored; a failed save puts the control back and says why.
 */
@Component({
    selector: 'app-public-profile-settings',
    imports: [RouterLink, AuthorChipComponent],
    template: `
      <p id="public-profile-consent-text" class="consent-text">Когда профиль публичный, ваш логин видят все, кто открывает ваши публичные колоды или страницу профиля. Ниже отметьте, что ещё можно показывать: имя, фото, «О себе». Эти данные видны только на сайте mnema.app, и мы не передаём их никому для других целей. Согласие действует, пока вы его не выключите. Выключить можно здесь в любой момент или письмом на адрес из раздела «Оператор»: профиль и подпись на колодах скроются сразу. Без публичного профиля ваши колоды остаются доступны вам и тем, кому вы дали доступ.</p>
      <p class="hint status" role="status" aria-atomic="true">{{ status() }}</p>
      @if (consent(); as current) {
        <div class="controls">
          <label class="settings-row is-switch is-live">
            <input type="checkbox" role="switch" [checked]="current.enabled" [disabled]="!current.enabled && !username()"
                   [attr.aria-disabled]="busy() ? 'true' : null" [attr.aria-describedby]="switchDescription(current)"
                   (click)="hold($event)" (change)="change('enabled', $event)" />
            <span>Показывать мой профиль другим</span>
          </label>
          @if (!current.enabled && !username()) {
            <p id="consent-username-hint" class="hint">{{ usernameRequired }}</p>
          }
          <fieldset class="check-group" [attr.aria-describedby]="current.enabled ? null : 'consent-fields-hint'">
            <legend>Что ещё показывать</legend>
            <div class="check-field">
              <label class="check-row"><input type="checkbox" [checked]="current.showDisplayName" [disabled]="!current.enabled"
                [attr.aria-disabled]="busy() ? 'true' : null" (click)="hold($event)" (change)="change('showDisplayName', $event)" /><span>Имя</span></label>
            </div>
            <div class="check-field">
              <label class="check-row"><input type="checkbox" [checked]="current.showAvatar" [disabled]="!current.enabled"
                [attr.aria-disabled]="busy() ? 'true' : null" (click)="hold($event)" (change)="change('showAvatar', $event)" /><span>Фото</span></label>
            </div>
            <div class="check-field">
              <label class="check-row"><input type="checkbox" [checked]="current.showBio" [disabled]="!current.enabled"
                [attr.aria-disabled]="busy() ? 'true' : null" (click)="hold($event)" (change)="change('showBio', $event)" /><span>О себе</span></label>
            </div>
            @if (!current.enabled) { <p id="consent-fields-hint" class="hint">Станет доступно, когда вы включите публичный профиль.</p> }
          </fieldset>
        </div>
        @if (error(); as message) {
          <div class="notice error" role="alert">
            <p>{{ message }}</p>
            @if (outdated()) { <button type="button" class="button small" (click)="reloadPage()">Обновить страницу</button> }
          </div>
        }
        <p class="hint terms"><a routerLink="/privacy" fragment="public-profile" target="_blank" rel="noopener">Условия показа профиля<span class="visually-hidden"> (откроется в новой вкладке)</span></a></p>

        <div class="preview">
          <h3>Как меня видят другие</h3>
          @if (current.enabled && username(); as name) {
            <app-author-chip [username]="name" [avatarSrc]="previewAvatar()" />
            @if (current.showDisplayName && profile().displayName; as displayName) { <p class="preview-name">{{ displayName }}</p> }
            @if (current.showBio && profile().bio; as bio) { <p class="preview-bio">{{ bio }}</p> }
          } @else {
            <p class="preview-hidden">Профиль скрыт</p>
          }
        </div>
      } @else if (state() === 'failed') {
        <div class="notice error" role="alert">Не удалось загрузить настройки публичного профиля. Остальной профиль работает как обычно.</div>
        <button type="button" class="button" [attr.aria-disabled]="retrying() ? 'true' : null" (click)="retry()">{{ retrying() ? 'Загружаем…' : 'Повторить' }}</button>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .consent-text { max-inline-size: 60ch; margin: 0 0 1.25rem; }
      .controls { display: grid; gap: .5rem; }
      .status { min-block-size: 1.5em; margin-block: .5rem; }
      .notice { margin-block: .5rem; }
      .notice p { margin: 0 0 .5rem; }
      .notice p:last-child { margin: 0; }
      .terms { margin-block: .5rem 1.5rem; }
      .terms a { display: inline-flex; align-items: center; min-block-size: var(--mn-touch-min, 46px); color: var(--mn-ink); text-underline-offset: .22em; }
      .preview { display: grid; justify-items: start; gap: .5rem; border-block-start: 1px solid var(--mn-rule); padding-block-start: 1rem; }
      .preview h3 { margin: 0; color: var(--mn-ink); font-size: 1rem; font-weight: 650; }
      .preview p { margin: 0; overflow-wrap: anywhere; }
      .preview-name { color: var(--mn-ink); font-weight: 650; }
      .preview-bio { max-inline-size: 60ch; white-space: pre-line; }
      .preview-hidden { color: var(--mn-muted); }
      @media (forced-colors: active) { .preview { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicProfileSettingsComponent implements OnInit {
    private readonly api = inject(AccountProfileApi);
    private readonly toasts = inject(ToastService);
    private readonly document = inject(DOCUMENT);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);

    /** The saved account profile: the login is the precondition, name and «О себе» feed the preview. */
    readonly profile = input.required<AccountProfile>();
    /** The owner's own photo as a local address (it is shown to the owner whatever the consent says). */
    readonly avatarUrl = input<string | null>(null);

    protected readonly usernameRequired = USERNAME_REQUIRED;
    protected readonly state = signal<'loading' | 'ready' | 'failed'>('loading');
    protected readonly consent = signal<PublicProfileConsent | null>(null);
    protected readonly busy = signal(false);
    protected readonly retrying = signal(false);
    /** The status region is rendered empty and filled after the first render, so the first text is announced. */
    private readonly announcing = signal(false);
    protected readonly status = computed(() => {
        if (this.retrying()) return 'Загружаем…';
        if (this.busy()) return 'Сохраняем…';
        return this.state() === 'loading' && this.announcing() ? 'Загружаем настройки…' : '';
    });
    protected readonly error = signal<string | null>(null);
    protected readonly outdated = signal(false);
    protected readonly username = computed(() => this.profile().profileUsername);
    protected readonly previewAvatar = computed(() => {
        const current = this.consent();
        return current !== null && current.showAvatar && this.profile().avatarPresent ? this.avatarUrl() : null;
    });

    constructor() {
        // Next task after the first paint: a live region only announces text that appears after it exists.
        afterNextRender(() => {
            const timer = setTimeout(() => this.announcing.set(true), 50);
            this.destroyRef.onDestroy(() => clearTimeout(timer));
        });
    }

    ngOnInit(): void { void this.load(); }

    async load(): Promise<void> {
        // A failed block stays mounted while it retries, so a focused «Повторить» is not destroyed under the user.
        if (this.state() === 'failed') this.retrying.set(true); else this.state.set('loading');
        const refocus = this.retrying() && this.host.nativeElement.contains(this.document.activeElement);
        try {
            this.consent.set(await firstValueFrom(this.api.loadPublicProfile()));
            this.state.set('ready');
            if (refocus) {
                // The retry button is gone now: focus moves to the first control instead of falling back to the page.
                afterNextRender(() => this.host.nativeElement.querySelector<HTMLInputElement>('input:not(:disabled)')?.focus(), { injector: this.injector });
            }
        } catch { this.state.set('failed'); }
        finally { this.retrying.set(false); }
    }

    protected retry(): void { if (!this.retrying()) void this.load(); }

    /** The switch also hears the consent text; the missing-login hint joins it while it is shown. */
    protected switchDescription(current: PublicProfileConsent): string {
        return !current.enabled && !this.username() ? 'public-profile-consent-text consent-username-hint' : 'public-profile-consent-text';
    }

    /** A click during a save is cancelled before the box flips, so the control keeps the value that is being saved. */
    protected hold(event: Event): void { if (this.busy()) event.preventDefault(); }

    protected reloadPage(): void { this.document.location.reload(); }

    protected async change(field: ConsentField, event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const current = this.consent();
        if (current === null || this.busy()) return;
        const update = this.update(current, field, input.checked);
        this.busy.set(true);
        this.error.set(null);
        this.outdated.set(false);
        try {
            const saved = await firstValueFrom(this.api.savePublicProfile(update));
            this.consent.set(saved);
            input.checked = saved[field];
            this.toasts.echo(field === 'enabled'
                ? (saved.enabled ? 'Публичный профиль включён.' : 'Публичный профиль скрыт.')
                : 'Настройки показа сохранены.');
        } catch (failure) {
            input.checked = current[field];
            await this.explain(failure);
        } finally { this.busy.set(false); }
    }

    /** The complete state after the change. Switching on never ticks a field; switching off clears them all. */
    private update(current: PublicProfileConsent, field: ConsentField, value: boolean): PublicProfileConsentUpdate {
        const enabled = field === 'enabled' ? value : current.enabled;
        const keep = (flag: boolean): boolean => enabled && flag;
        return {
            enabled,
            showDisplayName: field === 'showDisplayName' ? value : keep(current.showDisplayName),
            showAvatar: field === 'showAvatar' ? value : keep(current.showAvatar),
            showBio: field === 'showBio' ? value : keep(current.showBio),
            textVersion: PUBLIC_PROFILE_TEXT_VERSION
        };
    }

    private async explain(failure: unknown): Promise<void> {
        const code = failure instanceof HttpErrorResponse && failure.status === 409
            ? (failure.error as { code?: unknown } | null)?.code : null;
        if (code === 'consent_text_outdated') {
            this.outdated.set(true);
            this.error.set(TEXT_OUTDATED);
        } else this.error.set(code === 'profile_username_required' ? USERNAME_REQUIRED : SAVE_FAILED);
        if (code === 'consent_text_outdated' || code === 'profile_username_required') {
            // The state the server holds is what the controls must show.
            try { this.consent.set(await firstValueFrom(this.api.loadPublicProfile())); } catch { /* the message above stays */ }
        }
    }
}
