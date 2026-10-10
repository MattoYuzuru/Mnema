import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, firstValueFrom } from 'rxjs';
import { AdminApiService, AdminSession } from './admin-api.service';
@Component({
    selector: 'app-admin-shell',
    imports: [RouterLink, RouterLinkActive, RouterOutlet],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
    <div class="admin-shell">
      @if (state() !== 'ready') {
        <header><p class="eyebrow">Кабинет владельца</p><h1 tabindex="-1">Управление Мнемой</h1></header>
        @switch (state()) {
          @case ('loading') { <p role="status">Проверяем доступ…</p> }
          @case ('forbidden') { <div class="notice warning"><h2>Доступ закрыт</h2><p>Кабинет доступен только владельцу Мнемы.</p><a class="button" routerLink="/profile">Открыть профиль</a></div> }
          @case ('error') { <div class="notice error" role="alert"><p>Не удалось проверить доступ. Данные кабинета не загружены.</p><button class="button" type="button" (click)="load()">Повторить проверку</button></div> }
        }
      } @else {
        <nav class="admin-nav" aria-label="Разделы кабинета владельца">
          <button class="button quiet admin-nav-toggle" type="button" aria-controls="admin-navigation-links"
            [attr.aria-expanded]="menuOpen()" (click)="menuOpen.set(!menuOpen())">{{ menuOpen() ? 'Закрыть разделы' : 'Разделы кабинета' }}</button>
          <div class="admin-nav-links" id="admin-navigation-links" [class.is-open]="menuOpen()">
            <span class="eyebrow">Управление</span>
            @for (link of links; track link.path) { <a [routerLink]="link.path" routerLinkActive="active" ariaCurrentWhenActive="page" [routerLinkActiveOptions]="{exact: link.path === '/manage'}">{{ link.name }}</a> }
          </div>
        </nav>
        <div class="admin-workspace"><router-outlet (activate)="focusHeading()" /></div>
      }
    </div>`,
    styles: [`
      .admin-shell { inline-size: min(var(--mn-page-width), calc(100% - 2 * var(--mn-page-gutter))); margin: auto; padding-block: var(--mn-space-6) var(--mn-space-8); }
      .admin-shell:has(.admin-nav) { display: grid; grid-template-columns: 11rem minmax(0, 1fr); gap: var(--mn-space-7); }
      .admin-nav { display: flex; flex-direction: column; gap: var(--mn-space-2); border-inline-end: 1px solid var(--mn-rule); padding-inline-end: var(--mn-space-5); }
      .admin-nav-toggle { display: none; }
      .admin-nav-links { display: flex; flex-direction: column; gap: var(--mn-space-2); }
      .admin-nav a { display: flex; align-items: center; min-block-size: var(--mn-touch-min); padding: var(--mn-space-2); color: var(--mn-ink); overflow-wrap: anywhere; }
      .admin-nav a.active { font-weight: 700; background: var(--mn-soft); }
      .admin-workspace { min-inline-size: 0; }
      @media (max-width: 60rem) {
        .admin-shell:has(.admin-nav) { grid-template-columns: minmax(0, 1fr); gap: var(--mn-space-5); }
        .admin-nav { display: block; border-inline-end: 0; border-block-end: 1px solid var(--mn-rule); padding-inline-end: 0; padding-block-end: var(--mn-space-3); }
        .admin-nav-toggle { display: inline-flex; }
        .admin-nav-links { display: none; flex-direction: row; flex-wrap: wrap; margin-block-start: var(--mn-space-3); }
        .admin-nav-links.is-open { display: flex; }
        .admin-nav-links .eyebrow { flex-basis: 100%; }
      }
    `]
})
export class AdminShellComponent implements OnInit {
    private readonly api = inject(AdminApiService);
    private readonly session = inject(AdminSession);
    private readonly destroy = inject(DestroyRef);
    private readonly router = inject(Router);
    protected readonly state = signal<'loading' | 'ready' | 'forbidden' | 'error'>('loading');
    protected readonly menuOpen = signal(false);
    protected readonly links = [{
            path: '/manage',
            name: 'Обзор и расходы'
        }, {
            path: '/manage/users',
            name: 'Пользователи'
        }, {
            path: '/manage/promos',
            name: 'Промокоды'
        }, {
            path: '/manage/events',
            name: 'Новости'
        }, {
            path: '/manage/support',
            name: 'Поддержка'
        }, {
            path: '/manage/audit',
            name: 'Журнал действий'
        }];
    constructor() {
        this.destroy.onDestroy(() => this.session.revoke());
        // A rejected draft transition keeps the focused link and disclosure open.
        this.router.events.pipe(filter(event => event instanceof NavigationEnd), takeUntilDestroyed(this.destroy))
            .subscribe(() => this.menuOpen.set(false));
        effect(() => {
            if (this.state() === 'ready' && this.session.access() === null)
                this.state.set('forbidden');
        });
    }

    ngOnInit(): void {
        void this.load();
    }

    protected async load(): Promise<void> {
        this.state.set('loading');
        this.session.access.set(null);
        try {
            this.session.access.set(await firstValueFrom(this.api.access().pipe(takeUntilDestroyed(this.destroy))));
            this.state.set('ready');
        } catch (error) {
            this.state.set(error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403) ? 'forbidden' : 'error');
        }
    }

    protected focusHeading(): void {
        queueMicrotask(() => document.querySelector<HTMLElement>('.admin-workspace h1')?.focus());
    }
}
