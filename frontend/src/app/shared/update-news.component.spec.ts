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
    function create(inputs: { applied: number; pending: number; offer?: boolean }) {
        const fixture = TestBed.createComponent(UpdateNewsComponent);
        fixture.componentRef.setInput('applied', inputs.applied);
        fixture.componentRef.setInput('pending', inputs.pending);
        fixture.componentRef.setInput('appliedAt', '2026-10-12T09:30:00.000Z');
        fixture.componentRef.setInput('when', '2 часа назад, 12.10.2026, 12:30');
        if (inputs.offer) fixture.componentRef.setInput('offer', true);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('is a section named by its «Что нового» heading, with the time, the sentence of the numbers and no live region', () => {
        const root = create({ applied: 3, pending: 2 });
        const section = root.querySelector('section')!;
        const heading = root.querySelector('h2')!;
        expect(heading.textContent).toBe('Что нового');
        expect(section.getAttribute('aria-labelledby')).toBe(heading.id);
        const time = root.querySelector('time')!;
        expect(time.textContent).toBe('2 часа назад, 12.10.2026, 12:30');
        expect(time.getAttribute('datetime')).toBe('2026-10-12T09:30:00.000Z');
        expect(root.querySelector('.line')!.textContent).toBe('Автор обновил колоду, мы взяли 3 изменения автоматически. 2 ждут вашего решения.');
        expect(root.querySelector('[aria-live], [role=status], [role=alert]')).toBeNull();
        expect(root.querySelector('app-update-badge, button')).toBeNull();
    });

    it('leaves out the second sentence when nothing waits, and gives every block its own heading id', () => {
        const root = create({ applied: 1, pending: 0 });
        expect(root.querySelector('.line')!.textContent).toBe('Автор обновил колоду, мы взяли 1 изменение автоматически.');
        expect(create({ applied: 2, pending: 0 }).querySelector('h2')!.id).not.toBe(root.querySelector('h2')!.id);
    });

    it('carries the badge «Есть обновление» when the author has something waiting, and explains it only when this update left nothing to decide', () => {
        const waiting = create({ applied: 3, pending: 2, offer: true });
        expect(waiting.querySelector('app-update-badge')!.textContent!.trim()).toBe('Есть обновление');
        expect(waiting.querySelectorAll('h2')).toHaveLength(1);
        expect(waiting.querySelector('.line')!.textContent).toBe('Автор обновил колоду, мы взяли 3 изменения автоматически. 2 ждут вашего решения.');
        const published = create({ applied: 3, pending: 0, offer: true });
        expect(published.querySelector('.line')!.textContent).toBe(`Автор обновил колоду, мы взяли 3 изменения автоматически. ${UPDATE_TEXT.offerNote}`);
    });
});
