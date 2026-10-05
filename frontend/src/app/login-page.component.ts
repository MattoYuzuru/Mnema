import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from './auth.service';
import { AuthFailure, FederatedProvider, safeReturnUrl } from './auth-protocol';
import { AbuseProtectionFailure } from './turnstile.service';

export function identityErrorMessage(error: unknown): string {
    if (error instanceof AbuseProtectionFailure && error.code === 'blocked')
        return 'Вход и регистрация пока недоступны. Попробуйте позже.';
    if (error instanceof AbuseProtectionFailure) return error.code === 'unavailable'
        ? 'Защита входа сейчас недоступна. Проверьте блокировку скриптов и соединение, затем повторите.'
        : 'Проверка входа не завершилась или устарела. Повторите попытку — начнётся новая проверка.';
    if (error instanceof AuthFailure) {
        if (error.code === 'storage') return 'Браузер не разрешает сохранить данные входа. Разрешите хранилище для этого сайта и повторите.';
        if (error.code === 'configuration') return 'Вход пока недоступен: требуется настроенное защищённое соединение с сервисом аккаунтов.';
        if (error.code === 'recovery_required') return 'Аккаунт ожидает удаления. Обычный вход недоступен; требуется восстановление аккаунта.';
        return 'Не удалось подтвердить вход. Начните его заново.';
    }
    if (error instanceof HttpErrorResponse) {
        if (error.error?.code === 'abuse_protection_unavailable') return 'Защита входа сейчас недоступна. Попробуйте позже.';
        if (error.error?.code === 'abuse_verification_failed') return 'Не удалось подтвердить проверку входа. Повторите попытку.';
        if (error.status === 400 || error.status === 401) return 'Проверьте логин и пароль. Войти не удалось.';
        if (error.status === 409) return 'Не удалось зарегистрироваться с этими данными. Проверьте их или войдите в существующий аккаунт.';
        if (error.status === 429) return 'Слишком много попыток. Немного подождите и повторите.';
        if (error.status === 403) return 'Не удалось подтвердить запрос. Обновите страницу и повторите вход.';
    }
    return 'Сервис аккаунтов не отвечает. Проверьте соединение и повторите.';
}

@Component({
    selector: 'app-login-page',
    imports: [FormsModule, RouterLink],
    changeDetection: ChangeDetectionStrategy.OnPush,
    styleUrl: './identity-page.css',
    template: `
      <section class="identity-sheet" aria-labelledby="identity-heading" [attr.aria-busy]="busy()">
        <p class="eyebrow">Mnema · свои знания</p>
        <h1 id="identity-heading">{{ registering ? 'Место для любопытства' : 'Вернуться к своим материалам' }}</h1>
        @if (auth.status() === 'authenticated') {
          <p data-testid="identity-profile">Вы вошли как {{ auth.user()?.profileUsername || auth.user()?.email }}.</p>
          <a class="button primary" routerLink="/decks">Мои колоды →</a>
          <button class="text-action" type="button" data-testid="logout" [disabled]="busy()" (click)="logout()">Выйти из аккаунта</button>
        } @else {
          <p class="intro">{{ registering ? 'Создайте аккаунт через удобный сервис или с почтой, чтобы сохранять свои материалы.' : 'Выберите удобный способ входа.' }}</p>
          <div class="provider-options" role="group" aria-label="Вход через сервисы">
            @for (provider of providers; track provider.name) {
              <button class="provider-option" type="button" [disabled]="busy() || !available().includes(provider.id)"
                [attr.aria-label]="'Войти через ' + provider.name" (click)="loginWithProvider(provider.id)">
                <img [src]="provider.icon" width="32" height="32" alt="" />
                <strong>{{ provider.name }}</strong><span>{{ providerStatus() === 'loading' ? 'Проверяем…' : (available().includes(provider.id) ? 'Продолжить' : 'Недоступен') }}</span>
              </button>
            }
          </div>
          @if (providerStatus() === 'error') {
            <p class="hint" role="status">Не удалось проверить вход через сервисы.
              <button class="text-action" type="button" [disabled]="busy()" (click)="loadProviders()">Повторить</button>
            </p>
          }
          <p class="password-divider">{{ registering ? 'Или зарегистрируйтесь с почтой' : 'Или войдите с логином или почтой' }}</p>
          <form #form="ngForm" (ngSubmit)="submit(form)" novalidate>
            @if (registering) {
              <label for="email">Электронная почта</label>
              <input id="email" name="email" type="email" autocomplete="email" maxlength="320" [(ngModel)]="email" required email [disabled]="busy()" />
              <label for="username">Логин</label>
              <input id="username" name="username" autocomplete="username" minlength="3" maxlength="50" pattern="[A-Za-z0-9_.-]{3,50}" [(ngModel)]="username" required aria-describedby="username-help" [disabled]="busy()" />
              <p class="hint" id="username-help">От 3 до 50 латинских букв, цифр, точек, дефисов или подчёркиваний. Это также ваше начальное имя в профиле.</p>
            } @else {
              <label for="login-name">Логин или почта</label>
              <input id="login-name" name="login" autocomplete="username" maxlength="320" [(ngModel)]="login" required [disabled]="busy()" />
            }
            <label for="password">Пароль</label>
            <input id="password" name="password" type="password" [attr.autocomplete]="registering ? 'new-password' : 'current-password'" [minlength]="registering ? 12 : 1" maxlength="128" [(ngModel)]="password" required [attr.aria-describedby]="registering ? 'password-help' : null" [disabled]="busy()" />
            @if (registering) {
              <p class="hint" id="password-help">Не менее 12 символов. Если пароль окажется слишком длинным, попробуйте сократить его.</p>
            }
            <button class="button primary" type="submit" [disabled]="busy()">{{ busy() ? 'Подтверждаем…' : (registering ? 'Создать аккаунт →' : 'Войти →') }}</button>
          </form>
          <p class="switch-mode">{{ registering ? 'Уже есть аккаунт?' : 'Впервые здесь?' }}
            <a [routerLink]="registering ? '/login' : '/register'" [queryParams]="{ returnUrl }">{{ registering ? 'Войти' : 'Создать аккаунт' }}</a>
          </p>
          <p class="hint">Защита входа может использовать Cloudflare Turnstile.
            <a routerLink="/privacy">Обработка данных</a>.</p>
        }
        @if (error()) { <p class="identity-error" role="alert" tabindex="-1">{{ error() }}</p> }
        @if (auth.logoutUnconfirmed()) {
          <p class="identity-error" role="status">На этом устройстве вы вышли. Повторите выход, чтобы завершить сессию полностью.</p>
          <button class="text-action" type="button" [disabled]="busy()" (click)="logout()">Повторить выход</button>
        }
        <p class="identity-status" role="status">{{ busy() ? 'Проверяем данные…' : '' }}</p>
      </section>
    `
})
export class LoginPageComponent implements OnInit, OnDestroy {
    readonly providers = [
        { id: 'google', name: 'Google', icon: '/assets/brand/providers/google.svg' },
        { id: 'yandex', name: 'Яндекс', icon: '/assets/brand/providers/yandex.svg' },
        { id: 'github', name: 'GitHub', icon: '/assets/brand/providers/github.png' }
    ] as const;
    readonly auth = inject(AuthService);
    private readonly route = inject(ActivatedRoute);
    private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
    readonly registering = this.route.snapshot.routeConfig?.path === 'register';
    readonly returnUrl = safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
    readonly busy = signal(false);
    readonly error = signal('');
    readonly available = signal<FederatedProvider[]>([]);
    readonly providerStatus = signal<'loading' | 'ready' | 'error'>('loading');
    email = '';
    username = '';
    login = '';
    password = '';
    private destroyed = false;

    ngOnInit(): void { void this.loadProviders(); }

    async loadProviders(): Promise<void> {
        this.providerStatus.set('loading');
        try {
            const providers = await this.auth.availableProviders();
            if (!this.destroyed) { this.available.set(providers); this.providerStatus.set('ready'); }
        } catch {
            if (!this.destroyed) { this.available.set([]); this.providerStatus.set('error'); }
        }
    }

    async loginWithProvider(provider: FederatedProvider): Promise<void> {
        if (this.busy() || !this.available().includes(provider)) return;
        this.busy.set(true);
        this.error.set('');
        this.password = '';
        try { await this.auth.beginFederatedLogin(provider, this.returnUrl); }
        catch (error) {
            if (!this.destroyed) { this.error.set(identityErrorMessage(error)); this.busy.set(false); }
        }
    }

    async submit(form: NgForm): Promise<void> {
        if (this.busy()) return;
        this.error.set('');
        form.control.markAllAsTouched();
        if (form.invalid || (this.registering && new TextEncoder().encode(this.password).byteLength > 72)) {
            this.error.set('Проверьте заполнение полей и требования к паролю.');
            // Form control state is authoritative; CSS status classes may await the next render.
            const invalid = Array.from(this.element.nativeElement.querySelectorAll<HTMLInputElement>('input'))
                .find(input => form.controls[input.name]?.invalid) ?? this.element.nativeElement.querySelector<HTMLInputElement>('#password');
            invalid?.focus();
            return;
        }
        this.busy.set(true);
        try {
            if (this.registering) await this.auth.registerWithPassword(this.email, this.username, this.password, this.returnUrl);
            else await this.auth.loginWithPassword(this.login, this.password, this.returnUrl);
        } catch (error) {
            if (!this.destroyed) this.error.set(identityErrorMessage(error));
        } finally {
            this.password = '';
            if (!this.destroyed) this.busy.set(false);
        }
    }

    async logout(): Promise<void> {
        if (this.busy()) return;
        this.busy.set(true);
        this.error.set('');
        try { await this.auth.logout(); }
        catch { if (!this.destroyed) this.error.set('На этом устройстве вы вышли. Повторите выход, когда связь восстановится.'); }
        finally { if (!this.destroyed) this.busy.set(false); }
    }

    ngOnDestroy(): void { this.destroyed = true; this.password = ''; }
}
