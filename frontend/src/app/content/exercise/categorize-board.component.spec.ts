import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { MEDIA_PLAYBACK_RESOLVER } from '../../features/study/media-playback-resolver';
import { fakePlayback } from '../../features/study/study-test-data';
import { CategorizeBoardComponent, CategoryAssignment } from './categorize-board.component';
import { LearnerCategorizeItem, LearnerCategory } from './exercise-content.models';

const item = (n: number) => `9a000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
const group = (n: number) => `ca000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
const CATEGORIES: LearnerCategory[] = [
    { categoryId: group(1), label: 'Существительное' }, { categoryId: group(2), label: 'Глагол' }, { categoryId: group(3), label: 'Наречие' }
];
const ITEMS: LearnerCategorizeItem[] = [
    { itemId: item(3), blocks: [{ kind: 'TEXT', text: 'река' }] },
    { itemId: item(4), blocks: [{ kind: 'AUDIO', assetId: 'aaaaaaaa-0000-4000-8000-000000000004', transcriptAvailable: false }] },
    { itemId: item(1), blocks: [{ kind: 'TEXT', text: 'дом' }] },
    { itemId: item(2), blocks: [{ kind: 'TEXT', text: 'бежать' }] }
];

@Component({
    imports: [CategorizeBoardComponent],
    template: `<app-categorize-board [categories]="categories" [items]="items()" [assignments]="assignments()"
      (assigned)="onAssigned($event)" />`
})
class HostComponent {
    categories = CATEGORIES;
    items = signal(ITEMS);
    assignments = signal<Readonly<Record<string, string>>>({});
    events: CategoryAssignment[] = [];
    onAssigned(change: CategoryAssignment): void {
        this.events.push(change);
        this.assignments.update(current => {
            const { [change.itemId]: _gone, ...rest } = current;
            return change.categoryId === null ? rest : { ...rest, [change.itemId]: change.categoryId };
        });
    }
}

describe('CategorizeBoardComponent', () => {
    let fixture: ComponentFixture<HostComponent>;
    const root = () => fixture.nativeElement as HTMLElement;
    const host = () => fixture.componentInstance;
    const select = (n: number) => root().querySelector<HTMLButtonElement>(`[data-item-id="${item(n)}"] [data-select]`)!;
    const place = (n: number) => root().querySelector<HTMLButtonElement>(`[data-category="${group(n)}"] [data-place]`)!;
    const counter = (n: number) => root().querySelector(`[data-category="${group(n)}"] .counter`)!.textContent!.trim();
    const status = () => root().querySelector('.board-status')!.textContent!.trim();
    /** Selecting then placing, with the change detection a real click gets between the two presses. */
    const put = (n: number, g: number) => { select(n).click(); fixture.detectChanges(); place(g).click(); fixture.detectChanges(); };
    const inGroup = (n: number) => [...root().querySelectorAll(`[data-category="${group(n)}"] [data-item-id]`)].map(row => row.getAttribute('data-item-id'));

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [{ provide: MEDIA_PLAYBACK_RESOLVER, useValue: { resolve: fakePlayback } }] });
        fixture = TestBed.createComponent(HostComponent);
        document.body.appendChild(fixture.nativeElement);
        fixture.detectChanges();
    });
    afterEach(() => fixture.nativeElement.remove());

    it('lists every group with its label and a counter, and every item in the unassigned list', () => {
        expect([...root().querySelectorAll('.group h3')].map(heading => heading.textContent)).toEqual(['Существительное', 'Глагол', 'Наречие']);
        expect([1, 2, 3].map(counter)).toEqual(['Элементов: 0', 'Элементов: 0', 'Элементов: 0']);
        expect(root().querySelector('[data-pool] h3')?.textContent).toContain('Осталось распределить: 4');
        expect(root().querySelectorAll('[data-pool] [data-item-id]').length).toBe(4);
        expect(status()).toContain('Выберите элемент, затем группу');
    });

    it('assigns with the keyboard only: select an item, then a group button; several items can share a group', () => {
        expect([1, 2, 3].every(n => place(n).disabled)).toBe(true);
        select(1).click();
        fixture.detectChanges();
        expect(select(1).getAttribute('aria-pressed')).toBe('true');
        expect(place(1).disabled).toBe(false);
        expect(place(1).getAttribute('aria-label')).toBe('Поместить «дом» в группу «Существительное»');
        expect(status()).toBe('Выбран элемент «дом». Теперь выберите группу.');
        place(1).click();
        fixture.detectChanges();
        put(3, 1);
        expect(host().assignments()).toEqual({ [item(1)]: group(1), [item(3)]: group(1) });
        expect(inGroup(1).sort()).toEqual([item(1), item(3)].sort());
        expect(counter(1)).toBe('Элементов: 2');
        expect(root().querySelector('[data-pool] h3')?.textContent).toContain('Осталось распределить: 2');
        expect(status()).toContain('помещён в группу «Существительное»');
    });

    it('lets the learner change a decision until submit: re-assign to another group or return the item to the list', () => {
        put(2, 2);
        expect(inGroup(2)).toEqual([item(2)]);
        select(2).click();
        fixture.detectChanges();
        expect(place(2).disabled).toBe(true); // already there
        place(3).click();
        fixture.detectChanges();
        expect(inGroup(2)).toEqual([]);
        expect(inGroup(3)).toEqual([item(2)]);
        select(2).click();
        fixture.detectChanges();
        root().querySelector<HTMLButtonElement>('[data-unassign]')!.click();
        fixture.detectChanges();
        expect(host().assignments()).toEqual({});
        expect(status()).toContain('возвращён в список');
        expect(root().querySelector('[data-pool] h3')?.textContent).toContain('Осталось распределить: 4');
    });

    it('announces completion and keeps changing possible when everything is assigned', () => {
        for (const [n, g] of [[1, 1], [2, 2], [3, 1], [4, 2]])
            put(n, g);
        expect(root().querySelector('[data-pool] .empty')?.textContent).toContain('Все элементы распределены');
        expect(status()).toContain('Все элементы распределены');
        expect(select(4).disabled).toBe(false);
    });

    it('never assigns when a media control is pressed: players are siblings of the select and group buttons', () => {
        vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(); // a real play would load the clip after this test ends
        select(1).click();
        fixture.detectChanges();
        const audio = root().querySelector(`[data-item-id="${item(4)}"] .item-content`)!;
        expect(audio.querySelector('button[data-select]')).toBeNull();
        for (const control of audio.querySelectorAll<HTMLElement>('button, audio'))
            control.click();
        audio.dispatchEvent(new MouseEvent('click', { bubbles: true }));
        fixture.detectChanges();
        expect(host().events).toEqual([]);
        expect(select(1).getAttribute('aria-pressed')).toBe('true');
        expect(select(4).getAttribute('aria-pressed')).toBe('false');
        expect(audio.querySelectorAll('button').length).toBeGreaterThan(0);
    });

    it('keeps the learner on the next item after a keyboard assignment and leaves focus alone for a pointer', async () => {
        const board = fixture.debugElement.children[0].componentInstance as CategorizeBoardComponent;
        board.keyboard = true;
        put(3, 1);
        await fixture.whenStable();
        expect(document.activeElement).toBe(select(4));
        board.keyboard = false;
        select(4).focus();
        put(4, 2);
        await fixture.whenStable();
        expect(document.activeElement).not.toBe(select(1));
        expect(host().events.map(event => event.keyboard)).toEqual([true, false]);
    });

    // Stacking the groups in one column on a narrow screen is layout jsdom cannot compute; the browser harness owns it
    // (scripts/browser-identity, scenario mechanics-study-categorize-390 / categorize_study_390_columns).
    it('drops a selection whose item disappeared', () => {
        select(1).click();
        fixture.detectChanges();
        host().items.set(ITEMS.filter(entry => entry.itemId !== item(1)));
        fixture.detectChanges();
        fixture.detectChanges();
        const board = fixture.debugElement.children[0].componentInstance as CategorizeBoardComponent;
        expect(board.items().length).toBe(3);
        expect(board.selected()).toBeNull();
    });
});
