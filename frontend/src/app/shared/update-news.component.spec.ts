import { TestBed } from '@angular/core/testing';

import { UPDATE_TEXT } from './copy-text';
import { UpdateNewsComponent } from './update-news.component';

describe('«Что нового» sentences', () => {
    it('writes the taken changes with their plural form and the waiting ones with the matching verb', () => {
        expect(UPDATE_TEXT.newsApplied(1)).toBe('Автор обновил колоду, мы взяли 1 изменение автоматически.');
        expect(UPDATE_TEXT.newsApplied(3)).toBe('Автор обновил колоду, мы взяли 3 изменения автоматически.');
        expect(UPDATE_TEXT.newsApplied(5)).toBe('Автор обновил колоду, мы взяли 5 изменений автоматически.');
        expect(UPDATE_TEXT.newsApplied(21)).toBe('Автор обновил колоду, мы взяли 21 изменение автоматически.');
        expect(UPDATE_TEXT.newsApplied(12)).toBe('Автор обновил колоду, мы взяли 12 изменений автоматически.');
        expect(UPDATE_TEXT.newsApplied(0)).toBe('Автор обновил колоду.');
        expect(UPDATE_TEXT.newsPending(1)).toBe('1 ждёт вашего решения.');
        expect(UPDATE_TEXT.newsPending(2)).toBe('2 ждут вашего решения.');
        expect(UPDATE_TEXT.newsPending(11)).toBe('11 ждут вашего решения.');
        expect(UPDATE_TEXT.newsPending(21)).toBe('21 ждёт вашего решения.');
    });
});

describe('UpdateNewsComponent', () => {
    function create(applied: number, pending: number) {
        const fixture = TestBed.createComponent(UpdateNewsComponent);
        fixture.componentRef.setInput('applied', applied);
        fixture.componentRef.setInput('pending', pending);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('is a section named by its «Что нового» heading, with the sentence of the numbers and no live region', () => {
        const root = create(3, 2);
        const section = root.querySelector('section')!;
        const heading = root.querySelector('h2')!;
        expect(heading.textContent).toBe('Что нового');
        expect(section.getAttribute('aria-labelledby')).toBe(heading.id);
        expect(root.querySelector('.line')!.textContent).toBe('Автор обновил колоду, мы взяли 3 изменения автоматически. 2 ждут вашего решения.');
        expect(root.querySelector('[aria-live], [role=status], [role=alert]')).toBeNull();
    });

    it('leaves out the second sentence when nothing waits, and gives every block its own heading id', () => {
        const root = create(1, 0);
        expect(root.querySelector('.line')!.textContent).toBe('Автор обновил колоду, мы взяли 1 изменение автоматически.');
        expect(create(2, 0).querySelector('h2')!.id).not.toBe(root.querySelector('h2')!.id);
    });
});
