import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, OnInit, afterNextRender, computed, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AccountProfile, AccountProfileApi } from '../../account-profile.api';
import { ToastService } from '../../core/notifications/toast.service';
import { PUBLIC_PROFILE_TEXT_VERSION, PublicProfileConsent, PublicProfileConsentUpdate } from '../../public-profile';
import { AuthorChipComponent } from '../../shared/author-chip.component';
import { COMMUNITY_LAUNCH } from '../../shared/community-launch';

type ConsentField = 'enabled' | 'showDisplayName' | 'showAvatar' | 'showBio';

const SAVE_FAILED = 'Не удалось сохранить настройки. Проверьте соединение и попробуйте ещё раз.';
const USERNAME_REQUIRED = 'Чтобы включить публичный профиль, задайте имя пользователя в разделе «О вас» и сохраните профиль.';
const TEXT_OUTDATED = 'Текст согласия обновился. Обновите страницу, чтобы прочитать новый текст, и повторите.';

/**
 * «Публичный профиль» of /profile (#423): the separate, per-field, revocable consent to show the profile to other people
 * (152-FZ art. 10.1). Every change is saved at once as the complete state; nothing is ticked beforehand and a field is never
 * implied by the switch. While a save is on its way the controls stay focusable (`aria-disabled`, never `disabled`, so
 * focus is not thrown out) and further changes are ignored; a failed save puts the control back and says why. With the consent on and a login, and
 * once the Community is open (`COMMUNITY_LAUNCH`), the preview ends with the link «Открыть публичную страницу профиля» to the real page `/u/<логин>`.
 */
@Component({
    selector: 'app-public-profile-settings',
    imports: [RouterLink, AuthorChipComponent],
    template: `
      <p id="public-profile-consent-text" class="hint consent-text">Другие увидят ваш логин<span>{{ username() ? ': @' + username() : '' }}</span>. Остальное выбираете вы.</p>
      <p class="hint status" role="status" aria-atomic="true">{{ status() }}</p>
      @if (consent(); as current) {
        <div class="controls">
          <button type="button" class="toggle-button profile-toggle" [attr.aria-pressed]="current.enabled"
            [disabled]="!current.enabled && !username()" [attr.aria-disabled]="busy() ? 'true' : null"
            [attr.aria-describedby]="switchDescription(current)" (click)="change('enabled')">Показывать мой профиль другим</button>
          @if (!current.enabled && !username()) {
            <p id="consent-username-hint" class="hint">{{ usernameRequired }}</p>
          }
          @if (current.enabled) {
            <fieldset class="profile-fields">
              <legend>Что показывать?</legend>
              <div class="toggle-buttons">
                <button type="button" class="toggle-button" [attr.aria-pressed]="current.showDisplayName" [attr.aria-disabled]="busy() ? 'true' : null" (click)="change('showDisplayName')">Имя</button>
                <button type="button" class="toggle-button" [attr.aria-pressed]="current.showAvatar" [attr.aria-disabled]="busy() ? 'true' : null" (click)="change('showAvatar')">Фото</button>
                <button type="button" class="toggle-button" [attr.aria-pressed]="current.showBio" [attr.aria-disabled]="busy() ? 'true' : null" (click)="change('showBio')">О себе</button>
              </div>
            </fieldset>
          }
        </div>
        @if (error(); as message) {
          <div class="notice error" role="alert">
            <p>{{ message }}</p>
            @if (outdated()) { <button type="button" class="button small" (click)="reloadPage()">Обновить страницу</button> }
          </div>
        }

        <div class="preview">
          <h3>Как меня видят другие</h3>
          @if (current.enabled && username(); as name) {
            <app-author-chip [username]="name" [avatarSrc]="previewAvatar()" />
            @if (current.showDisplayName && profile().displayName; as displayName) { <p class="preview-name">{{ displayName }}</p> }
            @if (current.showBio && profile().bio; as bio) { <p class="preview-bio">{{ bio }}</p> }
            @if (communityLaunched) { <a class="page-link" [routerLink]="['/u', name]">Открыть публичную страницу профиля</a> }
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
      .controls { display: grid; justify-items: start; gap: .75rem; }
      .profile-fields { min-inline-size: 0; margin: 0; border: 0; padding: 0; }
      .profile-fields legend { padding: 0 0 .5rem; color: var(--mn-ink); font-weight: 650; }
      .status { min-block-size: 1.5em; margin-block: .5rem; }
      .status:empty { position: absolute; inline-size: 1px; block-size: 1px; min-block-size: 0; overflow: hidden; margin: 0; }
      .notice { margin-block: .5rem; }
      .notice p { margin: 0 0 .5rem; }
      .notice p:last-child { margin: 0; }
      .preview { margin-block-start: var(--mn-space-5); display: grid; justify-items: start; gap: .5rem; border-block-start: 1px solid var(--mn-rule); padding-block-start: 1rem; }
      .preview h3 { margin: 0; color: var(--mn-ink); font-size: 1rem; font-weight: 650; }
      .preview p { margin: 0; overflow-wrap: anywhere; }
      .preview-name { color: var(--mn-ink); font-weight: 650; }
      .preview-bio { max-inline-size: 60ch; white-space: pre-line; }
      .page-link { display: inline-flex; align-items: center; min-block-size: var(--mn-touch-min, 46px); color: var(--mn-ink); text-underline-offset: .22em; }
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
    /** The own page `/u/<логин>` is offered once the Community is open to readers (the route itself exists always). */
    protected readonly communityLaunched = inject(COMMUNITY_LAUNCH);

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
                afterNextRender(() => this.host.nativeElement.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus(), { injector: this.injector });
            }
        } catch { this.state.set('failed'); }
        finally { this.retrying.set(false); }
    }

    protected retry(): void { if (!this.retrying()) void this.load(); }

    /** The switch also hears the consent text; the missing-login hint joins it while it is shown. */
    protected switchDescription(current: PublicProfileConsent): string {
        return !current.enabled && !this.username() ? 'public-profile-consent-text consent-username-hint' : 'public-profile-consent-text';
    }

    protected reloadPage(): void { this.document.location.reload(); }

    protected async change(field: ConsentField): Promise<void> {
        const current = this.consent();
        if (current === null || this.busy() || (field === 'enabled' && !current.enabled && !this.username())) return;
        const update = this.update(current, field, !current[field]);
        this.busy.set(true);
        this.error.set(null);
        this.outdated.set(false);
        try {
            const saved = await firstValueFrom(this.api.savePublicProfile(update));
            this.consent.set(saved);
            this.toasts.echo(field === 'enabled'
                ? (saved.enabled ? 'Публичный профиль включён.' : 'Публичный профиль скрыт.')
                : 'Настройки показа сохранены.');
        } catch (failure) {
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
