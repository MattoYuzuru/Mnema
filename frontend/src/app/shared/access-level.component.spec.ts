import { TestBed } from '@angular/core/testing';

import { DECK_VISIBILITIES } from '../features/own-decks/own-deck.models';
import { ACCESS_LEVEL_LABELS, AccessLevelComponent } from './access-level.component';

describe('AccessLevelComponent', () => {
    it('names every level in words and hides the glyph from assistive technology', () => {
        const words: string[] = [];
        for (const level of DECK_VISIBILITIES) {
            const fixture = TestBed.createComponent(AccessLevelComponent);
            fixture.componentRef.setInput('level', level);
            fixture.detectChanges();
            const root = fixture.nativeElement as HTMLElement;
            expect(root.querySelector('.label')!.textContent).toBe(ACCESS_LEVEL_LABELS[level]);
            expect(root.querySelector('svg')!.getAttribute('aria-hidden')).toBe('true');
            words.push(root.textContent!.trim());
        }
        expect(words).toEqual(['Приватная', 'По приглашению', 'По ссылке', 'Публичная']);
    });

    it('draws a lock, a chain and a colonnade', () => {
        const drawing = (level: 'private' | 'link' | 'public'): Element => {
            const fixture = TestBed.createComponent(AccessLevelComponent);
            fixture.componentRef.setInput('level', level);
            fixture.detectChanges();
            return (fixture.nativeElement as HTMLElement).querySelector('svg')!;
        };
        expect(drawing('private').querySelector('rect[x="5"][y="10.5"]')).not.toBeNull();
        expect(drawing('link').querySelector('g[transform^="rotate(-45"]')).not.toBeNull();
        expect(drawing('public').querySelector('path[d^="M3.5 9 12 4"]')).not.toBeNull();
    });
});
