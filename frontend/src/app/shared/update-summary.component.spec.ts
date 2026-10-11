import { TestBed } from '@angular/core/testing';

import { UpdateCounts, UpdateSummaryComponent, summaryLine } from './update-summary.component';

const counts = (change: Partial<UpdateCounts> = {}): UpdateCounts => ({ materials: 0, exercises: 0, fixes: 0, conflicts: 0, deleted: 0, ...change });

describe('summaryLine', () => {
    it('writes every part with its plural form', () => {
        expect(summaryLine(counts({ materials: 12, exercises: 30, fixes: 5, conflicts: 2, deleted: 3 })))
            .toBe('+12 материалов, +30 упражнений, 5 исправлений, 2 конфликта, 3 удалено автором');
        expect(summaryLine(counts({ materials: 1, exercises: 1, fixes: 1, conflicts: 1, deleted: 1 })))
            .toBe('+1 материал, +1 упражнение, 1 исправление, 1 конфликт, 1 удалено автором');
        expect(summaryLine(counts({ materials: 2, exercises: 4, fixes: 22, conflicts: 5 })))
            .toBe('+2 материала, +4 упражнения, 22 исправления, 5 конфликтов');
        expect(summaryLine(counts({ materials: 11, exercises: 21, fixes: 111, conflicts: 12 })))
            .toBe('+11 материалов, +21 упражнение, 111 исправлений, 12 конфликтов');
    });

    it('leaves out a part with nothing in it, and says nothing for an empty update', () => {
        expect(summaryLine(counts({ fixes: 3, deleted: 1 }))).toBe('3 исправления, 1 удалено автором');
        expect(summaryLine(counts())).toBe('');
    });

    it('uses no long dash', () => {
        expect(summaryLine(counts({ materials: 5, exercises: 5, fixes: 5, conflicts: 5, deleted: 5 }))).not.toMatch(/[\u2012-\u2015\u2212]/u);
    });
});

describe('UpdateSummaryComponent', () => {
    function create(inputs: Record<string, unknown>) {
        const fixture = TestBed.createComponent(UpdateSummaryComponent);
        for (const [key, value] of Object.entries(inputs)) fixture.componentRef.setInput(key, value);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        return { fixture, root, button: () => root.querySelector<HTMLButtonElement>('button')! };
    }

    it('is a section named by its heading, with the line, the note and the primary action', () => {
        const { root, button } = create({ counts: counts({ materials: 2, fixes: 1 }), note: 'Добавили два урока.' });
        const section = root.querySelector('section')!;
        const heading = root.querySelector('h2')!;
        expect(heading.textContent).toBe('Что изменил автор');
        expect(section.getAttribute('aria-labelledby')).toBe(heading.id);
        expect(root.querySelector('.line')?.textContent).toBe('+2 материала, 1 исправление');
        expect(root.querySelector('.news')?.textContent).toContain('Что нового');
        expect(root.querySelector('.news-text')?.textContent).toBe('Добавили два урока.');
        expect(button().textContent!.trim()).toBe('Принять всё безопасное');
        expect(button().classList.contains('primary')).toBe(true);
    });

    it('describes the button by what it touches and what it does not', () => {
        const { root, button } = create({ counts: counts({ materials: 1 }) });
        const description = root.querySelector<HTMLElement>(`#${button().getAttribute('aria-describedby')}`)!;
        expect(description.textContent).toContain('Ваши правки и удаления не трогаем.');
    });

    it('has no «Что нового» block without a note, and can take a lower heading level', () => {
        const { root } = create({ counts: counts({ materials: 1 }), headingLevel: 3 });
        expect(root.querySelector('.news')).toBeNull();
        expect(root.querySelector('h3')).not.toBeNull();
        expect(root.querySelector('h2')).toBeNull();
    });

    it('emits when the button is pressed', () => {
        const { fixture, button } = create({ counts: counts({ exercises: 2 }) });
        const accepted = vi.fn();
        fixture.componentInstance.acceptSafe.subscribe(accepted);
        button().click();
        expect(accepted).toHaveBeenCalledTimes(1);
    });

    it('keeps the focusable button but refuses the action while it runs', () => {
        const { fixture, button } = create({ counts: counts({ exercises: 2 }), busy: true });
        const accepted = vi.fn();
        fixture.componentInstance.acceptSafe.subscribe(accepted);
        expect(button().disabled).toBe(false);
        expect(button().getAttribute('aria-busy')).toBe('true');
        expect(button().getAttribute('aria-disabled')).toBe('true');
        expect(button().textContent!.trim()).toBe('Принимаем…');
        button().click();
        expect(accepted).not.toHaveBeenCalled();
    });

    it('says there is nothing safe to accept when only conflicts and deletions remain', () => {
        const { root, fixture, button } = create({ counts: counts({ conflicts: 2, deleted: 1 }) });
        const accepted = vi.fn();
        fixture.componentInstance.acceptSafe.subscribe(accepted);
        expect(root.querySelector('.line')?.textContent).toBe('2 конфликта, 1 удалено автором');
        expect(button().getAttribute('aria-disabled')).toBe('true');
        expect(root.querySelector('.action .hint')?.textContent).toBe('Безопасного для приёма сейчас нет.');
        button().click();
        expect(accepted).not.toHaveBeenCalled();
    });

    it('says so when the update holds nothing for the reader', () => {
        const { root } = create({ counts: counts() });
        expect(root.querySelector('.line')?.textContent).toBe('Для вас в этом обновлении ничего нет.');
    });
});
