import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AccountProfile, AccountProfileApi } from './account-profile.api';
import { AuthService } from './auth.service';

@Component({
    selector: 'app-profile-page',
    imports: [ReactiveFormsModule, RouterLink],
    template: `
      <section class="profile-page" aria-labelledby="profile-title">
        <a routerLink="/decks" class="back-link">← Мои колоды</a>
        <header>
          <p class="eyebrow">Личный кабинет</p>
          <h1 id="profile-title" tabindex="-1">Профиль</h1>
          <p>Имя и аватар видны там, где вы делитесь материалами.</p>
        </header>

        @if (loading()) {
          <p role="status">Загружаем профиль…</p>
        } @else if (profile(); as account) {
          <div class="profile-grid">
            <section class="sheet" aria-labelledby="avatar-heading">
              <h2 id="avatar-heading">Аватар</h2>
              <div class="avatar-preview">
                @if (account.avatarPresent && avatarUrl()) {
                  <img [src]="avatarUrl()" alt="Ваш аватар" width="112" height="112" />
                } @else {
                  <span aria-hidden="true">{{ (account.displayName || account.profileUsername || account.email).charAt(0).toUpperCase() }}</span>
                }
              </div>
              <label for="avatar-file" class="file-label">Выбрать изображение</label>
              <input id="avatar-file" type="file" accept="image/png,image/jpeg,image/webp"
                     (change)="uploadAvatar($event)" [disabled]="avatarBusy()" />
              <p class="hint">PNG, JPEG или WebP, до 10 МБ и 1024 × 1024 пикселей.</p>
              @if (avatarBusy()) { <p role="status">Сохраняем аватар…</p> }
              @if (avatarError()) { <p class="error" role="alert">{{ avatarError() }}</p> }
            </section>

            <section class="sheet" aria-labelledby="details-heading">
              <h2 id="details-heading">О вас</h2>
              <p class="email">{{ account.email }}</p>
              <form [formGroup]="form" (ngSubmit)="save()">
                <label for="profile-username">Имя пользователя</label>
                <input id="profile-username" formControlName="profileUsername" autocomplete="nickname"
                       aria-describedby="username-hint" />
                <p id="username-hint" class="hint">3–50 символов: латинские буквы, цифры, точка, дефис или подчёркивание.</p>
                <label for="display-name">Отображаемое имя</label>
                <input id="display-name" formControlName="displayName" autocomplete="name" maxlength="200" />
                <label for="profile-bio">О себе</label>
                <textarea id="profile-bio" formControlName="bio" maxlength="200" rows="4"></textarea>
                @if (saveError()) { <p class="error" role="alert">{{ saveError() }}</p> }
                @if (saveSuccess()) { <p class="success" role="status">Изменения сохранены.</p> }
                <button type="submit" [disabled]="form.invalid || saving()">{{ saving() ? 'Сохраняем…' : 'Сохранить профиль' }}</button>
              </form>
            </section>
          </div>
          @if (account.hasPassword) {
            <section class="sheet password-sheet" aria-labelledby="password-heading">
              <h2 id="password-heading">Пароль</h2>
              <p class="hint">После смены пароля нужно войти заново.</p>
              <form [formGroup]="passwordForm" (ngSubmit)="changePassword()">
                <label for="current-password">Текущий пароль</label>
                <input id="current-password" type="password" formControlName="currentPassword" autocomplete="current-password" />
                <label for="new-password">Новый пароль</label>
                <input id="new-password" type="password" formControlName="newPassword" autocomplete="new-password" />
                @if (passwordError()) { <p class="error" role="alert">{{ passwordError() }}</p> }
                <button type="submit" [disabled]="passwordForm.invalid || passwordBusy()">Сменить пароль</button>
              </form>
            </section>
          }
        } @else {
          <p class="error" role="alert">{{ loadError() || 'Не удалось открыть профиль.' }}</p>
          <button type="button" (click)="load()">Повторить</button>
        }
      </section>
    `,
    styles: [`
      :host { display: block; color: #342e44; }
      .profile-page { max-width: 72rem; margin: 0 auto; padding: clamp(1.25rem, 4vw, 3rem); }
      .back-link { color: #281378; text-underline-offset: .2em; }
      header { max-width: 42rem; padding-block: 1.7rem 2rem; }
      .eyebrow { margin: 0; color: #655b80; font-size: .8rem; font-weight: 700; letter-spacing: .13em; text-transform: uppercase; }
      h1, h2 { color: #281378; font-family: Georgia, 'Times New Roman', serif; }
      h1 { margin: .35rem 0 .75rem; font-size: clamp(2.4rem, 6vw, 4rem); }
      h2 { margin: 0 0 1.5rem; font-size: 1.55rem; }
      .profile-grid { display: grid; grid-template-columns: minmax(15rem, .75fr) minmax(0, 1.5fr); gap: 1rem; align-items: start; }
      .sheet { padding: clamp(1.25rem, 3vw, 2rem); border: 1px solid #c9c0ce; background: #fbf8ef; }
      .password-sheet { margin-top: 1rem; max-width: 40rem; }
      .avatar-preview { display: grid; place-items: center; width: 7rem; height: 7rem; margin-bottom: 1.25rem;
        border: 1px solid #c9c0ce; border-radius: 50%; overflow: hidden; background: #e8e1ed; color: #281378;
        font: 3rem Georgia, serif; }
      .avatar-preview img { display: block; width: 100%; height: 100%; object-fit: cover; }
      .file-label, label:not(.file-label) { display: block; margin-bottom: .4rem; font-weight: 650; }
      input[type=file] { max-width: 100%; }
      input:not([type=file]), textarea { width: 100%; min-height: 2.75rem; padding: .65rem .75rem; margin-bottom: 1rem;
        border: 1px solid #9788aa; border-radius: .3rem; background: #fffdf8; color: #342e44; font: inherit; }
      textarea { resize: vertical; }
      input:focus-visible, textarea:focus-visible, button:focus-visible, a:focus-visible { outline: 3px solid #6c55b8; outline-offset: 3px; }
      button { min-height: 2.75rem; padding: .65rem 1.25rem; border: 1px solid #281378; border-radius: .25rem;
        background: #281378; color: white; font: inherit; font-weight: 650; cursor: pointer; }
      button:hover:not(:disabled) { background: #442a9c; }
      button:disabled { opacity: .55; cursor: not-allowed; }
      .hint, .email { color: #625c70; font-size: .9rem; }
      .hint { margin: .25rem 0 1.3rem; }
      .email { margin: -.6rem 0 1.5rem; }
      .error { color: #9b1b30; }
      .success { color: #236149; }
      @media (max-width: 45rem) { .profile-grid { grid-template-columns: 1fr; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProfilePageComponent implements OnInit {
    private readonly api = inject(AccountProfileApi);
    private readonly auth = inject(AuthService);
    private readonly fb = inject(FormBuilder);
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
        bio: ['', Validators.maxLength(200)]
    });
    readonly passwordForm = this.fb.nonNullable.group({
        currentPassword: ['', Validators.required],
        newPassword: ['', [Validators.required, Validators.maxLength(128)]]
    });

    ngOnInit(): void { void this.load(); }

    async load(): Promise<void> {
        this.loading.set(true);
        this.loadError.set(null);
        try {
            const profile = await firstValueFrom(this.api.load());
            this.profile.set(profile);
            this.form.setValue({ profileUsername: profile.profileUsername ?? '', displayName: profile.displayName ?? '',
                bio: profile.bio ?? '' });
            this.avatarUrl.set(profile.avatarPresent ? this.api.avatarUrl(profile.accountId, Date.now()) : null);
        } catch {
            this.loadError.set('Проверьте соединение и попробуйте снова.');
        } finally { this.loading.set(false); }
    }

    async save(): Promise<void> {
        if (this.form.invalid || this.saving()) return;
        this.saving.set(true);
        this.saveError.set(null);
        this.saveSuccess.set(false);
        try {
            const profile = await firstValueFrom(this.api.update(this.form.getRawValue()));
            this.profile.set(profile);
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
        if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || file.size > 10 * 1024 * 1024 || !file.size) {
            this.avatarError.set('Выберите PNG, JPEG или WebP размером до 10 МБ.');
            return;
        }
        this.avatarBusy.set(true);
        try {
            await firstValueFrom(this.api.uploadAvatar(file));
            const current = this.profile();
            if (current) {
                this.profile.set({ ...current, avatarPresent: true });
                this.avatarUrl.set(this.api.avatarUrl(current.accountId, Date.now()));
            }
        } catch { this.avatarError.set('Не удалось сохранить аватар. Проверьте формат и размеры изображения.'); }
        finally { this.avatarBusy.set(false); }
    }

    async changePassword(): Promise<void> {
        if (this.passwordForm.invalid || this.passwordBusy()) return;
        this.passwordBusy.set(true);
        this.passwordError.set(null);
        try {
            const { currentPassword, newPassword } = this.passwordForm.getRawValue();
            await this.auth.setPassword(currentPassword, newPassword);
            this.passwordForm.reset();
        } catch { this.passwordError.set('Не удалось сменить пароль. Проверьте текущий пароль.'); }
        finally { this.passwordBusy.set(false); }
    }
}
