import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ReadingFrameComponent } from './reading-frame.component';

describe('ReadingFrameComponent', () => {
    it('uses named ordinary links and omits non-existent neighbours', () => {
        TestBed.configureTestingModule({ providers: [provideRouter([])] });
        const fixture = TestBed.createComponent(ReadingFrameComponent);
        fixture.componentRef.setInput('next', ['/decks', 'example', 'materials', 'next']);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('a[rel="prev"]')).toBeNull();
        expect(root.querySelector('a[rel="next"]')?.getAttribute('href')).toBe('/decks/example/materials/next');
        expect(root.querySelector('a[rel="next"]')?.getAttribute('aria-label')).toBe('Следующий материал');
        expect(root.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
        fixture.componentRef.setInput('next', null); fixture.detectChanges();
        expect(root.querySelectorAll('a')).toHaveLength(0);
    });
});
