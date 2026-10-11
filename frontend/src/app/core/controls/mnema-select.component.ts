import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, inject, input, output, signal } from '@angular/core';

export interface MnemaSelectOption {
    readonly value: string;
    readonly label: string;
    readonly disabled?: boolean;
    /**
     * The heading of the group this option belongs to. Consecutive options with the same `group` are drawn together under one
     * heading (`role="group"` named by it); the heading is text, not an option, so arrows, Home, End and typeahead never land on it.
     */
    readonly group?: string;
}

interface SelectSection {
    readonly key: number;
    readonly label: string | null;
    readonly entries: readonly { readonly option: MnemaSelectOption; readonly index: number }[];
}

/** A select-only combobox. Focus stays on the trigger while aria-activedescendant explores the popup. */
@Component({
    selector: 'app-mnema-select',
    imports: [NgTemplateOutlet],
    templateUrl: './mnema-select.component.html',
    styleUrl: './mnema-select.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {
        '[class.is-open]': 'open()',
        '[class.is-compact]': 'compact()',
        '(document:pointerdown)': 'onDocumentPointerDown($event)',
        '(document:focusin)': 'onDocumentFocusIn($event)'
    }
})
export class MnemaSelectComponent {
    readonly controlId = input.required<string>();
    readonly label = input.required<string>();
    readonly options = input.required<readonly MnemaSelectOption[]>();
    readonly value = input<string>('');
    readonly disabled = input(false);
    readonly invalid = input(false);
    readonly compact = input(false);
    readonly answerControl = input(false);
    /** Ids of elements that describe the control (a state, a hint); they are announced after its name. */
    readonly describedBy = input<string | null>(null);
    readonly valueChange = output<string>();

    readonly open = signal(false);
    readonly activeIndex = signal(-1);
    readonly selectedLabel = computed(() => this.options().find(option => option.value === this.value())?.label ?? 'Выберите вариант');
    /** The options in order, split into runs of the same group; an option without a group stands alone. */
    readonly sections = computed<readonly SelectSection[]>(() => {
        const sections: { key: number; label: string | null; entries: { option: MnemaSelectOption; index: number }[] }[] = [];
        this.options().forEach((option, index) => {
            const label = option.group ?? null;
            const last = sections[sections.length - 1];
            if (last !== undefined && last.label === label && label !== null) last.entries.push({ option, index });
            else sections.push({ key: sections.length, label, entries: [{ option, index }] });
        });
        return sections;
    });
    readonly activeOptionId = computed(() => this.open() && this.activeIndex() >= 0
        ? `${this.controlId()}-option-${this.activeIndex()}` : null);

    private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly document = inject(DOCUMENT);
    private readonly destroyRef = inject(DestroyRef);
    private typed = '';
    private typeTimer: ReturnType<typeof setTimeout> | null = null;

    constructor() {
        this.destroyRef.onDestroy(() => this.clearTypeahead());
    }

    toggle(): void {
        if (this.disabled()) return;
        if (this.open()) this.close();
        else this.show();
    }

    onKeydown(event: KeyboardEvent): void {
        if (this.disabled()) return;
        switch (event.key) {
            case 'ArrowDown':
                event.preventDefault();
                if (!this.open()) this.show();
                else this.move(1);
                return;
            case 'ArrowUp':
                event.preventDefault();
                if (!this.open()) this.show();
                else this.move(-1);
                return;
            case 'Home':
            case 'End':
                event.preventDefault();
                if (!this.open()) this.show();
                this.activate(event.key === 'Home' ? this.firstEnabled() : this.lastEnabled());
                return;
            case 'Enter':
            case ' ':
                event.preventDefault();
                if (this.open()) this.choose(this.activeIndex());
                else this.show();
                return;
            case 'Escape':
                if (this.open()) { event.preventDefault(); this.close(); }
                return;
            case 'Tab':
                this.close();
                return;
            default:
                if (event.key.length === 1 && !event.altKey && !event.ctrlKey && !event.metaKey) {
                    this.typeahead(event.key);
                }
        }
    }

    choose(index: number): void {
        const option = this.options()[index];
        if (!option || option.disabled) return;
        if (option.value !== this.value()) this.valueChange.emit(option.value);
        this.close();
    }

    onDocumentPointerDown(event: PointerEvent): void {
        if (this.open() && !this.element.nativeElement.contains(event.target as Node)) this.close();
    }

    onDocumentFocusIn(event: FocusEvent): void {
        if (this.open() && !this.element.nativeElement.contains(event.target as Node)) this.close();
    }

    private show(): void {
        if (this.options().every(option => option.disabled)) return;
        const selected = this.options().findIndex(option => option.value === this.value() && !option.disabled);
        this.activeIndex.set(selected >= 0 ? selected : this.firstEnabled());
        this.open.set(true);
        this.scrollActiveIntoView();
    }

    private close(): void {
        this.open.set(false);
        this.activeIndex.set(-1);
        this.clearTypeahead();
    }

    private move(direction: number): void {
        const options = this.options();
        if (options.length === 0) return;
        for (let step = 1; step <= options.length; step++) {
            const index = (this.activeIndex() + direction * step + options.length * (step + 1)) % options.length;
            if (!options[index].disabled) { this.activate(index); return; }
        }
    }

    private activate(index: number): void {
        if (index < 0) return;
        this.activeIndex.set(index);
        this.scrollActiveIntoView();
    }

    private firstEnabled(): number { return this.options().findIndex(option => !option.disabled); }
    private lastEnabled(): number {
        const options = this.options();
        for (let index = options.length - 1; index >= 0; index--) {
            if (!options[index].disabled) return index;
        }
        return -1;
    }

    private typeahead(character: string): void {
        const query = (this.typed + character).toLocaleLowerCase();
        const options = this.options();
        const start = this.open() ? this.activeIndex() : options.findIndex(option => option.value === this.value());
        const match = (term: string) => {
            for (let step = 1; step <= options.length; step++) {
                const index = (start + step + options.length) % options.length;
                if (!options[index].disabled && options[index].label.toLocaleLowerCase().startsWith(term)) return index;
            }
            return -1;
        };
        const index = match(query) >= 0 ? match(query) : match(character.toLocaleLowerCase());
        if (index < 0) return;
        if (!this.open()) this.show();
        this.activate(index);
        this.typed = query;
        this.clearTypeaheadTimer();
        this.typeTimer = setTimeout(() => { this.typed = ''; this.typeTimer = null; }, 700);
    }

    private scrollActiveIntoView(): void {
        const id = `${this.controlId()}-option-${this.activeIndex()}`;
        requestAnimationFrame(() => this.document.getElementById(id)?.scrollIntoView({ block: 'nearest' }));
    }

    private clearTypeahead(): void {
        this.clearTypeaheadTimer();
        this.typed = '';
    }

    private clearTypeaheadTimer(): void {
        if (this.typeTimer !== null) clearTimeout(this.typeTimer);
        this.typeTimer = null;
    }
}
