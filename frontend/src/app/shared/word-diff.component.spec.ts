import { TestBed } from '@angular/core/testing';

import { diffLines } from '../content/word-diff';
import { WordDiffComponent } from './word-diff.component';

describe('WordDiffComponent', () => {
    const paragraphs = diffLines(['Это старый текст урока.'], ['Это новый текст урока.']);

    function render(side?: 'mine' | 'author' | 'both'): HTMLElement {
        const fixture = TestBed.createComponent(WordDiffComponent);
        fixture.componentRef.setInput('paragraphs', paragraphs);
        if (side !== undefined) fixture.componentRef.setInput('side', side);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('draws removed words as del and inserted ones as ins, each announced by a hidden prefix', () => {
        const host = render();
        const del = host.querySelector('del')!;
        const ins = host.querySelector('ins')!;
        expect(del.textContent).toBe('удалено: старый ');
        expect(ins.textContent).toBe('добавлено: новый ');
        expect(del.querySelector('.visually-hidden')?.textContent).toBe('удалено: ');
        expect(ins.querySelector('.visually-hidden')?.textContent).toBe('добавлено: ');
    });

    it('keeps the text between the changes, one p per paragraph', () => {
        const host = render();
        expect(host.querySelectorAll('p.diff-paragraph')).toHaveLength(1);
        expect(host.textContent!.replace(/\s+/gu, ' ').trim()).toBe('Это удалено: старый добавлено: новый текст урока.');
    });

    it('shows only what the reader has now on the «mine» side', () => {
        const host = render('mine');
        expect(host.querySelector('ins')).toBeNull();
        expect(host.querySelector('del')?.textContent).toContain('старый');
        expect(host.textContent).not.toContain('новый');
    });

    it('shows only the author\'s version on the «author» side', () => {
        const host = render('author');
        expect(host.querySelector('del')).toBeNull();
        expect(host.querySelector('ins')?.textContent).toContain('новый');
        expect(host.textContent).not.toContain('старый');
    });

    it('draws no paragraph that has nothing left on the chosen side', () => {
        const fixture = TestBed.createComponent(WordDiffComponent);
        fixture.componentRef.setInput('paragraphs', diffLines(['Первый.'], ['Первый.', 'Новый абзац.']));
        fixture.componentRef.setInput('side', 'mine');
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelectorAll('p')).toHaveLength(1);
    });
});
