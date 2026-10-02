import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ItemSummary } from '../../authoring/authoring.models';
import { MaterialSelection } from './material-selection';
import { SelectableMaterialListComponent } from './selectable-material-list.component';

describe('SelectableMaterialListComponent', () => {
    const deckId = '11111111-1111-4111-8111-111111111111';
    const row = (index: number, overrides: Partial<ItemSummary> = {}): ItemSummary => ({
        memberKey: `44444444-4444-4444-8444-${String(index).padStart(12, '0')}`,
        itemRevisionId: `55555555-5555-4555-8555-${String(index).padStart(12, '0')}`,
        itemVersion: '0', ordinal: index, formatVersion: 1, createdAt: '2026-09-12T12:00:00Z', updatedAt: '2026-09-14T08:00:00Z',
        title: `Материал ${index + 1}`, exerciseCount: index % 3, exemplar: false, ...overrides
    });
    const rows = [0, 1, 2, 3, 4].map(index => row(index));
    let fixture: ComponentFixture<SelectableMaterialListComponent>;
    let selection: MaterialSelection;
    const root = () => fixture.nativeElement as HTMLElement;
    const checkbox = (index: number) => root().querySelectorAll<HTMLInputElement>('li .check input')[index];
    const header = () => root().querySelector<HTMLInputElement>('.select-all input')!;

    function open(items: readonly ItemSummary[] = rows, total = 5, budget = { count: 1, limit: 10 }) {
        selection = new MaterialSelection();
        selection.total.set(total);
        fixture = TestBed.createComponent(SelectableMaterialListComponent);
        fixture.componentRef.setInput('deckId', deckId);
        fixture.componentRef.setInput('items', items);
        fixture.componentRef.setInput('total', total);
        fixture.componentRef.setInput('selection', selection);
        fixture.componentRef.setInput('exemplars', budget);
        fixture.detectChanges();
        return fixture.componentInstance;
    }

    beforeEach(() => TestBed.configureTestingModule({ imports: [SelectableMaterialListComponent], providers: [provideRouter([])] }));

    it('renders a <ul> of <li> rows: a checkbox, a stretched link to the material and the exercise count', () => {
        open();
        const items = root().querySelectorAll('ul.item-list > li.item-row');
        expect(items).toHaveLength(5);
        const first = items[0];
        expect(first.querySelector('a.row-link')?.getAttribute('href')).toBe(`/decks/${deckId}/materials/${rows[0].memberKey}`);
        // A checkbox must not be inside a link: it sits beside it.
        expect(first.querySelector('a input')).toBeNull();
        expect(first.querySelector('.check input')?.getAttribute('aria-label')).toBe('Выбрать «Материал 1»');
        expect(first.querySelector('.count.missing')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('нет упражнений');
        expect(items[1].querySelector('.count')?.textContent?.trim()).toBe('1 упражнение');
        expect(items[2].querySelector('.count')?.textContent?.trim()).toBe('2 упражнения');
        expect(first.querySelector('.folio')?.textContent?.trim()).toBe('1');
        expect(items[0].querySelector('time')?.getAttribute('datetime')).toBe('2026-09-14T08:00:00Z');
    });

    it('gives an untitled material a readable name', () => {
        open([row(0, { title: '' })], 1);
        expect(root().querySelector('.row-link')?.textContent?.trim()).toBe('Материал без текста');
        expect(checkbox(0).getAttribute('aria-label')).toBe('Выбрать «Материал без текста»');
    });

    it('selects with the checkbox and reflects the shared selection', () => {
        open();
        checkbox(1).click();
        fixture.detectChanges();
        expect(selection.isSelected(rows[1].memberKey)).toBe(true);
        expect(root().querySelectorAll('li.selected')).toHaveLength(1);
        expect(checkbox(1).checked).toBe(true);
        expect(header().indeterminate).toBe(true);
        expect(header().checked).toBe(false);
    });

    it('selects a range with shift+click', () => {
        open();
        checkbox(1).click();
        checkbox(3).dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, shiftKey: true }));
        fixture.detectChanges();
        expect(rows.filter(entry => selection.isSelected(entry.memberKey)).map(entry => entry.ordinal)).toEqual([1, 2, 3]);
        expect(Array.from(root().querySelectorAll('li')).map(li => li.classList.contains('selected'))).toEqual([false, true, true, true, false]);
    });

    it('drives the tri-state «Выбрать все» over the loaded rows', () => {
        open();
        expect(header().checked).toBe(false);
        expect(header().indeterminate).toBe(false);
        header().click();
        fixture.detectChanges();
        expect(header().checked).toBe(true);
        expect(header().indeterminate).toBe(false);
        expect(selection.count()).toBe(5);
        header().click();
        fixture.detectChanges();
        expect(selection.count()).toBe(0);
        checkbox(0).click();
        fixture.detectChanges();
        expect(header().indeterminate).toBe(true);
        header().click();
        fixture.detectChanges();
        expect(selection.count()).toBe(5);
    });

    it('offers «Выбрать все N в колоде?» only when every loaded row is selected and more exist, then selects the whole deck', () => {
        open(rows, 50);
        expect(root().querySelector('.escalate')).toBeNull();
        header().click();
        fixture.detectChanges();
        const prompt = root().querySelector('.escalate')!;
        expect(prompt.textContent?.replace(/\s+/g, ' ')).toContain('Выбрано загруженных: 5.');
        prompt.querySelector('button')!.click();
        fixture.detectChanges();
        expect(selection.allInDeck()).toBe(true);
        expect(selection.count()).toBe(50);
        expect(root().querySelector('.escalate')?.textContent?.replace(/\s+/g, ' ')).toContain('Выбраны все 50 материалов колоды.');
        // Rows that load later arrive selected, and unchecking one records an exception.
        checkbox(2).click();
        fixture.detectChanges();
        expect(selection.count()).toBe(49);
        expect(root().querySelector('.escalate')?.textContent).toContain('кроме 1');
        root().querySelector<HTMLButtonElement>('.escalate button')!.click();
        fixture.detectChanges();
        expect(selection.count()).toBe(0);
    });

    it('does not offer the escalation when everything is already loaded', () => {
        open();
        header().click();
        fixture.detectChanges();
        expect(root().querySelector('.escalate')).toBeNull();
    });

    describe('«Эталон» star', () => {
        it('is a toggle button named after the material and reports intent', () => {
            const component = open([row(0, { exemplar: true }), row(1)]);
            const stars = root().querySelectorAll<HTMLButtonElement>('button.star');
            expect(stars[0].getAttribute('aria-pressed')).toBe('true');
            expect(stars[1].getAttribute('aria-pressed')).toBe('false');
            expect(stars[0].getAttribute('aria-label')).toBe('Эталон: Материал 1');
            const requested: ItemSummary[] = [];
            component.toggleExemplar.subscribe(item => requested.push(item));
            stars[1].click();
            stars[0].click();
            expect(requested.map(item => item.ordinal)).toEqual([1, 0]);
            expect(root().querySelector('.budget')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Эталонов: 1 из 10.');
        });

        it('at the limit stays focusable, explains itself and ignores a new star, but still lets one be removed', () => {
            const component = open([row(0, { exemplar: true }), row(1)], 2, { count: 10, limit: 10 });
            const stars = root().querySelectorAll<HTMLButtonElement>('button.star');
            expect(stars[1].getAttribute('aria-disabled')).toBe('true');
            expect(stars[1].disabled).toBe(false);
            const note = root().querySelector('.budget')!;
            expect(stars[1].getAttribute('aria-describedby')).toBe(note.id);
            expect(note.textContent).toContain('Лимит достигнут');
            expect(stars[0].getAttribute('aria-disabled')).toBeNull();
            const requested: ItemSummary[] = [];
            component.toggleExemplar.subscribe(item => requested.push(item));
            stars[1].click();
            expect(requested).toEqual([]);
            stars[0].click();
            expect(requested).toHaveLength(1);
        });

        it('ignores clicks while its own request is in flight', () => {
            const component = open([row(0)], 1);
            fixture.componentRef.setInput('starPending', new Set([rows[0].memberKey]));
            fixture.detectChanges();
            const requested: ItemSummary[] = [];
            component.toggleExemplar.subscribe(item => requested.push(item));
            const star = root().querySelector<HTMLButtonElement>('button.star')!;
            expect(star.getAttribute('aria-busy')).toBe('true');
            star.click();
            expect(requested).toEqual([]);
        });
    });

    it('shows «Показать ещё» only with more pages, reports intent, disables while loading and keeps a failure visible', () => {
        const component = open(rows, 50);
        expect(root().querySelector('.more')).toBeNull();
        fixture.componentRef.setInput('hasMore', true);
        fixture.detectChanges();
        let requests = 0;
        component.loadMore.subscribe(() => requests++);
        const more = root().querySelector<HTMLButtonElement>('.more button')!;
        expect(more.textContent?.trim()).toBe('Показать ещё (45)');
        more.click();
        expect(requests).toBe(1);
        fixture.componentRef.setInput('loadingMore', true);
        fixture.detectChanges();
        expect(more.disabled).toBe(true);
        fixture.componentRef.setInput('loadingMore', false);
        fixture.componentRef.setInput('moreError', true);
        fixture.detectChanges();
        expect(root().querySelector('.problem')?.textContent).toContain('Загруженные остались на месте');
    });
});
