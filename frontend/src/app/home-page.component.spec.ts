import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AuthService } from './auth.service';
import { HomePageComponent } from './home-page.component';

describe('HomePageComponent', () => {
    let fixture: ComponentFixture<HomePageComponent>;
    let auth: jasmine.SpyObj<AuthService>;

    beforeEach(async () => {
        auth = jasmine.createSpyObj<AuthService>('AuthService', ['status', 'user']);
        auth.status.and.returnValue('authenticated');
        auth.user.and.returnValue(null);
        Object.defineProperty(auth, 'status$', { value: of('authenticated') });
        Object.defineProperty(auth, 'user$', { value: of(null) });
        await TestBed.configureTestingModule({
            imports: [HomePageComponent],
            providers: [provideRouter([]), { provide: AuthService, useValue: auth }]
        }).compileComponents();
        fixture = TestBed.createComponent(HomePageComponent);
        fixture.detectChanges();
    });

    it('offers only implemented own-deck actions without fake study or catalog state', () => {
        const root = fixture.nativeElement as HTMLElement;
        const links = Array.from(root.querySelectorAll<HTMLAnchorElement>('a')).map(link => link.getAttribute('href'));

        expect(links).toEqual(['/decks', '/decks/new']);
        expect(root.textContent).not.toContain('Прогресс');
        expect(root.textContent).not.toContain('Начать обучение');
        expect(root.querySelectorAll('[role="progressbar"]').length).toBe(0);
    });
});
