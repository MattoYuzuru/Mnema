import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';

import { AuthService, AuthStatus } from '../../auth.service';
import { AppShellComponent } from './app-shell.component';

@Component({ template: '<h1 tabindex="-1">Мои колоды</h1>' })
class TestPageComponent {}

describe('AppShellComponent', () => {
    let fixture: ComponentFixture<AppShellComponent>;
    let auth: jasmine.SpyObj<AuthService>;
    let status: BehaviorSubject<AuthStatus>;

    beforeEach(async () => {
        auth = jasmine.createSpyObj<AuthService>('AuthService', ['status', 'user', 'logout']);
        status = new BehaviorSubject<AuthStatus>('authenticated');
        auth.status.and.returnValue('authenticated');
        const user = {
            accountId: '11111111-1111-4111-8111-111111111111',
            email: 'reader@example.test',
            emailVerified: true,
            profileUsername: 'reader',
            displayName: 'Читатель',
            hasPassword: true,
            name: 'Читатель'
        };
        auth.user.and.returnValue(user);
        auth.logout.and.resolveTo();
        Object.defineProperty(auth, 'status$', { value: status.asObservable() });
        Object.defineProperty(auth, 'user$', { value: of(user) });
        await TestBed.configureTestingModule({
            imports: [AppShellComponent],
            providers: [
                provideRouter([
                    { path: '', component: TestPageComponent },
                    { path: 'login', component: TestPageComponent }
                ]),
                { provide: AuthService, useValue: auth }
            ]
        }).compileComponents();
        fixture = TestBed.createComponent(AppShellComponent);
        await TestBed.inject(Router).navigateByUrl('/');
        fixture.detectChanges();
        await fixture.whenStable();
    });

    it('renders one main landmark, a skip link, and only real deck destinations', () => {
        const root = fixture.nativeElement as HTMLElement;

        expect(root.querySelectorAll('main').length).toBe(1);
        expect(root.querySelector<HTMLAnchorElement>('.skip-link')?.getAttribute('href')).toBe('#main-content');
        expect(Array.from(root.querySelectorAll('.primary-nav a')).map(link => link.getAttribute('href')))
            .toEqual(['/decks', '/decks/new']);
        expect(root.textContent).not.toContain('Начать обучение');
    });

    it('moves focus to the activated page heading without initializing auth', async () => {
        fixture.componentInstance.focusPageHeading();
        await Promise.resolve();

        expect(document.activeElement?.textContent).toBe('Мои колоды');
        expect(auth.logout).not.toHaveBeenCalled();
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
        (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.quiet-action')?.click();
        await fixture.whenStable();
        expect(auth.logout).toHaveBeenCalledTimes(1);
    });

    it('opens login recovery when server logout is not confirmed', async () => {
        auth.logout.and.rejectWith(new Error('server unavailable'));

        await fixture.componentInstance.logout();

        expect(TestBed.inject(Router).url).toBe('/login');
    });
});
