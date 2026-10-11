import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { CompareColumnsComponent } from './compare-columns.component';

@Component({
    imports: [CompareColumnsComponent],
    template: `
      <app-compare-columns [mineAbsent]="mineAbsent()" [authorAbsent]="authorAbsent()" [headingLevel]="level()">
        <p compare-mine id="mine-body">Моё содержимое</p>
        <p compare-author id="author-body">Содержимое автора</p>
      </app-compare-columns>`
})
class HostComponent {
    readonly mineAbsent = signal(false);
    readonly authorAbsent = signal(false);
    readonly level = signal<3 | 4 | 5>(4);
}

describe('CompareColumnsComponent', () => {
    function create() {
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        return { fixture, root, host: fixture.componentInstance };
    }
    const column = (root: HTMLElement, name: 'mine' | 'author') => root.querySelector<HTMLElement>(`.column[data-column='${name}']`)!;
    const radios = (root: HTMLElement) => [...root.querySelectorAll<HTMLInputElement>('.switch input[type=radio]')];

    it('has two named groups with a heading each, mine first and the author\'s second', () => {
        const { root } = create();
        const columns = [...root.querySelectorAll<HTMLElement>('.column')];
        expect(columns.map(item => item.getAttribute('role'))).toEqual(['group', 'group']);
        const headings = columns.map(item => item.querySelector('h4')!);
        expect(headings.map(heading => heading.textContent!.trim())).toEqual(['Сейчас у вас', 'Вариант автора']);
        columns.forEach((item, index) => expect(item.getAttribute('aria-labelledby')).toBe(headings[index]!.id));
        expect(columns[0]!.textContent).toContain('Моё содержимое');
        expect(columns[1]!.textContent).toContain('Содержимое автора');
        expect(columns[0]!.compareDocumentPosition(columns[1]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('follows the heading level it is given', () => {
        const { fixture, root, host } = create();
        host.level.set(3);
        fixture.detectChanges();
        expect(root.querySelectorAll('.column h3')).toHaveLength(2);
        host.level.set(5);
        fixture.detectChanges();
        expect(root.querySelectorAll('.column h5')).toHaveLength(2);
    });

    it('offers the two sides in a native radio switch that starts on the reader\'s version', () => {
        const { root } = create();
        expect(root.querySelector('.switch legend')?.textContent?.trim()).toBe('Какую версию показать');
        expect(radios(root).map(input => input.labels![0]!.textContent!.trim())).toEqual(['Сейчас у вас', 'Вариант автора']);
        expect(radios(root).map(input => input.checked)).toEqual([true, false]);
        expect(root.querySelector('.compare')?.getAttribute('data-side')).toBe('mine');
    });

    it('shows the other side after the switch is used, and keeps the focus on the switch', () => {
        const { fixture, root } = create();
        const author = radios(root)[1]!;
        author.focus();
        author.click();
        fixture.detectChanges();
        expect(root.querySelector('.compare')?.getAttribute('data-side')).toBe('author');
        expect(radios(root).map(input => input.checked)).toEqual([false, true]);
        expect(document.activeElement).toBe(author);
        expect(root.querySelector('.switch .hint')?.textContent).toBe('Показан вариант автора.');
    });

    it('says in words that a side does not exist, instead of projecting it', () => {
        const { fixture, root, host } = create();
        host.mineAbsent.set(true);
        host.authorAbsent.set(true);
        fixture.detectChanges();
        expect(column(root, 'mine').querySelector('.absent')?.textContent).toContain('Нет у вас');
        expect(column(root, 'mine').textContent).toContain('В вашей копии этого пока нет.');
        expect(column(root, 'author').querySelector('.absent')?.textContent).toContain('Автор удалил');
        expect(root.querySelector('#mine-body')).toBeNull();
        expect(root.querySelector('#author-body')).toBeNull();
    });

    it('starts on the side that exists, unless the page chose one', () => {
        const { fixture, root, host } = create();
        host.mineAbsent.set(true);
        fixture.detectChanges();
        expect(root.querySelector('.compare')?.getAttribute('data-side')).toBe('author');
        expect(radios(root).map(input => input.checked)).toEqual([false, true]);
        host.mineAbsent.set(false);
        host.authorAbsent.set(true);
        fixture.detectChanges();
        expect(root.querySelector('.compare')?.getAttribute('data-side')).toBe('mine');
        host.mineAbsent.set(true);
        fixture.detectChanges();
        expect(root.querySelector('.compare')?.getAttribute('data-side')).toBe('mine');
    });

    it('keeps an explicit side even when that side is missing', () => {
        const fixture = TestBed.createComponent(CompareColumnsComponent);
        fixture.componentRef.setInput('mineAbsent', true);
        fixture.componentRef.setInput('side', 'mine');
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('.compare')?.getAttribute('data-side')).toBe('mine');
    });

    it('has no animation of its own', () => {
        const { root } = create();
        expect(root.querySelector('[style*="transition"], [style*="animation"]')).toBeNull();
    });
});
