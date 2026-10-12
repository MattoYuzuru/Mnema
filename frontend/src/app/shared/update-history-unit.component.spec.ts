import { TestBed } from '@angular/core/testing';

import { HistoryUnitView, UpdateHistoryUnitComponent } from './update-history-unit.component';

describe('UpdateHistoryUnitComponent', () => {
    const view = (change: Partial<HistoryUnitView> = {}): HistoryUnitView => ({ id: 'u', kind: 'material', change: 'CHANGED', title: 'Спряжение ser и estar', state: null, ...change });

    function create(unit: HistoryUnitView) {
        const fixture = TestBed.createComponent(UpdateHistoryUnitComponent);
        fixture.componentRef.setInput('unit', unit);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('names the kind and the change in words, and the row by its title', () => {
        const root = create(view());
        expect(root.querySelector('.kind')!.textContent).toBe('Материал');
        expect(root.querySelector('.stamp')!.textContent).toBe('Исправлено');
        const article = root.querySelector('article')!;
        const title = root.querySelector('.title')!;
        expect(title.textContent).toBe('Спряжение ser и estar');
        expect(article.getAttribute('aria-labelledby')).toBe(title.id);
        expect(root.querySelector('.state')).toBeNull();
    });

    it.each([
        ['ADDED', 'material', 'Добавлено', 'Материал'],
        ['CHANGED', 'exercise', 'Исправлено', 'Упражнение'],
        ['REMOVED', 'exercise', 'Удалено', 'Упражнение'],
        ['REORDERED', 'order', 'Порядок', 'Порядок материалов']
    ] as const)('says %s as «%s»', (change, kind, word, kindWord) => {
        const root = create(view({ change, kind }));
        expect(root.querySelector('.stamp')!.textContent).toBe(word);
        expect(root.querySelector('.kind')!.textContent).toBe(kindWord);
        expect(root.querySelector('.stamp')!.classList.contains('solid')).toBe(change === 'REMOVED');
    });

    it('says in a sentence when the unit is no longer what the update made it', () => {
        expect(create(view({ state: 'RETURNED' })).querySelector('.state')!.textContent).toBe('Вы уже вернули');
        expect(create(view({ state: 'CHANGED_SINCE' })).querySelector('.state')!.textContent).toBe('Вы изменили после обновления');
    });

    it('keeps a long title whole and hosts the action the page projects', () => {
        const long = 'Превратите в приказ: tú hablas, а потом объясните, почему здесь нужна именно эта форма';
        const fixture = TestBed.createComponent(UpdateHistoryUnitComponent);
        fixture.componentRef.setInput('unit', view({ title: long }));
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('.title')!.textContent).toBe(long);
        expect((fixture.nativeElement as HTMLElement).querySelector('.action')).not.toBeNull();
    });
});
