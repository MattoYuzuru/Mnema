import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { installPopoverShim, popoverIsOpen, removePopoverShim } from '../../testing/popover-shim';
import { ActionMenuComponent, ActionMenuItem } from './action-menu.component';

@Component({
    imports: [ActionMenuComponent],
    template: `<button id="before" type="button">до</button>
      <app-action-menu label="Действия с колодой «Падежи»" [items]="items()" (chosen)="picked.push($event)" />
      <button id="after" type="button">после</button>`
})
class HostComponent {
    readonly items = signal<readonly ActionMenuItem[]>([
        { id: 'share', label: 'Поделиться', glyph: 'share' },
        { id: 'soon', label: 'Скоро', disabled: true },
        { id: 'report', label: 'Пожаловаться' },
        { id: 'remove', label: 'Убрать', danger: true }
    ]);
    readonly picked: string[] = [];
}

describe('ActionMenuComponent', () => {
    let host: HostComponent;
    let root: HTMLElement;
    let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;

    beforeEach(() => {
        installPopoverShim();
        fixture = TestBed.createComponent(HostComponent);
        host = fixture.componentInstance;
        root = fixture.nativeElement as HTMLElement;
        fixture.detectChanges();
    });
    afterEach(() => removePopoverShim());

    const trigger = (): HTMLButtonElement => root.querySelector<HTMLButtonElement>('button.trigger')!;
    const menu = (): HTMLElement => root.querySelector<HTMLElement>('[role=menu]')!;
    const items = (): HTMLButtonElement[] => [...root.querySelectorAll<HTMLButtonElement>('[role=menuitem]')];
    const enabled = (): HTMLButtonElement[] => items().filter(item => !item.disabled);
    const focused = (): Element | null => document.activeElement;

    async function press(target: Element, key: string, init: KeyboardEventInit = {}): Promise<KeyboardEvent> {
        const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
        target.dispatchEvent(event);
        await fixture.whenStable();
        fixture.detectChanges();
        return event;
    }

    /** Enter or Space on a button is a click; with `popovertarget` the platform then toggles the popover. */
    async function clickTrigger(): Promise<void> {
        const target = menu();
        if (popoverIsOpen(target)) target.hidePopover(); else target.showPopover();
        trigger().click();
        await fixture.whenStable();
        fixture.detectChanges();
    }

    async function openWith(key: 'ArrowDown' | 'ArrowUp'): Promise<void> {
        trigger().focus();
        await press(trigger(), key);
    }

    it('is a menu button: haspopup, expanded, controls, and a name from the caller', () => {
        expect(trigger().type).toBe('button');
        expect(trigger().getAttribute('aria-haspopup')).toBe('menu');
        expect(trigger().getAttribute('aria-expanded')).toBe('false');
        expect(trigger().getAttribute('aria-controls')).toBe(menu().id);
        expect(trigger().getAttribute('popovertarget')).toBe(menu().id);
        expect(trigger().getAttribute('aria-label')).toBe('Действия с колодой «Падежи»');
        expect(trigger().querySelector('app-glyph')?.getAttribute('data-glyph')).toBe('more');
        expect(trigger().textContent?.trim()).toBe('');
    });

    it('is a native auto popover with role menu labelled by the button and menuitem buttons', () => {
        expect(menu().getAttribute('popover')).toBe('auto');
        expect(menu().getAttribute('aria-labelledby')).toBe(trigger().id);
        expect(items().map(item => item.textContent?.trim())).toEqual(['Поделиться', 'Скоро', 'Пожаловаться', 'Убрать']);
        for (const item of items()) {
            expect(item.type).toBe('button');
            expect(item.getAttribute('tabindex')).toBe('-1');
        }
        expect(items()[1].disabled).toBe(true);
        expect(items()[3].classList.contains('danger')).toBe(true);
        expect(items()[0].querySelector('app-glyph')?.getAttribute('data-glyph')).toBe('share');
    });

    it('keeps a place for the glyph in rows without one, so labels line up, and only then', () => {
        expect(items().map(item => item.querySelector('.spacer') !== null)).toEqual([false, true, true, true]);
        host.items.set([{ id: 'a', label: 'А' }, { id: 'b', label: 'Б' }]);
        fixture.detectChanges();
        expect(root.querySelector('.spacer')).toBeNull();
    });

    it('gives each menu its own ids and anchor', () => {
        const second = TestBed.createComponent(HostComponent);
        second.detectChanges();
        const other = (second.nativeElement as HTMLElement).querySelector<HTMLElement>('[role=menu]')!;
        expect(other.id).not.toBe(menu().id);
    });

    it('opens on ArrowDown with the first enabled item focused and aria-expanded true', async () => {
        await openWith('ArrowDown');
        expect(popoverIsOpen(menu())).toBe(true);
        expect(trigger().getAttribute('aria-expanded')).toBe('true');
        expect(focused()).toBe(enabled()[0]);
    });

    it('opens on ArrowUp with the last item focused', async () => {
        await openWith('ArrowUp');
        expect(focused()).toBe(enabled().at(-1));
    });

    it('opens by Enter or Space (a click on the trigger) and focuses the first item once the platform reports the toggle', async () => {
        trigger().focus();
        await clickTrigger();
        expect(trigger().getAttribute('aria-expanded')).toBe('true');
        expect(focused()).toBe(enabled()[0]);
    });

    it('does not steal the focus back to the first item after a key already placed it', async () => {
        await openWith('ArrowUp');
        await Promise.resolve();
        expect(focused()).toBe(enabled().at(-1));
    });

    it('ignores arrows with a modifier on the trigger', async () => {
        trigger().focus();
        await press(trigger(), 'ArrowDown', { altKey: true });
        expect(popoverIsOpen(menu())).toBe(false);
    });

    it('moves with ArrowDown and ArrowUp, wraps, and skips the disabled item', async () => {
        await openWith('ArrowDown');
        await press(focused()!, 'ArrowDown');
        expect(focused()).toBe(enabled()[1]);
        expect(focused()).toBe(items()[2]);
        await press(focused()!, 'ArrowDown');
        await press(focused()!, 'ArrowDown');
        expect(focused()).toBe(enabled()[0]);
        await press(focused()!, 'ArrowUp');
        expect(focused()).toBe(enabled().at(-1));
    });

    it('jumps with Home and End', async () => {
        await openWith('ArrowDown');
        await press(focused()!, 'End');
        expect(focused()).toBe(items()[3]);
        await press(focused()!, 'Home');
        expect(focused()).toBe(items()[0]);
    });

    it('prevents the page from scrolling on the keys it handles', async () => {
        await openWith('ArrowDown');
        for (const key of ['ArrowDown', 'ArrowUp', 'Home', 'End']) {
            expect((await press(focused()!, key)).defaultPrevented, key).toBe(true);
        }
    });

    it('jumps to the item that starts with the typed letters', async () => {
        await openWith('ArrowDown');
        await press(focused()!, 'у');
        expect(focused()).toBe(items()[3]);
        vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 5000);
        await press(focused()!, 'п');
        expect(focused()).toBe(items()[0]);
    });

    it('cycles through the items with the same first letter when that letter is repeated', async () => {
        await openWith('ArrowDown');
        await press(focused()!, 'п');
        expect(focused()).toBe(items()[2]);
        await press(focused()!, 'п');
        expect(focused()).toBe(items()[0]);
        await press(focused()!, 'п');
        expect(focused()).toBe(items()[2]);
    });

    it('searches by the typed prefix, not the first letter, when different letters follow', async () => {
        await openWith('ArrowDown');
        await press(focused()!, 'п');
        await press(focused()!, 'о');
        await press(focused()!, 'ж');
        expect(focused()).toBe(items()[2]);
    });

    it('closes on Escape and returns the focus to the trigger', async () => {
        await openWith('ArrowDown');
        const event = await press(focused()!, 'Escape');
        expect(event.defaultPrevented).toBe(true);
        expect(popoverIsOpen(menu())).toBe(false);
        expect(trigger().getAttribute('aria-expanded')).toBe('false');
        expect(focused()).toBe(trigger());
    });

    it('closes on Tab, leaves the focus on the trigger and does not cancel Tab', async () => {
        await openWith('ArrowDown');
        const event = await press(focused()!, 'Tab');
        expect(event.defaultPrevented).toBe(false);
        expect(popoverIsOpen(menu())).toBe(false);
        expect(focused()).toBe(trigger());
    });

    it('closes, returns the focus and reports the id on activation', async () => {
        await openWith('ArrowDown');
        items()[2].click();
        fixture.detectChanges();
        expect(host.picked).toEqual(['report']);
        expect(popoverIsOpen(menu())).toBe(false);
        expect(trigger().getAttribute('aria-expanded')).toBe('false');
        expect(focused()).toBe(trigger());
    });

    it('does not report a disabled item', async () => {
        await openWith('ArrowDown');
        items()[1].click();
        expect(host.picked).toEqual([]);
    });

    it('follows the platform closing the menu by light dismiss', async () => {
        await openWith('ArrowDown');
        menu().hidePopover();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(trigger().getAttribute('aria-expanded')).toBe('false');
    });

    it('stays harmless with no focusable item', async () => {
        host.items.set([{ id: 'a', label: 'Нельзя', disabled: true }]);
        fixture.detectChanges();
        await openWith('ArrowDown');
        await press(menu(), 'ArrowDown');
        expect(focused()).toBe(trigger());
    });

    it('keeps working when the browser has no Popover API', async () => {
        removePopoverShim();
        trigger().focus();
        await press(trigger(), 'ArrowDown');
        expect(focused()).toBe(enabled()[0]);
        await press(focused()!, 'Escape');
        expect(focused()).toBe(trigger());
    });
});
