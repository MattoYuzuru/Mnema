import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { PaperLandingComponent } from './paper-landing.component';

describe('PaperLandingComponent', () => {
    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [PaperLandingComponent],
            providers: [provideRouter([])]
        }).compileComponents();
    });

    it('presents one heading and only the own-material authoring journey', () => {
        const fixture = TestBed.createComponent(PaperLandingComponent);
        fixture.detectChanges();

        const element = fixture.nativeElement as HTMLElement;
        const copy = element.textContent ?? '';

        expect(element.querySelectorAll('h1').length).toBe(1);
        expect(copy).toContain('На потом');
        expect(copy).toContain('цельный документ');
        expect(copy).not.toContain('Каталог');
        expect(copy).not.toContain('AI');
        expect(element.querySelectorAll('button').length).toBe(0);
    });

    it('routes the primary action from the supplied session state', () => {
        const fixture = TestBed.createComponent(PaperLandingComponent);
        fixture.componentRef.setInput('authenticated', true);
        fixture.detectChanges();

        const action = fixture.nativeElement.querySelector('.paper-primary-action') as HTMLAnchorElement;
        expect(action.textContent).toContain('Открыть мои колоды');
        expect(action.getAttribute('href')).toBe('/decks');
    });

    it('keeps the optional engraving decorative', () => {
        const fixture = TestBed.createComponent(PaperLandingComponent);
        fixture.componentRef.setInput(
            'engravingSrc',
            'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=='
        );
        fixture.detectChanges();

        const image = fixture.nativeElement.querySelector('img') as HTMLImageElement;
        expect(image.getAttribute('alt')).toBe('');
        expect(image.getAttribute('width')).toBe('1024');
        expect(image.getAttribute('height')).toBe('1536');
    });
});
