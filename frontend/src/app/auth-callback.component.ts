import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from './auth.service';
import { identityErrorMessage } from './login-page.component';

@Component({
    selector: 'app-auth-callback',
    imports: [RouterLink],
    changeDetection: ChangeDetectionStrategy.OnPush,
    styleUrl: './identity-page.css',
    template: `
      <section class="identity-sheet" aria-labelledby="callback-heading">
        <p class="eyebrow">Mnema · вход</p><h1 id="callback-heading">{{ error() ? 'Вход не подтверждён' : 'Возвращаемся к вашим материалам' }}</h1>
        @if (error()) { <p class="identity-error" role="alert">{{ error() }}</p><a class="primary-action" routerLink="/login">Начать вход заново →</a> }
        @else { <p role="status">Проверяем ответ сервиса аккаунтов…</p> }
      </section>
    `
})
export class AuthCallbackComponent implements OnInit {
    private readonly auth = inject(AuthService);
    readonly error = signal('');
    ngOnInit(): void { void this.auth.completeCallback().catch(error => this.error.set(identityErrorMessage(error))); }
}
