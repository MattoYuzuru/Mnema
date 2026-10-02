import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ToggletipComponent } from './toggletip.component';

@Component({
    imports: [ToggletipComponent],
    template: `<app-toggletip topic="режимы сравнения"><p>Мягко прощает знаки препинания.</p></app-toggletip>
      <app-toggletip topic="второе"><p>Другое.</p></app-toggletip>`
})
class HostComponent {}

describe('ToggletipComponent', () => {
    function create(): HTMLElement {
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('is a button that targets an auto popover and is named «Подробнее: <topic>»', () => {
        const root = create();
        const button = root.querySelector<HTMLButtonElement>('button')!;
        const popover = root.querySelector<HTMLElement>('[popover]')!;
        expect(button.type).toBe('button');
        expect(button.getAttribute('aria-label')).toBe('Подробнее: режимы сравнения');
        expect(button.getAttribute('popovertarget')).toBe(popover.id);
        expect(popover.getAttribute('popover')).toBe('auto');
        expect(button.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    });

    it('projects plain content into the popover', () => {
        const popover = create().querySelector('[popover]')!;
        expect(popover.textContent?.trim()).toBe('Мягко прощает знаки препинания.');
    });

    it('gives every instance its own popover id and anchor', () => {
        const root = create();
        const ids = [...root.querySelectorAll<HTMLElement>('[popover]')].map(element => element.id);
        expect(new Set(ids).size).toBe(2);
        expect([...root.querySelectorAll('button')].map(button => button.getAttribute('popovertarget'))).toEqual(ids);
    });
});
