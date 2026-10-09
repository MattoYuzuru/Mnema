import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { BehaviorSubject, NEVER, of } from 'rxjs';

import { AuthService, AuthStatus } from '../../auth.service';
import { NotificationsApiService } from '../notifications/notifications-api.service';
import { NotificationCenter } from '../notifications/notification-center';
import { LearningProfileApiService } from '../../features/goal/learning-profile-api.service';
import { AppShellComponent } from './app-shell.component';
import { spyObj, type SpyObj } from '../../../testing/mocks';

@Component({ template: '<h1 tabindex="-1">Мои колоды</h1>' })
class TestPageComponent {
}

describe('AppShellComponent', () => {
    let fixture: ComponentFixture<AppShellComponent>;
    let auth: SpyObj<AuthService>;
    let status: BehaviorSubject<AuthStatus>;

    beforeEach(async () => {
        auth = spyObj<AuthService>({
            status: vi.fn().mockName("AuthService.status"),
            user: vi.fn().mockName("AuthService.user"),
            logout: vi.fn().mockName("AuthService.logout")
        });
        status = new BehaviorSubject<AuthStatus>('authenticated');
        auth.status.mockReturnValue('authenticated');
        const user = {
            accountId: '11111111-1111-4111-8111-111111111111',
            email: 'reader@example.test',
            emailVerified: true,
            profileUsername: 'reader',
            displayName: 'Читатель',
            hasPassword: true,
            name: 'Читатель'
        };
        auth.user.mockReturnValue(user);
        auth.logout.mockResolvedValue();
        Object.defineProperty(auth, 'status$', { value: status.asObservable() });
        Object.defineProperty(auth, 'user$', { value: of(user) });
        await TestBed.configureTestingModule({
            imports: [AppShellComponent],
            providers: [
                provideRouter([
                    { path: '', component: TestPageComponent },
                    { path: 'login', component: TestPageComponent },
                    { path: 'manage', component: TestPageComponent }
                ]),
                { provide: AuthService, useValue: auth },
                // The center polls for a signed-in account; this spec is about the shell, not the wire.
                { provide: NotificationsApiService, useValue: { list: () => NEVER } },
                // The goal question loads for a signed-in account; this spec is about the shell.
                { provide: LearningProfileApiService, useValue: { load: () => NEVER } }
            ]
        }).compileComponents();
        fixture = TestBed.createComponent(AppShellComponent);
        await TestBed.inject(Router).navigateByUrl('/');
        fixture.detectChanges();
        await fixture.whenStable();
    });

    it('renders one main landmark, a skip link, and current deck/account destinations', () => {
        const root = fixture.nativeElement as HTMLElement;

        expect(root.querySelectorAll('main').length).toBe(1);
        expect(root.querySelector<HTMLAnchorElement>('.skip-link')?.getAttribute('href')).toBe('#main-content');
        expect(Array.from(root.querySelectorAll('.primary-nav a')).map(link => link.getAttribute('href')))
            .toEqual(['/decks', '/decks/new', '/profile']);
        expect(root.textContent).not.toContain('Начать обучение');
    });

    it('links «Как Mnema использует ИИ» from the footer, signed in or not', () => {
        const root = fixture.nativeElement as HTMLElement;
        const link = Array.from(root.querySelectorAll<HTMLAnchorElement>('footer nav a')).find(anchor => anchor.getAttribute('href') === '/ai');
        expect(link?.textContent).toBe('Как Mnema использует ИИ');
        status.next('anonymous');
        fixture.detectChanges();
        expect(root.querySelector('footer nav a[href="/ai"]')).not.toBeNull();
    });

    it('puts the bell in the session area and the toast landmark after main, for a signed-in account only', () => {
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('.session app-notification-bell button[aria-controls]')).not.toBeNull();
        const region = root.querySelector('section.toast-region[aria-label="Уведомления"]')!;
        expect(region.compareDocumentPosition(root.querySelector('main')!) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy();

        status.next('anonymous');
        fixture.detectChanges();
        expect(root.querySelector('app-notification-bell')).toBeNull();
    });

    it('hosts the promo popup outside main, so it can only appear when a page asks at a natural breakpoint', () => {
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('app-promo-popup-host')).not.toBeNull();
        expect(root.querySelector('main app-promo-popup-host')).toBeNull();
        expect(root.querySelector('dialog')).toBeNull();
    });

    it('moves focus to the activated page heading without initializing auth', async () => {
        fixture.componentInstance.focusPageHeading();
        await Promise.resolve();

        expect(document.activeElement?.textContent).toBe('Мои колоды');
        expect(auth.logout).not.toHaveBeenCalled();
    });

    it('suppresses learner prompts, public footer and notification polling in the owner console', async () => {
        await TestBed.inject(Router).navigateByUrl('/manage'); fixture.detectChanges(); await fixture.whenStable();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('app-goal-onboarding')).toBeNull();
        expect(root.querySelector('app-promo-popup-host')).toBeNull();
        expect(root.querySelector('app-notification-bell')).toBeNull();
        expect(root.querySelector('app-public-footer')).toBeNull();
        expect(root.querySelector('.primary-nav a')?.textContent).toBe('Открыть Мнему');
        expect(root.querySelectorAll('main')).toHaveLength(1);
        const center = TestBed.inject(NotificationCenter);
        expect(center.suspended()).toBe(true);
        await TestBed.inject(Router).navigateByUrl('/'); fixture.detectChanges(); await fixture.whenStable();
        expect(center.suspended()).toBe(false);
    });

    it('suspends synchronously on an initial browser owner route before the router completes its first navigation', () => {
        fixture.destroy();
        const router = TestBed.inject(Router), wasNavigated = router.navigated;
        const originalUrl = window.location.pathname + window.location.search;
        router.navigated = false;
        window.history.replaceState(null, '', '/manage/users');
        try {
            fixture = TestBed.createComponent(AppShellComponent);
            expect(fixture.componentInstance.adminMode()).toBe(true);
            expect(TestBed.inject(NotificationCenter).suspended()).toBe(true);
        } finally {
            router.navigated = wasNavigated;
            window.history.replaceState(null, '', originalUrl);
        }
    });

    it('starts a button fill at the pointer entry position', () => {
        const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.button.mono')!;
        const bounds = button.getBoundingClientRect();

        button.dispatchEvent(new PointerEvent('pointerover', {
            bubbles: true, clientX: bounds.left + 4, clientY: bounds.top + 6
        }));

        expect(button.style.getPropertyValue('--mn-wave-x')).toBe('4px');
        expect(button.style.getPropertyValue('--mn-wave-y')).toBe('6px');
    });

    it('starts a deck or material row fill at the entry point of a nested label', () => {
        const main = (fixture.nativeElement as HTMLElement).querySelector('main')!;
        const row = document.createElement('a');
        row.className = 'item-row';
        const label = document.createElement('strong');
        row.append(label);
        main.append(row);
        const bounds = row.getBoundingClientRect();
        label.dispatchEvent(new PointerEvent('pointerover', {
            bubbles: true, clientX: bounds.left + 12, clientY: bounds.top + 8
        }));

        expect(row.style.getPropertyValue('--mn-wave-x')).toBe('12px');
        expect(row.style.getPropertyValue('--mn-wave-y')).toBe('8px');
    });

    it('keeps the current route and focuses main or the footer when a skip link is activated', () => {
        const [main, footer] = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('.skip-link'));
        const event = new MouseEvent('click', { bubbles: true, cancelable: true });

        expect(main.dispatchEvent(event)).toBe(false);
        expect(event.defaultPrevented).toBe(true);
        expect(document.activeElement?.id).toBe('main-content');
        footer.click();
        expect(document.activeElement?.id).toBe('site-footer');
        expect(TestBed.inject(Router).url).toBe('/');
    });

    it('shows a non-actionable pending state before offering anonymous login', () => {
        status.next('pending');
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Проверяем вход…');
        expect((fixture.nativeElement as HTMLElement).querySelector('.session a')).toBeNull();

        status.next('anonymous');
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('.session a')?.getAttribute('href'))
            .toBe('/login');
    });

    it('delegates logout without claiming a completed server session', async () => {
        (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.button.mono')?.click();
        await fixture.whenStable();
        expect(auth.logout).toHaveBeenCalledTimes(1);
    });

    it('opens login recovery when server logout is not confirmed', async () => {
        auth.logout.mockRejectedValue(new Error('server unavailable'));

        await fixture.componentInstance.logout();

        expect(TestBed.inject(Router).url).toBe('/login');
    });
});
