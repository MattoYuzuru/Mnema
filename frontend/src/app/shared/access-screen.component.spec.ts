import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { AccessScreenComponent } from './access-screen.component';

@Component({
    imports: [AccessScreenComponent],
    template: `
      <app-access-screen heading="Колода не найдена" eyebrow="Колода" [headingLevel]="level">
        <p>Проверьте ссылку.</p>
        <a access-actions class="button" href="/">На главную</a>
      </app-access-screen>
    `
})
class HostComponent { level: 1 | 2 | 3 | 4 = 1; }

describe('AccessScreenComponent', () => {
    function render(level: 1 | 2 | 3 | 4 = 1): HTMLElement {
        const fixture = TestBed.createComponent(HostComponent);
        fixture.componentInstance.level = level;
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('is a labelled section with the page heading, the explanation and the actions', () => {
        const root = render();
        const section = root.querySelector('section.access')!;
        const heading = section.querySelector('h1')!;
        expect(heading.textContent).toBe('Колода не найдена');
        expect(heading.getAttribute('tabindex')).toBe('-1');
        expect(section.getAttribute('aria-labelledby')).toBe(heading.id);
        expect(section.querySelector('.eyebrow')?.textContent).toBe('Колода');
        expect(section.querySelector('.message p')?.textContent).toBe('Проверьте ссылку.');
        expect(section.querySelector('.actions a.button')?.textContent).toBe('На главную');
    });

    it('takes a lower heading level inside a page that has its own h1', () => {
        for (const [level, tag] of [[2, 'h2'], [3, 'h3'], [4, 'h4']] as const) {
            TestBed.resetTestingModule();
            const root = render(level);
            expect(root.querySelector('h1')).toBeNull();
            expect(root.querySelector(tag)?.textContent).toBe('Колода не найдена');
        }
    });
});
