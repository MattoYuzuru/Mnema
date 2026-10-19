import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { RhythmWeekComponent } from './rhythm-week.component';

@Component({
    imports: [RhythmWeekComponent],
    template: '<app-rhythm-week [weekStart]="weekStart()" [today]="today()" [days]="days()" />'
})
class HostComponent {
    readonly weekStart = signal('2026-10-19');
    readonly today = signal('2026-10-23');
    readonly days = signal<readonly string[]>(['2026-10-20', '2026-10-22', '2026-10-23']);
}

interface Patch { today?: string; days?: readonly string[] }

describe('RhythmWeekComponent (the seven days of the weekly rhythm)', () => {
    async function render(patch: Patch = {}): Promise<{ root: HTMLElement; host: HostComponent; update: () => Promise<void> }> {
        const fixture = TestBed.createComponent(HostComponent);
        if (patch.today !== undefined) fixture.componentInstance.today.set(patch.today);
        if (patch.days !== undefined) fixture.componentInstance.days.set(patch.days);
        const update = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); };
        await update();
        return { root: fixture.nativeElement as HTMLElement, host: fixture.componentInstance, update };
    }
    const cells = (root: HTMLElement): HTMLElement[] => [...root.querySelectorAll<HTMLElement>('ol.week > li')];

    it('draws Пн to Вс as an ordered list of seven, Monday first', async () => {
        const { root } = await render();
        expect(root.querySelector('ol.week')).not.toBeNull();
        expect(cells(root).map(cell => cell.querySelector('.name')?.textContent)).toEqual(['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс']);
    });

    it('marks a studied day with a check mark AND a filled circle (two signs), and an unstudied one with neither', async () => {
        const { root } = await render();
        expect(cells(root).map(cell => cell.classList.contains('done'))).toEqual([false, true, false, true, true, false, false]);
        expect(cells(root).map(cell => cell.querySelector('.day')?.textContent)).toEqual(['', '✓', '', '✓', '✓', '', '']);
    });

    it('outlines today (a shape), also when today is not a studied day, and marks no day when today is outside the week', async () => {
        const { root } = await render();
        expect(cells(root).map(cell => cell.classList.contains('today'))).toEqual([false, false, false, false, true, false, false]);
        const second = await render({ today: '2026-10-21' });
        expect(second.root.querySelectorAll('.today')).toHaveLength(1);
        expect(cells(second.root)[2].classList.contains('today')).toBe(true);
        expect(cells(second.root)[2].classList.contains('done')).toBe(false);
        const outside = await render({ today: '2026-11-02' });
        expect(outside.root.querySelectorAll('.today')).toHaveLength(0);
    });

    it('puts today at either end of the week', async () => {
        const first = await render({ today: '2026-10-19', days: ['2026-10-19'] });
        expect(cells(first.root)[0].classList.contains('today') && cells(first.root)[0].classList.contains('done')).toBe(true);
        const last = await render({ today: '2026-10-25', days: ['2026-10-25'] });
        expect(cells(last.root)[6].classList.contains('today') && cells(last.root)[6].classList.contains('done')).toBe(true);
    });

    it('says the week once, in a visually hidden paragraph, and hides the visual list from assistive technology', async () => {
        const { root } = await render();
        const summary = root.querySelector('p.visually-hidden');
        expect(summary?.textContent).toBe('Эта неделя: занятия во вторник, в четверг и в пятницу.');
        expect(root.querySelector('ol.week')?.getAttribute('aria-hidden')).toBe('true');
        // Nothing else in the component is readable text for a screen reader.
        expect([...root.querySelectorAll('[aria-hidden="true"]')].map(item => item.tagName)).toEqual(['OL']);
    });

    it('says a week without a session plainly, with no negative word', async () => {
        const { root } = await render({ days: [], today: '2026-10-21' });
        expect(root.querySelector('p.visually-hidden')?.textContent).toBe('Эта неделя: занятия ещё не отмечены.');
        expect(root.querySelectorAll('.done')).toHaveLength(0);
        expect(root.textContent).not.toMatch(/пропущ|потер|долг|серия|не удалось/iu);
    });

    it('follows a new day when the input changes', async () => {
        const { root, host, update } = await render({ days: ['2026-10-20'] });
        expect(cells(root).filter(cell => cell.classList.contains('done'))).toHaveLength(1);
        host.days.set(['2026-10-20', '2026-10-23']);
        await update();
        expect(cells(root).filter(cell => cell.classList.contains('done'))).toHaveLength(2);
        expect(root.querySelector('p.visually-hidden')?.textContent).toBe('Эта неделя: занятия во вторник и в пятницу.');
    });
});
