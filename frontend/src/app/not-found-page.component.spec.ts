import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotFoundPageComponent } from './not-found-page.component';

describe('unknown page recovery', () => {
    it('offers a real homepage link and a focusable heading', () => {
        TestBed.configureTestingModule({ providers: [provideRouter([])] });
        const fixture = TestBed.createComponent(NotFoundPageComponent);
        fixture.detectChanges();
        const root: HTMLElement = fixture.nativeElement;
        expect(root.querySelector('h1')?.textContent).toBe('Страница не найдена');
        expect(root.querySelector('h1')?.getAttribute('tabindex')).toBe('-1');
        expect(root.querySelector('a')?.getAttribute('href')).toBe('/');
    });
});
