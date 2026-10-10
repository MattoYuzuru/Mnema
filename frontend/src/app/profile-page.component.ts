import { ChangeDetectionStrategy, Component, DestroyRef, Injector, OnInit, afterNextRender, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { AccountProfile, AccountProfileApi } from './account-profile.api';
import { AuthService } from './auth.service';
import { appConfig } from './app.config';
import { validProfileBioEdit } from './profile-bio';
import { DuringStudyMode, NotificationPreferences } from './core/notifications/notification-preferences';
import { ProfilePlanComponent } from './features/plans/profile-plan.component';
import { PublicProfileSettingsComponent } from './features/public-profile/public-profile-settings.component';
import { SpeechConsentSettingsComponent } from './features/speech/speech-consent-settings.component';
import { UsageBudgetComponent } from './features/usage/usage-budget.component';
import { SegmentedChoiceComponent, SegmentedOption } from './shared/segmented-choice.component';

function passwordByteLimit(control: AbstractControl): { passwordBytes: true } | null {
    return new TextEncoder().encode(String(control.value ?? '')).length > 72 ? { passwordBytes: true } : null;
}

@Component({
    selector: 'app-profile-page',
    imports: [ReactiveFormsModule, RouterLink, SegmentedChoiceComponent, UsageBudgetComponent, ProfilePlanComponent, SpeechConsentSettingsComponent,
        PublicProfileSettingsComponent],
    template: `
      <section class="profile-page" aria-labelledby="profile-title">
        <a routerLink="/decks" class="back-link">← Мои колоды</a>
        <header>
          <p class="eyebrow">Личный кабинет</p>
          <h1 id="profile-title" tabindex="-1">Профиль</h1>
          <p>Расскажите о себе — так вас увидят другие, когда вы поделитесь материалами.</p>
        </header>

        @if (loading()) {
          <p role="status">Загружаем профиль…</p>
        } @else if (profile(); as account) {
          @if (showEmailWarning && !account.emailVerified) {
            <p class="notice" role="status">Почта ещё не подтверждена. Проверьте письмо для подтверждения, чтобы сохранить доступ к аккаунту.</p>
          }
          <div class="profile-layout">
            <section class="sheet profile-sheet" aria-labelledby="details-heading">
              <div class="profile-intro">
              <input id="avatar-file" class="file-input" type="file" accept="image/png,image/jpeg,image/webp"
                     aria-describedby="avatar-hint"
                     (change)="uploadAvatar($event)" [disabled]="avatarBusy()" />
              <label for="avatar-file" class="avatar-action" [class.is-busy]="avatarBusy()" aria-label="Изменить аватар">
                <span class="avatar-preview">
                  @if (account.avatarPresent && avatarUrl()) {
                    <img [src]="avatarUrl()" alt="" width="112" height="112" draggable="false" />
                  } @else {
                    <span aria-hidden="true">{{ (account.displayName || account.profileUsername || account.email).charAt(0).toUpperCase() }}</span>
                  }
                </span>
                <span class="avatar-overlay" aria-hidden="true"><span>✎</span>{{ account.avatarPresent ? 'Изменить' : 'Добавить фото' }}</span>
              </label>
              <div><h2 id="details-heading">О вас</h2><p class="email">{{ account.email }}</p><p id="avatar-hint" class="hint">Нажмите на аватар, чтобы выбрать фото. Лучше подходит квадратное изображение.</p></div>
              </div>
              @if (avatarBusy()) { <p role="status">Сохраняем аватар…</p> }
              @if (avatarError()) { <p class="error" role="alert">{{ avatarError() }}</p> }
              <form [formGroup]="form" (ngSubmit)="save()">
                <label for="profile-username">Имя пользователя</label>
                <input id="profile-username" formControlName="profileUsername" autocomplete="nickname"
                       [attr.aria-describedby]="form.controls.profileUsername.touched && form.controls.profileUsername.invalid ? 'username-hint username-error' : 'username-hint'"
                       [attr.aria-invalid]="form.controls.profileUsername.touched && form.controls.profileUsername.invalid" />
                <p id="username-hint" class="hint">Такое имя будет в ссылке на ваш профиль.</p>
                @if (form.controls.profileUsername.touched && form.controls.profileUsername.invalid) {
                  <p id="username-error" class="error field-error" role="alert">Введите имя пользователя из 3–50 допустимых символов.</p>
                }
                <label for="display-name">Отображаемое имя</label>
                <input id="display-name" formControlName="displayName" autocomplete="name" maxlength="200" />
                <label for="profile-bio">О себе</label>
                <textarea id="profile-bio" formControlName="bio" maxlength="200" rows="4"
                          [attr.aria-describedby]="form.controls.bio.touched && form.controls.bio.invalid ? 'bio-hint bio-error' : 'bio-hint'"
                          [attr.aria-invalid]="form.controls.bio.touched && form.controls.bio.invalid"></textarea>
                <p id="bio-hint" class="hint">До 200 символов и 6 строк. Можно разделять абзацы пустой строкой; лишние отступы уберём при сохранении.</p>
                @if (form.controls.bio.touched && form.controls.bio.invalid) {
                  <p id="bio-error" class="error field-error" role="alert">Оставьте не больше 200 символов и 6 строк, без служебных символов.</p>
                }
                @if (saveError()) { <p class="error" role="alert">{{ saveError() }}</p> }
                @if (saveSuccess()) { <p class="success" role="status">Изменения сохранены.</p> }
                <button type="submit" class="button primary" [disabled]="form.invalid || saving()">{{ saving() ? 'Сохраняем…' : 'Сохранить профиль' }}</button>
              </form>
            </section>
            <section id="public-profile-settings" class="sheet" aria-labelledby="public-profile-heading">
              <h2 id="public-profile-heading">Публичный профиль</h2>
              <app-public-profile-settings [profile]="account" [avatarUrl]="avatarUrl()" />
            </section>
            <section id="ai-budget" class="sheet" aria-labelledby="ai-budget-heading">
              <h2 id="ai-budget-heading" tabindex="-1">ИИ-бюджет</h2>
              <app-usage-budget />
              <p class="hint"><a routerLink="/ai">Как Mnema использует ИИ</a></p>
            </section>
            <section id="plan" class="sheet" aria-labelledby="plan-heading">
              <h2 id="plan-heading">Тариф</h2>
              <app-profile-plan />
            </section>
            <section id="speech-consent" class="sheet" aria-labelledby="speech-consent-heading">
              <h2 id="speech-consent-heading" tabindex="-1">Распознавание речи</h2>
              <app-speech-consent-settings />
            </section>
            <section class="sheet" aria-labelledby="notifications-heading">
              <h2 id="notifications-heading">Уведомления</h2>
              <app-segmented-choice legend="Во время занятия" name="notifications-during-study"
                [options]="duringStudyOptions" [value]="preferences.duringStudy()"
                (valueChange)="chooseDuringStudy($event)" />
              <p class="hint">Настройка хранится в этом браузере. Все сообщения остаются в «Входящих» при любом выборе.</p>
            </section>
          @if (account.hasPassword) {
            <section class="sheet password-sheet" aria-labelledby="password-heading">
              <h2 id="password-heading">Пароль</h2>
              <p class="hint">После смены пароля нужно войти заново.</p>
              <form [formGroup]="passwordForm" (ngSubmit)="changePassword()">
                <label for="current-password">Текущий пароль</label>
                <input id="current-password" type="password" formControlName="currentPassword" autocomplete="current-password" />
                <label for="new-password">Новый пароль</label>
                <input id="new-password" type="password" formControlName="newPassword" autocomplete="new-password"
                       [attr.aria-describedby]="passwordForm.controls.newPassword.touched && passwordForm.controls.newPassword.invalid ? 'password-hint password-error' : 'password-hint'"
                       [attr.aria-invalid]="passwordForm.controls.newPassword.touched && passwordForm.controls.newPassword.invalid" />
                <p id="password-hint" class="hint">Не менее 12 символов.</p>
                @if (passwordForm.controls.newPassword.touched && passwordForm.controls.newPassword.invalid) {
                  <p id="password-error" class="error field-error" role="alert">Новый пароль слишком короткий или длинный.</p>
                }
                <label for="confirm-password">Повторите новый пароль</label>
                <input id="confirm-password" type="password" formControlName="confirmPassword" autocomplete="new-password" />
                @if (passwordError()) { <p class="error" role="alert">{{ passwordError() }}</p> }
                <button type="submit" class="button primary" [disabled]="passwordForm.invalid || passwordBusy()">Сменить пароль</button>
              </form>
            </section>
          }
          </div>
        } @else {
          <p class="error" role="alert">{{ loadError() || 'Не удалось открыть профиль.' }}</p>
          <button type="button" class="button primary" (click)="load()">Повторить</button>
        }
      </section>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; color: var(--mn-body); }
      * { box-sizing: border-box; }
      .profile-page { inline-size: min(100%, var(--mn-workspace-width)); margin-inline: auto; padding: clamp(1.5rem, 3vw, 2.5rem) clamp(1.125rem, 5vw, 3rem); }
      .back-link { display: inline-flex; align-items: center; min-block-size: var(--mn-touch-min, 46px); color: var(--mn-ink); text-underline-offset: .22em; }
      header { max-inline-size: 45rem; padding-block: clamp(1.25rem, 3vw, 2rem); }
      h1, h2 { color: var(--mn-ink); font-family: var(--mn-font-display, Georgia, serif); font-weight: 500; overflow-wrap: anywhere; }
      h1 { margin: .35rem 0 .75rem; font-size: clamp(2.7rem, 7vw, 5rem); line-height: .98; }
      /* The anchor jump (/profile#ai-budget) must leave the heading and its focus ring clear of the viewport edge. */
      #ai-budget-heading { scroll-margin-block-start: 1.5rem; }
      h2 { margin: 0 0 1.5rem; font-size: clamp(1.8rem, 3vw, 2.25rem); line-height: 1.05; }
      header > p:last-child { max-inline-size: 52ch; color: var(--mn-muted); }
      .profile-layout { display: grid; gap: clamp(1.5rem, 4vw, 3rem); }
      .profile-intro { display: grid; grid-template-columns: 7rem minmax(0, 1fr); align-items: center; gap: clamp(1.25rem, 4vw, 2.5rem); margin-block-end: 1.5rem; }
      .sheet { min-inline-size: 0; border-block-start: 1px solid var(--mn-ink); border-block-end: 1px solid var(--mn-rule); padding: clamp(1.25rem, 3vw, 2rem); background: var(--mn-sheet); }
      .password-sheet { margin-block-start: 0; }
      .avatar-action { position: relative; display: block; inline-size: 7rem; block-size: 7rem; border-radius: 50%; cursor: pointer; overflow: hidden; }
      .avatar-preview { display: grid; place-items: center; inline-size: 100%; block-size: 100%; border: 1px solid var(--mn-field-border); border-radius: 50%; overflow: hidden; background: var(--mn-soft); color: var(--mn-ink); font: 3rem var(--mn-font-display, Georgia, serif); }
      .avatar-preview img { display: block; inline-size: 100%; block-size: 100%; object-fit: cover; }
      .avatar-overlay { position: absolute; inset: 0; display: grid; place-content: center; gap: .1rem; border-radius: 50%; background: rgb(33 20 96 / 82%); color: white; font: 600 .76rem/1.2 var(--mn-font-body, system-ui, sans-serif); text-align: center; opacity: 0; transition: opacity .18s ease; }
      .avatar-overlay span { font-size: 1.35rem; }
      .avatar-action:hover .avatar-overlay, .file-input:focus-visible + .avatar-action .avatar-overlay { opacity: 1; }
      .avatar-action.is-busy { cursor: wait; opacity: .6; }
      label:not(.avatar-action) { display: block; margin-block-end: .4rem; font-weight: 650; }
      .file-input { position: absolute; inline-size: 1px; block-size: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
      .file-input:focus-visible + .avatar-action { outline: 3px solid var(--mn-focus); outline-offset: 3px; }
      input:not([type=file]), textarea { inline-size: 100%; min-inline-size: 0; min-block-size: var(--mn-touch-min, 46px); margin-block-end: 1rem; border: 1px solid var(--mn-field-border); border-radius: var(--mn-radius, 2px); padding: .65rem .75rem; background: var(--mn-sheet); color: var(--mn-body); font: 1rem/1.5 var(--mn-font-body, system-ui, sans-serif); }
      textarea { resize: vertical; }
      input[aria-invalid=true] { border: 2px solid var(--mn-danger); }
      input:focus-visible, textarea:focus-visible, button:focus-visible, a:focus-visible { outline: 3px solid var(--mn-focus); outline-offset: 3px; }
      .email { color: var(--mn-muted); font-size: .9rem; }
      .hint { margin: .25rem 0 1.25rem; }
      .email { margin: -.6rem 0 .75rem; overflow-wrap: anywhere; }
      .error { color: var(--mn-danger); }
      .field-error { margin: -.7rem 0 1rem; }
      .success { color: var(--mn-positive); }
      .notice { margin: 0 0 1.5rem; border-inline-start: 3px solid var(--mn-caution); padding: 1rem 1.25rem; background: var(--mn-sheet); color: var(--mn-body); }
      @media (max-width: 36rem) { .profile-intro { grid-template-columns: 1fr; } }
      @media (max-width: 36rem) { .sheet { padding-inline: 0; background: transparent; } }
      @media (prefers-reduced-motion: reduce) { .avatar-overlay { transition: none; } }
      @media (forced-colors: active) { .sheet, .avatar-preview, button, .notice { border-color: CanvasText; } input:focus-visible, textarea:focus-visible, button:focus-visible, a:focus-visible, .file-input:focus-visible + .avatar-action { outline-color: Highlight; } .avatar-overlay { color: CanvasText; background: Canvas; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProfilePageComponent implements OnInit {
    private readonly api = inject(AccountProfileApi);
    private readonly auth = inject(AuthService);
    private readonly fb = inject(FormBuilder);
    private readonly injector = inject(Injector);
    private readonly route = inject(ActivatedRoute);
    private readonly document = inject(DOCUMENT);
    private ownAvatar: string | null = null;
    private avatarRequest = 0;
    protected readonly preferences = inject(NotificationPreferences);
    protected readonly duringStudyOptions: readonly SegmentedOption<DuringStudyMode>[] = [
        { value: 'AT_PAUSES', label: 'В паузах', hint: 'Сообщение появится после ответа или в конце занятия.' },
        { value: 'IMMEDIATE', label: 'Сразу', hint: 'Сообщение появится поверх задания.' },
        { value: 'BADGE_ONLY', label: 'Только значок', hint: 'Всплывающих сообщений не будет: только значок у колокольчика.' }
    ];
    readonly showEmailWarning = appConfig.features.showEmailVerificationWarning;
    readonly profile = signal<AccountProfile | null>(null);
    readonly avatarUrl = signal<string | null>(null);
    readonly loading = signal(true);
    readonly loadError = signal<string | null>(null);
    readonly saving = signal(false);
    readonly saveError = signal<string | null>(null);
    readonly saveSuccess = signal(false);
    readonly avatarBusy = signal(false);
    readonly avatarError = signal<string | null>(null);
    readonly passwordBusy = signal(false);
    readonly passwordError = signal<string | null>(null);
    readonly form = this.fb.nonNullable.group({
        profileUsername: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9_.-]{3,50}$/u)]],
        displayName: ['', Validators.maxLength(200)],
        bio: ['', (control: AbstractControl) => validProfileBioEdit(String(control.value ?? '')) ? null : { profileBio: true }]
    });
    readonly passwordForm = this.fb.nonNullable.group({
        currentPassword: ['', Validators.required],
        newPassword: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(128), passwordByteLimit]],
        confirmPassword: ['', Validators.required]
    });

    constructor() {
        inject(DestroyRef).onDestroy(() => { this.avatarRequest++; this.releaseAvatar(); });
        // A link to `/profile#ai-budget` while this page is already open changes only the fragment. Before the first load
        // finishes the block does not exist yet; `load()` then reveals it itself.
        this.route.fragment.pipe(takeUntilDestroyed()).subscribe(fragment => {
            if (!this.loading()) this.revealFragment(fragment);
        });
    }

    ngOnInit(): void { void this.load(); }

    protected chooseDuringStudy(mode: DuringStudyMode | null): void {
        if (mode !== null) this.preferences.setDuringStudy(mode);
    }

    async load(): Promise<void> {
        this.loading.set(true);
        this.loadError.set(null);
        try {
            const profile = await firstValueFrom(this.api.load());
            this.profile.set(profile);
            this.form.setValue({ profileUsername: profile.profileUsername ?? '', displayName: profile.displayName ?? '',
                bio: profile.bio ?? '' });
            void this.showOwnAvatar(profile.avatarPresent);
        } catch {
            this.loadError.set('Проверьте соединение и попробуйте снова.');
        } finally {
            this.loading.set(false);
            this.revealFragment(this.route.snapshot.fragment);
        }
    }

    /**
     * The owner's photo comes from the bearer-protected `/me/avatar` as a local object address: it stays visible without a
     * public-profile consent and never travels through the public endpoint. The previous address is released on replace
     * and on destroy; an older response that arrives late is dropped.
     */
    private async showOwnAvatar(present: boolean): Promise<void> {
        const request = ++this.avatarRequest;
        let next: string | null = null;
        if (present) {
            try {
                const blob = await firstValueFrom(this.api.loadAvatar());
                if (request === this.avatarRequest && ['image/png', 'image/jpeg', 'image/webp'].includes(blob.type)) next = URL.createObjectURL(blob);
            } catch { /* the letter placeholder stays */ }
        }
        if (request !== this.avatarRequest) {
            if (next !== null) URL.revokeObjectURL(next);
            return;
        }
        this.releaseAvatar();
        this.ownAvatar = next;
        this.avatarUrl.set(next);
    }

    private releaseAvatar(): void {
        if (this.ownAvatar !== null) URL.revokeObjectURL(this.ownAvatar);
        this.ownAvatar = null;
    }

    /**
     * `/profile#ai-budget` (the link of a usage notification): the block only exists once the profile has loaded, so the
     * router's own anchor scroll misses it. Bring it into view after the render and give its heading focus, so a keyboard
     * or screen-reader user lands on it instead of at the top of the page. Also runs when the fragment changes later.
     */
    private revealFragment(fragment: string | null): void {
        if (fragment !== 'ai-budget') return;
        afterNextRender({ write: () => {
            const heading = this.document.getElementById('ai-budget-heading');
            if (heading === null) return;
            heading.scrollIntoView({ block: 'start' });
            heading.focus({ preventScroll: true });
        } }, { injector: this.injector });
    }

    async save(): Promise<void> {
        if (this.form.invalid || this.saving()) return;
        this.saving.set(true);
        this.saveError.set(null);
        this.saveSuccess.set(false);
        try {
            const profile = await firstValueFrom(this.api.update(this.form.getRawValue()));
            this.profile.set(profile);
            this.form.controls.bio.setValue(profile.bio ?? '');
            this.saveSuccess.set(true);
        } catch { this.saveError.set('Не удалось сохранить профиль. Проверьте данные и попробуйте снова.'); }
        finally { this.saving.set(false); }
    }

    async uploadAvatar(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file || this.avatarBusy()) return;
        this.avatarError.set(null);
        if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) {
            this.avatarError.set('Выберите изображение PNG, JPEG или WebP.');
            return;
        }
        if (file.size > 10 * 1024 * 1024 || !file.size) {
            this.avatarError.set('Изображение слишком большое или пустое. Выберите другое.');
            return;
        }
        this.avatarBusy.set(true);
        try {
            await firstValueFrom(this.api.uploadAvatar(file));
            const current = this.profile();
            if (current) {
                this.profile.set({ ...current, avatarPresent: true });
                void this.showOwnAvatar(true);
            }
        } catch { this.avatarError.set('Не удалось сохранить аватар. Проверьте формат и размеры изображения.'); }
        finally { this.avatarBusy.set(false); }
    }

    async changePassword(): Promise<void> {
        if (this.passwordForm.invalid || this.passwordBusy()) return;
        const { currentPassword, newPassword, confirmPassword } = this.passwordForm.getRawValue();
        if (newPassword !== confirmPassword) {
            this.passwordError.set('Новые пароли не совпадают.');
            return;
        }
        this.passwordBusy.set(true);
        this.passwordError.set(null);
        try {
            await this.auth.setPassword(currentPassword, newPassword);
            this.passwordForm.reset();
        } catch (error) {
            this.passwordError.set(error instanceof HttpErrorResponse && error.status === 400
                ? 'Новый пароль слишком короткий или длинный.'
                : 'Не удалось сменить пароль. Проверьте текущий пароль и соединение.');
        }
        finally { this.passwordBusy.set(false); }
    }
}
