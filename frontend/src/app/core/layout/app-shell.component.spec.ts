import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';

import { AuthService } from '../../auth.service';
import { AppShellComponent } from './app-shell.component';

@Component({ template: '<h1 tabindex="-1">Мои колоды</h1>' })
class TestPageComponent {}

describe('AppShellComponent', () => {
    let fixture: ComponentFixture<AppShellComponent>;
    let auth: jasmine.SpyObj<AuthService>;

    beforeEach(async () => {
        auth = jasmine.createSpyObj<AuthService>('AuthService', ['status', 'user', 'logout']);
        auth.status.and.returnValue('authenticated');
        auth.user.and.returnValue({ email: 'reader@example.test', name: 'Читатель' });
        Object.defineProperty(auth, 'status$', { value: of('authenticated') });
        Object.defineProperty(auth, 'user$', { value: of({ email: 'reader@example.test', name: 'Читатель' }) });
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

    it('delegates logout without claiming a completed server session', () => {
        (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.quiet-action')?.click();
        expect(auth.logout).toHaveBeenCalledTimes(1);
    });

    it('opens login recovery when server logout is not confirmed', async () => {
        auth.logout.and.throwError('server unavailable');

        await fixture.componentInstance.logout();

        expect(TestBed.inject(Router).url).toBe('/login');
    });
});
