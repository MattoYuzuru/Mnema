import { TestBed } from '@angular/core/testing';

import { TourNudgeComponent } from './tour-nudge.component';

describe('TourNudgeComponent', () => {
    function render(inline = false) {
        const fixture = TestBed.createComponent(TourNudgeComponent);
        fixture.componentRef.setInput('title', 'Не нашли подходящую?');
        fixture.componentRef.setInput('body', 'Создайте свою колоду — это займёт пару минут.');
        fixture.componentRef.setInput('action', 'Создать свою');
        fixture.componentRef.setInput('inline', inline);
        fixture.detectChanges();
        return { fixture, root: fixture.nativeElement as HTMLElement, nudge: fixture.componentInstance };
    }

    it('is a region named by its heading, not a modal dialog', () => {
        const { root } = render();
        const region = root.querySelector('section')!;
        expect(region.getAttribute('aria-labelledby')).toBe(root.querySelector('h2')!.id);
        expect(root.querySelector('dialog')).toBeNull();
        expect(root.querySelector('p')!.textContent).toBe('Создайте свою колоду — это займёт пару минут.');
    });

    it('offers one primary action and «Назад»', () => {
        const { root, nudge } = render();
        const seen: string[] = [];
        nudge.accepted.subscribe(() => seen.push('accepted'));
        nudge.dismissed.subscribe(() => seen.push('dismissed'));
        const [back, act] = [...root.querySelectorAll<HTMLButtonElement>('button')];
        expect([back.textContent, act.textContent]).toEqual(['Назад', 'Создать свою']);
        expect(act.classList.contains('primary')).toBe(true);
        back.click();
        act.click();
        expect(seen).toEqual(['dismissed', 'accepted']);
    });

    it('takes the focus to its heading only on request', () => {
        const { root, nudge } = render();
        expect(document.activeElement).not.toBe(root.querySelector('h2'));
        nudge.focusHeading();
        expect(document.activeElement).toBe(root.querySelector('h2'));
    });

    it('can be drawn in the flow', () => {
        expect(render(true).root.classList.contains('inline')).toBe(true);
    });
});
