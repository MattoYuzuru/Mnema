import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input, output, signal, viewChild } from '@angular/core';

import { GlyphComponent, GlyphName } from './glyph.component';

/** One row of an {@link ActionMenuComponent}. `id` is what the menu reports back when the row is chosen. */
export interface ActionMenuItem {
    readonly id: string;
    readonly label: string;
    readonly glyph?: GlyphName;
    readonly disabled?: boolean;
    /** A destructive or sensitive action: drawn in the danger colour (the label still says what it does). */
    readonly danger?: boolean;
}

let nextMenu = 0;
/** Typed characters count as one search until this long passes without a key. */
const TYPEAHEAD_MS = 700;

/**
 * The «⋯» button with its menu of actions, after the WAI-ARIA APG menu button pattern. The trigger is a button with
 * `aria-haspopup="menu"`, `aria-expanded` and `aria-controls`; its accessible name comes from the caller (`label`, for
 * example «Действия с колодой «Падежи»»), because «⋯» says nothing by itself. The menu is a native `popover="auto"` in
 * the top layer, so the platform provides light dismiss and the layering; the menu items are `role="menuitem"` buttons.
 *
 * Keyboard. On the trigger: Enter and Space open the menu and focus the first item, ArrowDown does the same, ArrowUp
 * opens it on the last. In the menu: ArrowDown and ArrowUp move (and wrap), Home and End jump to the ends, typing
 * letters jumps to the item that starts with them (the same letter again goes to the next such item), Esc closes and returns focus to the trigger, Tab closes and lets the
 * focus move on from the trigger, activating an item closes the menu, returns focus to the trigger and reports the
 * choice. Disabled items are skipped. Nothing depends on hover. Next to the trigger the menu is placed by CSS anchor
 * positioning; without it the browser centres it in the window.
 */
@Component({
    selector: 'app-action-menu',
    imports: [GlyphComponent],
    template: `
      <button #trigger type="button" class="trigger" aria-haspopup="menu" [id]="triggerId" [attr.aria-label]="label()"
        [attr.aria-expanded]="open()" [attr.aria-controls]="menuId" [attr.popovertarget]="menuId" [style.anchor-name]="anchorName"
        (keydown)="triggerKey($event)">
        <app-glyph name="more" />
      </button>
      <div #menu class="menu" role="menu" popover="auto" [id]="menuId" [attr.aria-labelledby]="triggerId"
        [style.position-anchor]="anchorName" (toggle)="toggled($event)" (keydown)="menuKey($event)">
        @for (item of items(); track item.id) {
          <button type="button" role="menuitem" class="item" tabindex="-1" [class.danger]="item.danger === true"
            [disabled]="item.disabled === true" (click)="choose(item)">
            @if (item.glyph; as glyph) { <app-glyph [name]="glyph" /> } @else if (hasGlyphs()) { <span class="spacer" aria-hidden="true"></span> }
            <span>{{ item.label }}</span>
          </button>
        }
      </div>
    `,
    styleUrl: './action-menu.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ActionMenuComponent {
    /** The accessible name of the «⋯» button: what the actions are about. */
    readonly label = input.required<string>();
    readonly items = input.required<readonly ActionMenuItem[]>();
    /** The `id` of the chosen item. The menu is already closed and the focus is back on the trigger. */
    readonly chosen = output<string>();

    private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
    private readonly menu = viewChild.required<ElementRef<HTMLElement>>('menu');
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

    private readonly uid = `mn-menu-${nextMenu++}`;
    protected readonly triggerId = `${this.uid}-trigger`;
    protected readonly menuId = `${this.uid}-menu`;
    protected readonly anchorName = `--${this.uid}`;
    protected readonly open = signal(false);
    /** When some rows carry a glyph, the others keep its place so the labels line up. */
    protected readonly hasGlyphs = computed(() => this.items().some(item => item.glyph !== undefined));

    private search = '';
    private searchAt = 0;

    protected triggerKey(event: KeyboardEvent): void {
        if (event.altKey || event.ctrlKey || event.metaKey) return;
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            this.show(event.key === 'ArrowDown' ? 'first' : 'last');
        }
    }

    /** Fired by the platform when the menu opens or closes by any route: the trigger, light dismiss, Esc. */
    protected toggled(event: Event): void {
        const opened = (event as ToggleEvent).newState === 'open';
        this.open.set(opened);
        // Enter, Space or a click on the trigger opened it: the first item takes the focus (unless a key already placed it).
        if (opened && !this.menu().nativeElement.contains(this.activeElement())) this.focusAt('first');
    }

    protected menuKey(event: KeyboardEvent): void {
        if (event.altKey || event.ctrlKey || event.metaKey) return;
        switch (event.key) {
            case 'ArrowDown': event.preventDefault(); this.move(1); break;
            case 'ArrowUp': event.preventDefault(); this.move(-1); break;
            case 'Home': event.preventDefault(); this.focusAt('first'); break;
            case 'End': event.preventDefault(); this.focusAt('last'); break;
            case 'Escape': event.preventDefault(); event.stopPropagation(); this.close(); break;
            // The default Tab then moves on from the trigger, which is where the focus has just been put back.
            case 'Tab': this.close(); break;
            default: if (/^[\p{L}\p{N}]$/u.test(event.key)) this.typeahead(event.key);
        }
    }

    protected choose(item: ActionMenuItem): void {
        if (item.disabled === true) return;
        this.close();
        this.chosen.emit(item.id);
    }

    private show(at: 'first' | 'last'): void {
        const menu = this.menu().nativeElement;
        if (!this.open() && typeof menu.showPopover === 'function') {
            try { menu.showPopover(); } catch { /* already open */ }
            this.open.set(true);
        }
        this.focusAt(at);
    }

    private close(): void {
        const menu = this.menu().nativeElement;
        if (typeof menu.hidePopover === 'function') {
            try { menu.hidePopover(); } catch { /* already closed */ }
        }
        this.open.set(false);
        this.trigger().nativeElement.focus();
    }

    private enabledItems(): HTMLButtonElement[] {
        return [...this.menu().nativeElement.querySelectorAll<HTMLButtonElement>('[role=menuitem]:not(:disabled)')];
    }

    private focusAt(at: 'first' | 'last'): void {
        const items = this.enabledItems();
        items.at(at === 'first' ? 0 : -1)?.focus();
    }

    private move(step: 1 | -1): void {
        const items = this.enabledItems();
        if (items.length === 0) return;
        const current = items.indexOf(this.activeElement() as HTMLButtonElement);
        const next = current === -1 ? (step === 1 ? 0 : items.length - 1) : (current + step + items.length) % items.length;
        items[next].focus();
    }

    private typeahead(key: string): void {
        const now = Date.now();
        this.search = now - this.searchAt > TYPEAHEAD_MS ? key.toLowerCase() : this.search + key.toLowerCase();
        this.searchAt = now;
        // The same letter again and again (APG) is not the prefix «пп…» but a request for the next item with that letter.
        const prefix = [...this.search].every(letter => letter === this.search[0]) ? this.search[0] : this.search;
        const items = this.enabledItems();
        const current = items.indexOf(this.activeElement() as HTMLButtonElement);
        // Search from the item after the focused one and wrap, so the focused item is the last candidate.
        const ordered = [...items.slice(current + 1), ...items.slice(0, current + 1)];
        ordered.find(item => (item.textContent ?? '').trim().toLowerCase().startsWith(prefix))?.focus();
    }

    private activeElement(): Element | null {
        return this.host.nativeElement.ownerDocument.activeElement;
    }
}
