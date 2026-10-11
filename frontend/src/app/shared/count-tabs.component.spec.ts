import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { CountTab, CountTabsComponent } from './count-tabs.component';

type Kind = 'new' | 'fix' | 'conflict';

@Component({
    imports: [CountTabsComponent],
    template: `
      <app-count-tabs #tabsRef label="Классы изменений" [tabs]="tabs" [(value)]="value" />
      <div role="tabpanel" [id]="tabsRef.panelId(value())" [attr.aria-labelledby]="tabsRef.tabId(value())">панель</div>`
})
class HostComponent {
    readonly tabs: readonly CountTab<Kind>[] = [
        { value: 'new', label: 'Новое', count: 12 },
        { value: 'fix', label: 'Исправления', count: 5 },
        { value: 'conflict', label: 'Конфликты', count: 2 }
    ];
    readonly value = signal<Kind>('new');
}

describe('CountTabsComponent', () => {
    function create() {
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        const tabs = () => [...root.querySelectorAll<HTMLButtonElement>('[role=tab]')];
        const press = (target: HTMLElement, key: string) => {
            target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
            fixture.detectChanges();
        };
        return { fixture, root, tabs, press, host: fixture.componentInstance };
    }

    it('is a named tab list whose tabs carry their count in the name', () => {
        const { root, tabs } = create();
        expect(root.querySelector('[role=tablist]')?.getAttribute('aria-label')).toBe('Классы изменений');
        expect(tabs().map(tab => tab.textContent!.replace(/\s+/gu, ' ').trim())).toEqual(['Новое: 12', 'Исправления: 5', 'Конфликты: 2']);
    });

    it('selects one tab, makes only it tabbable and ties it to the panel the page draws', () => {
        const { root, tabs } = create();
        expect(tabs().map(tab => tab.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
        expect(tabs().map(tab => tab.tabIndex)).toEqual([0, -1, -1]);
        const panel = root.querySelector('[role=tabpanel]')!;
        expect(tabs()[0]!.getAttribute('aria-controls')).toBe(panel.id);
        expect(tabs()[1]!.getAttribute('aria-controls')).toBeNull();
        expect(panel.getAttribute('aria-labelledby')).toBe(tabs()[0]!.id);
    });

    it('selects by click', () => {
        const { fixture, tabs, host } = create();
        tabs()[2]!.click();
        fixture.detectChanges();
        expect(host.value()).toBe('conflict');
        expect(tabs().map(tab => tab.getAttribute('aria-selected'))).toEqual(['false', 'false', 'true']);
    });

    it('moves focus and selection with the arrow keys, wrapping, and with Home and End', () => {
        const { tabs, press, host } = create();
        tabs()[0]!.focus();
        press(tabs()[0]!, 'ArrowRight');
        expect(host.value()).toBe('fix');
        expect(document.activeElement).toBe(tabs()[1]);
        press(tabs()[1]!, 'End');
        expect(host.value()).toBe('conflict');
        press(tabs()[2]!, 'ArrowRight');
        expect(host.value()).toBe('new');
        press(tabs()[0]!, 'ArrowLeft');
        expect(host.value()).toBe('conflict');
        press(tabs()[2]!, 'Home');
        expect(host.value()).toBe('new');
        expect(document.activeElement).toBe(tabs()[0]);
    });

    it('leaves other keys and modified keys alone', () => {
        const { tabs, press, host } = create();
        press(tabs()[0]!, 'a');
        expect(host.value()).toBe('new');
        tabs()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', ctrlKey: true, bubbles: true, cancelable: true }));
        expect(host.value()).toBe('new');
    });
});
