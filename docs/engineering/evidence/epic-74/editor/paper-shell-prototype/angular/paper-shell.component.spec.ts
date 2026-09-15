import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { PaperShellComponent } from './paper-shell.component';

describe('PaperShellComponent', () => {
    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [PaperShellComponent],
            providers: [provideRouter([])]
        }).compileComponents();
    });

    it('provides semantic navigation and a skip target', () => {
        const fixture = TestBed.createComponent(PaperShellComponent);
        fixture.detectChanges();

        const element = fixture.nativeElement as HTMLElement;
        const skipLink = element.querySelector<HTMLAnchorElement>('.paper-skip-link');
        const main = element.querySelector<HTMLElement>('main');

        expect(skipLink?.getAttribute('href')).toBe('#paper-main');
        expect(main?.id).toBe('paper-main');
        expect(element.querySelector('nav')?.getAttribute('aria-label')).toBe('Основная навигация');
        expect(element.querySelectorAll('h1').length).toBe(0);
    });

    it('renders viewer identity without owning authentication state', () => {
        const fixture = TestBed.createComponent(PaperShellComponent);
        fixture.componentRef.setInput('viewer', { authenticated: true, displayName: '  Марина  ' });
        fixture.detectChanges();

        const accountLink = fixture.nativeElement.querySelector('.paper-account-link') as HTMLAnchorElement;
        expect(accountLink.textContent).toContain('Марина');
        expect(accountLink.getAttribute('href')).toBe('/decks');
    });
});
