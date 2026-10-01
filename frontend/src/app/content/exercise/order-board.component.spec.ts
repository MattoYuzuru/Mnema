import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { MEDIA_PLAYBACK_RESOLVER } from '../../features/study/media-playback-resolver';
import { fakePlayback } from '../../features/study/study-test-data';
import { LearnerOrderItem } from './exercise-content.models';
import { OrderBoardComponent } from './order-board.component';

const id = (suffix: number) => `0d000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}`;
const ITEMS: LearnerOrderItem[] = [
    { itemId: id(1), blocks: [{ kind: 'TEXT', text: 'Это' }] },
    { itemId: id(2), blocks: [{ kind: 'TEXT', text: 'очень' }] },
    { itemId: id(3), blocks: [{ kind: 'TEXT', text: 'очень' }] },
    { itemId: id(4), blocks: [{ kind: 'AUDIO', assetId: 'aaaaaaaa-0000-4000-8000-000000000001', transcriptAvailable: false }] },
    { itemId: id(5), blocks: [{ kind: 'IMAGE', assetId: 'aaaaaaaa-0000-4000-8000-000000000003', alt: 'Кадр 3' }] }
];

/** Owns the sequence like the learner exercise does, so moves re-render the board. */
@Component({
    imports: [OrderBoardComponent],
    template: `<app-order-board [items]="items()" [sequence]="sequence()" [disabled]="disabled()" (sequenceChange)="sequence.set($event)" />`
})
class HostComponent {
    items = signal(ITEMS);
    sequence = signal<readonly string[]>(ITEMS.map(item => item.itemId));
    disabled = signal(false);
}

describe('OrderBoardComponent', () => {
    let fixture: ComponentFixture<HostComponent>;
    const root = () => fixture.nativeElement as HTMLElement;
    const rows = () => [...root().querySelectorAll<HTMLElement>('li.order-item')];
    const order = () => rows().map(row => row.getAttribute('data-item-id'));
    const rowOf = (n: number) => root().querySelector<HTMLElement>(`[data-item-id="${id(n)}"]`)!;
    const button = (n: number, kind: 'up' | 'down') => rowOf(n).querySelector<HTMLButtonElement>(`[data-move="${kind}"]`)!;
    const status = () => root().querySelector('[role="status"]')!.textContent!.trim();

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [{ provide: MEDIA_PLAYBACK_RESOLVER, useValue: { resolve: fakePlayback } }] });
        fixture = TestBed.createComponent(HostComponent);
        document.body.appendChild(fixture.nativeElement);
        fixture.detectChanges();
    });
    afterEach(() => fixture.nativeElement.remove());

    it('shows the sequence it is given, as a list with a live region, and keeps media apart from every move control', () => {
        expect(order()).toEqual([1, 2, 3, 4, 5].map(id));
        expect(root().querySelector('ol')?.getAttribute('aria-label')).toBe('Элементы для расстановки, всего 5');
        expect(root().querySelector('[role="status"]')?.getAttribute('aria-live')).toBe('polite');
        for (const row of rows()) {
            const content = row.querySelector('.order-content')!;
            const controls = row.querySelector('.order-controls')!;
            expect(content.contains(controls)).toBeFalse();
            expect(content.querySelector('button[data-move]')).toBeNull();
        }
    });

    it('moves an item up and down by button, announces it and disables the arrows at the ends', () => {
        button(3, 'up').click(); fixture.detectChanges();
        expect(order()).toEqual([1, 3, 2, 4, 5].map(id));
        expect(status()).toBe('Элемент «очень» перемещён на позицию 2 из 5.');
        button(3, 'down').click(); fixture.detectChanges();
        expect(order()).toEqual([1, 2, 3, 4, 5].map(id));
        expect(button(1, 'up').disabled).toBeTrue();
        expect(button(5, 'down').disabled).toBeTrue();
        button(1, 'up').click(); fixture.detectChanges();
        expect(order()).toEqual([1, 2, 3, 4, 5].map(id));
    });

    it('names generated media items by their issued number, which does not change when they move', () => {
        expect(button(4, 'up').getAttribute('aria-label')).toBe('Поднять выше: Аудио, элемент 4');
        expect(button(5, 'up').getAttribute('aria-label')).toBe('Поднять выше: Кадр 3');
        button(4, 'up').click(); fixture.detectChanges();
        expect(button(4, 'up').getAttribute('aria-label')).toBe('Поднять выше: Аудио, элемент 4');
        expect(status()).toBe('Элемент «Аудио, элемент 4» перемещён на позицию 3 из 5.');
    });

    it('moves to a chosen position with the «На позицию N» select by keyboard only', () => {
        const trigger = rowOf(1).querySelector<HTMLButtonElement>('[role="combobox"]')!;
        expect(trigger.textContent).toContain('На позицию 1');
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
        fixture.detectChanges();
        expect(trigger.getAttribute('aria-expanded')).toBe('true');
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
        trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
        fixture.detectChanges();
        expect(order()).toEqual([2, 3, 4, 5, 1].map(id));
        expect(status()).toBe('Элемент «Это» перемещён на позицию 5 из 5.');
        expect(rowOf(1).querySelector('[role="combobox"]')!.textContent).toContain('На позицию 5');
    });

    it('returns focus to the moved item after the list re-inserts it, switching arrows at the end of the list', async () => {
        button(2, 'up').click(); fixture.detectChanges();
        await fixture.whenStable();
        expect(document.activeElement).toBe(button(2, 'down'));      // now first: its up arrow is disabled
        button(2, 'down').click(); fixture.detectChanges();
        await fixture.whenStable();
        expect(document.activeElement).toBe(button(2, 'down'));
    });

    it('does not move anything when a media control is pressed, and no move control sits inside the content', () => {
        spyOn(HTMLMediaElement.prototype, 'play').and.resolveTo();   // a real play would load the clip after this test ends
        const changes: unknown[] = [];
        fixture.componentInstance.sequence.set(ITEMS.map(item => item.itemId));
        const board = fixture.debugElement.children[0].componentInstance as OrderBoardComponent;
        board.sequenceChange.subscribe(value => changes.push(value));
        for (const control of root().querySelectorAll<HTMLElement>('.order-content button, .order-content audio, .order-content img, .order-content')) {
            control.click();
        }
        fixture.detectChanges();
        expect(changes).toEqual([]);
        expect(order()).toEqual([1, 2, 3, 4, 5].map(id));
        expect(root().querySelectorAll('.order-content button').length).toBeGreaterThan(0);   // the audio player is really there
    });

    it('keeps the same order across a re-render of equal items and does not reorder identical tiles', () => {
        button(5, 'up').click(); button(1, 'down').click(); fixture.detectChanges();
        const before = order();
        fixture.componentInstance.items.set(ITEMS.map(item => ({ ...item, blocks: [...item.blocks] })));
        fixture.detectChanges();
        expect(order()).toEqual(before);
        const same = rows().filter(row => row.textContent?.includes('очень')).map(row => row.getAttribute('data-item-id'));
        expect(same.sort()).toEqual([id(2), id(3)]);
    });

    it('supports dragging by the handle as an extra way and ignores foreign drags', () => {
        const handle = (n: number) => rowOf(n).querySelector<HTMLElement>('.drag-handle')!;
        const transfer = () => new DataTransfer();
        const dragStart = new DragEvent('dragstart', { bubbles: true, dataTransfer: transfer() });
        handle(4).dispatchEvent(dragStart);
        fixture.detectChanges();
        expect(rowOf(4).classList).toContain('is-dragging');
        const over = new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: transfer() });
        rowOf(2).dispatchEvent(over);
        expect(over.defaultPrevented).toBeTrue();
        fixture.detectChanges();
        expect(rowOf(2).classList).toContain('is-drop-target');
        rowOf(2).dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer() }));
        fixture.detectChanges();
        expect(order()).toEqual([1, 4, 2, 3, 5].map(id));
        expect(rowOf(4).classList).not.toContain('is-dragging');

        const foreign = new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: transfer() });
        rowOf(3).dispatchEvent(foreign);
        expect(foreign.defaultPrevented).toBeFalse();
        rowOf(3).dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: transfer() }));
        expect(order()).toEqual([1, 4, 2, 3, 5].map(id));
    });

    it('locks every control while disabled and offers no drag handle', () => {
        fixture.componentInstance.disabled.set(true); fixture.detectChanges();
        expect(root().querySelectorAll('.drag-handle').length).toBe(0);
        expect([...root().querySelectorAll<HTMLButtonElement>('button[data-move]')].every(control => control.disabled)).toBeTrue();
        expect(root().querySelector<HTMLButtonElement>('[role="combobox"]')!.disabled).toBeTrue();
    });
});
