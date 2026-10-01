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

    it('keeps the landing actions on implemented routes and the feature links on real sections', () => {
        const root = fixture.nativeElement as HTMLElement;
        const routes = Array.from(root.querySelectorAll<HTMLAnchorElement>('a[href^="/decks"]'))
            .map(link => link.getAttribute('href'));
        const sections = Array.from(root.querySelectorAll<HTMLAnchorElement>('.feature-strip a'))
            .map(link => link.hash);

        expect(routes).toContain('/decks');
        expect(routes).toContain('/decks/new');
        expect(sections).toEqual(['#materials', '#exercises', '#rhythm']);
        expect(root.querySelectorAll('h1').length).toBe(1);
        expect(root.querySelector('picture img')?.getAttribute('width')).toBe('1024');
        expect(root.textContent).not.toContain('Прогресс');
        expect(root.querySelectorAll('[role="progressbar"]').length).toBe(0);
        expect(root.textContent).toContain('Повторяйте с интервалами');
        expect(root.textContent).toContain('изображения, произношение, диаграммы');
        expect(root.textContent).toContain('больше шаблонов упражнений');
    });

    it('offers login instead of a protected deck route to an anonymous visitor', () => {
        fixture.destroy();
        auth.status.and.returnValue('anonymous');
        fixture = TestBed.createComponent(HomePageComponent);
        fixture.detectChanges();

        const root = fixture.nativeElement as HTMLElement;
        const primaryAction = root.querySelector<HTMLAnchorElement>('.hero-actions .primary');
        expect(primaryAction?.getAttribute('href')).toBe('/login');
        expect(root.querySelectorAll('a[href^="/decks"]').length).toBe(0);
    });
});
