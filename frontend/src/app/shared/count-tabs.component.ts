import { ChangeDetectionStrategy, Component, ElementRef, input, model, viewChildren } from '@angular/core';

/** One tab of {@link CountTabsComponent}: the value it selects, its words and how many items it holds. */
export interface CountTab<T extends string = string> {
    readonly value: T;
    readonly label: string;
    readonly count: number;
}

let nextTabs = 0;

/**
 * Tabs with a count each («Конфликты 2»), the [APG tabs pattern](https://www.w3.org/WAI/ARIA/apg/patterns/tabs/) with automatic
 * activation: `tablist` of `tab` buttons, arrows move the focus and select, Home and End jump to the ends, only the selected tab is
 * in the Tab order (roving `tabindex`). The panel is the page's: put `role="tabpanel"`, `[id]="tabs.panelId(value)"` and
 * `[attr.aria-labelledby]="tabs.tabId(value)"` on the element that shows the selected list, where `tabs` is a template reference to
 * this component. The selected tab shows it with a thicker rule and bold text as well as the colour. The count is part of the name
 * («Конфликты: 2»). A tab with zero items is still drawn; the page decides whether to leave an empty class out.
 */
@Component({
    selector: 'app-count-tabs',
    template: `
      <div class="tablist" role="tablist" [attr.aria-label]="label()">
        @for (tab of tabs(); track tab.value) {
          <button #tab type="button" role="tab" class="tab" [id]="tabId(tab.value)" [attr.aria-selected]="tab.value === value() ? 'true' : 'false'"
            [attr.aria-controls]="tab.value === value() ? panelId(tab.value) : null" [attr.tabindex]="tab.value === value() ? 0 : -1"
            (click)="value.set(tab.value)" (keydown)="move($event, $index)">
            <span class="name">{{ tab.label }}</span><span class="visually-hidden">: </span><span class="count">{{ tab.count }}</span>
          </button>
        }
      </div>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .tablist { display: flex; flex-wrap: wrap; gap: 0 1.25rem; border-block-end: 1px solid var(--mn-rule); }
      .tab { display: inline-flex; flex-wrap: wrap; align-items: baseline; gap: .1rem .5rem; min-block-size: var(--mn-touch-min); margin-block-end: -1px; border: 0; border-block-end: 1px solid transparent; padding: .4rem .1rem; background: none; color: var(--mn-body); font: 600 1rem/1.3 var(--mn-font-body, system-ui, sans-serif); text-align: start; cursor: pointer; }
      .tab[aria-selected='true'] { border-block-end: 4px solid var(--mn-ink); color: var(--mn-ink); font-weight: 750; }
      .tab:not([aria-selected='true']):hover { border-block-end-color: var(--mn-ink); }
      .tab:focus-visible { outline: 3px solid var(--mn-focus, var(--mn-ink)); outline-offset: 2px; }
      .count { color: var(--mn-muted); font: 700 .8rem/1.3 var(--mn-font-mono); }
      .tab[aria-selected='true'] .count { color: var(--mn-ink); }
      @media (forced-colors: active) {
        .tablist { border-block-end-color: CanvasText; }
        .tab { border-block-end-color: Canvas; color: ButtonText; }
        .tab[aria-selected='true'] { border-block-end-color: Highlight; }
        .tab:focus-visible { outline-color: Highlight; }
        .count, .tab[aria-selected='true'] .count { color: ButtonText; }
      }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class CountTabsComponent<T extends string = string> {
    readonly tabs = input.required<readonly CountTab<T>[]>();
    /** The name of the tab list for assistive technology («Классы изменений»). */
    readonly label = input.required<string>();
    readonly value = model.required<T>();

    private readonly buttons = viewChildren<ElementRef<HTMLButtonElement>>('tab');
    private readonly uid = `mn-tabs-${nextTabs++}`;

    tabId(value: T): string { return `${this.uid}-tab-${value}`; }
    panelId(value: T): string { return `${this.uid}-panel-${value}`; }

    protected move(event: KeyboardEvent, index: number): void {
        const count = this.tabs().length;
        const next = event.key === 'ArrowRight' ? (index + 1) % count : event.key === 'ArrowLeft' ? (index - 1 + count) % count
            : event.key === 'Home' ? 0 : event.key === 'End' ? count - 1 : -1;
        if (next < 0 || event.altKey || event.ctrlKey || event.metaKey) return;
        event.preventDefault();
        this.value.set(this.tabs()[next]!.value);
        this.buttons()[next]?.nativeElement.focus();
    }
}
