import { TestBed } from '@angular/core/testing';

import { GLYPH_NAMES, GlyphComponent, GlyphName } from './glyph.component';

describe('GlyphComponent', () => {
    function render(name: GlyphName): HTMLElement {
        const fixture = TestBed.createComponent(GlyphComponent);
        fixture.componentRef.setInput('name', name);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it.each(GLYPH_NAMES)('draws «%s» as a hidden, unfocusable svg with shapes', name => {
        const host = render(name);
        const svg = host.querySelector('svg')!;
        expect(host.getAttribute('aria-hidden')).toBe('true');
        expect(host.dataset['glyph']).toBe(name);
        expect(svg.getAttribute('aria-hidden')).toBe('true');
        expect(svg.getAttribute('focusable')).toBe('false');
        expect(svg.getAttribute('viewBox')).toBe('0 0 24 24');
        expect(svg.querySelectorAll('path, circle, rect').length).toBeGreaterThan(0);
        expect(svg.innerHTML).not.toMatch(/#[0-9a-f]{3,6}\b/iu);
    });

    it('has a different drawing for every name', () => {
        const drawings = GLYPH_NAMES.map(name => render(name).querySelector('svg')!.innerHTML);
        expect(new Set(drawings).size).toBe(GLYPH_NAMES.length);
    });
});
