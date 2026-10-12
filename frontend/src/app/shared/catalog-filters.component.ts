import { DOCUMENT } from '@angular/common';
import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterRenderEffect, computed, inject, input, output, signal, viewChild
} from '@angular/core';

import { MnemaSelectComponent, MnemaSelectOption } from '../core/controls/mnema-select.component';
import {
    CATALOG_LEVELS, CATALOG_MECHANICS, CATALOG_MEDIA, CATALOG_SIZES, CATALOG_SORTS, CatalogFilters, CatalogMechanic, CatalogMedia, CatalogSize, CatalogSort,
    FilterRef, LanguageOption, TopicOption, activeCount, withoutFilter, withoutFilters
} from './catalog-filters';
import { SegmentedChoiceComponent, SegmentedOption } from './segmented-choice.component';

let nextFilters = 0;
/** How long a request to move the focus after a removed chip waits for the new value to arrive. */
const FOCUS_REQUEST_MS = 2000;

/** `text` is what the chip says («Уровень: B1», «Внутри: аудио»), `name` what a screen reader hears. The name holds the text, so it stays valid for voice control. */
interface FilterChip { readonly id: string; readonly text: string; readonly ref: FilterRef }

const ANY_SIZE = 'any';
const SORT_OPTIONS: readonly SegmentedOption<CatalogSort>[] = CATALOG_SORTS.map(({ value, label }) => ({ value, label }));
const SIZE_OPTIONS: readonly SegmentedOption[] = [{ value: ANY_SIZE, label: 'Любой' }, ...CATALOG_SIZES.map(({ value, label, hint }) => ({ value, label, hint }))];

/**
 * The filters and the sorting of the community catalogue: a pure value in (`value`), a new value out (`changed`); the page owns
 * the value, keeps it in the address (`toQueryParams`) and reloads the results. Built from the existing controls: `app-mnema-select`
 * for «Тема» (grouped by the parent topic), «Что учим» and «Уровень», `app-segmented-choice` for «Размер» and for the sorting,
 * `.check-row` boxes for «Внутри», «Упражнения» and «Живая».
 *
 * On a narrow container the controls sit behind one button «Фильтры» (or «Фильтры · 3» with the number that is on) that carries
 * `aria-expanded`; on a wide one they are a wrapped grid and the button is not drawn. The sorting is always in view. Every filter that is
 * on is also a chip that says its category («Уровень: B1», «Внутри: аудио»), a button named «Убрать фильтр «Уровень: B1»»; «Сбросить всё» removes them all and leaves the sorting alone. After a chip is
 * removed the focus goes to the next chip, or to the «Фильтры» button when none is left. With `sortDisabled` (a text search is ordered by closeness to the query)
 * the sorting is drawn disabled and a line under it says why.
 */
@Component({
    selector: 'app-catalog-filters',
    imports: [MnemaSelectComponent, SegmentedChoiceComponent],
    host: { '(document:focusin)': 'focusMoved($event)' },
    template: `
      <div class="filters">
        <div class="bar">
          <button #toggle type="button" class="button disclosure" [attr.aria-expanded]="open()" [attr.aria-controls]="panelId"
            (click)="open.set(!open())">{{ toggleLabel() }}</button>
          <app-segmented-choice class="sort" legend="Сортировка" [options]="sortOptions" [value]="value().sort" [disabled]="sortDisabled()"
            [describedBy]="sortDisabled() ? sortNoteId : null" (valueChange)="setSort($event)" />
        </div>
        @if (sortDisabled()) { <p class="hint sort-note" [id]="sortNoteId">Найденные колоды идут по близости к запросу.</p> }
        <div class="panel" [id]="panelId" [class.is-open]="open()">
          <div class="grid">
            <div class="field">
              <label [for]="topicId">Тема</label>
              <app-mnema-select [controlId]="topicId" label="Тема" [options]="topicOptions()" [value]="value().topic ?? ''" (valueChange)="setTopic($event)" />
            </div>
            <div class="field">
              <label [for]="learnId">Что учим</label>
              <app-mnema-select [controlId]="learnId" label="Что учим" [options]="languageOptions()" [value]="value().learn ?? ''" (valueChange)="setLearn($event)" />
            </div>
            <div class="field">
              <label [for]="levelId">Уровень</label>
              <app-mnema-select [controlId]="levelId" label="Уровень" [options]="levelOptions" [value]="value().level ?? ''" (valueChange)="setLevel($event)" />
            </div>
            <app-segmented-choice legend="Размер" [options]="sizeOptions" [value]="value().size ?? 'any'" (valueChange)="setSize($event)" />
            <fieldset class="check-group">
              <legend>Внутри</legend>
              @for (item of media; track item.value) {
                <label class="check-row"><input type="checkbox" [checked]="value().media.includes(item.value)" (change)="toggleMedia(item.value, $event)" /><span>{{ item.label }}</span></label>
              }
            </fieldset>
            <fieldset class="check-group">
              <legend>Упражнения</legend>
              @for (item of mechanics; track item.value) {
                <label class="check-row"><input type="checkbox" [checked]="value().mechanics.includes(item.value)" (change)="toggleMechanic(item.value, $event)" /><span>{{ item.label }}</span></label>
              }
            </fieldset>
            <div class="check-field">
              <label class="check-row"><input type="checkbox" [attr.aria-describedby]="freshHintId" [checked]="value().fresh" (change)="setFresh($event)" /><span>Живая</span></label>
              <p class="hint" [id]="freshHintId">Обновлялась за последний месяц</p>
            </div>
          </div>
        </div>
        @if (chips().length > 0) {
          <div class="active">
            <ul class="chips" role="list" aria-label="Выбранные фильтры">
              @for (chip of chips(); track chip.id; let index = $index) {
                <li><button type="button" class="button small chip" [attr.aria-label]="'Убрать фильтр «' + chip.text + '»'" (click)="remove(chip, index)"><span>{{ chip.text }}</span><span aria-hidden="true">×</span></button></li>
              }
            </ul>
            <button type="button" class="button quiet small" (click)="reset()">Сбросить всё</button>
          </div>
        }
      </div>
    `,
    styleUrl: './catalog-filters.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class CatalogFiltersComponent {
    readonly value = input.required<CatalogFilters>();
    /** The topics to choose from, parents and children; a child names its parent and is drawn under its heading. */
    readonly topics = input<readonly TopicOption[]>([]);
    readonly languages = input<readonly LanguageOption[]>([]);
    /** The sorting does not apply (a text search orders by closeness to the query): the control is drawn disabled, with a line that says why. */
    readonly sortDisabled = input(false);
    /** The new value; the sorting alone changing is also a new value. */
    readonly changed = output<CatalogFilters>();

    protected readonly sortOptions = SORT_OPTIONS;
    protected readonly sizeOptions = SIZE_OPTIONS;
    protected readonly media = CATALOG_MEDIA;
    protected readonly mechanics = CATALOG_MECHANICS;
    protected readonly open = signal(false);

    private readonly uid = `mn-filters-${nextFilters++}`;
    protected readonly panelId = `${this.uid}-panel`;
    protected readonly sortNoteId = `${this.uid}-sort-note`;
    protected readonly topicId = `${this.uid}-topic`;
    protected readonly learnId = `${this.uid}-learn`;
    protected readonly levelId = `${this.uid}-level`;
    protected readonly freshHintId = `${this.uid}-fresh-hint`;

    protected readonly levelOptions: readonly MnemaSelectOption[] = [{ value: '', label: 'Любой' }, ...CATALOG_LEVELS];
    protected readonly topicOptions = computed<readonly MnemaSelectOption[]>(() => [
        { value: '', label: 'Любая' },
        ...this.topics().map(topic => ({ value: topic.id, label: topic.name, group: topic.parentName }))
    ]);
    protected readonly languageOptions = computed<readonly MnemaSelectOption[]>(() => [
        { value: '', label: 'Любой' },
        ...this.languages().map(language => ({ value: language.code, label: language.name }))
    ]);
    protected readonly toggleLabel = computed(() => {
        const count = activeCount(this.value());
        return count > 0 ? `Фильтры · ${count}` : 'Фильтры';
    });
    protected readonly chips = computed<readonly FilterChip[]>(() => {
        const filters = this.value();
        const chips: FilterChip[] = [];
        const low = (text: string): string => text.toLocaleLowerCase('ru');
        if (filters.topic !== null) chips.push({ id: 'topic', text: `Тема: ${this.topics().find(topic => topic.id === filters.topic)?.name ?? filters.topic}`, ref: { kind: 'topic' } });
        if (filters.learn !== null) chips.push({ id: 'learn', text: `Что учим: ${this.languages().find(language => language.code === filters.learn)?.name ?? filters.learn}`, ref: { kind: 'learn' } });
        if (filters.level !== null) chips.push({ id: 'level', text: `Уровень: ${CATALOG_LEVELS.find(level => level.value === filters.level)?.label ?? filters.level}`, ref: { kind: 'level' } });
        if (filters.size !== null) chips.push({ id: 'size', text: `Размер: ${low(CATALOG_SIZES.find(size => size.value === filters.size)?.label ?? filters.size)}`, ref: { kind: 'size' } });
        for (const item of CATALOG_MEDIA) if (filters.media.includes(item.value)) chips.push({ id: `media:${item.value}`, text: `Внутри: ${low(item.label)}`, ref: { kind: 'media', value: item.value } });
        for (const item of CATALOG_MECHANICS) if (filters.mechanics.includes(item.value)) chips.push({ id: `mechanics:${item.value}`, text: `Упражнения: ${low(item.label)}`, ref: { kind: 'mechanics', value: item.value } });
        if (filters.fresh) chips.push({ id: 'fresh', text: 'Живая', ref: { kind: 'fresh' } });
        return chips;
    });

    private readonly toggle = viewChild.required<ElementRef<HTMLButtonElement>>('toggle');
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly document = inject(DOCUMENT);
    /**
     * Where the focus goes once the new value has arrived: the chip now at `index`, or the button when `index` is -1. It is keyed to the chips
     * that were removed (it acts only when they are really gone) and it lapses when the focus moves to any control outside the chips, so a
     * slow parent can never take the focus from where the reader went meanwhile.
     */
    private focusRequest: { readonly index: number; readonly removed: readonly string[] } | null = null;
    private requestTimer: ReturnType<typeof setTimeout> | null = null;

    constructor() {
        inject(DestroyRef).onDestroy(() => this.clearRequest());
        // The value is owned by the page and may arrive late (through the address bar): act when the chips have really changed.
        afterRenderEffect(() => {
            const chips = this.chips();
            const request = this.focusRequest;
            if (request === null || request.removed.some(id => chips.some(chip => chip.id === id))) return;
            this.clearRequest();
            this.placeFocus(request.index);
        });
    }

    protected setSort(sort: CatalogSort | null): void {
        if (sort !== null && sort !== this.value().sort) this.changed.emit({ ...this.value(), sort });
    }
    protected setTopic(id: string): void { this.changed.emit({ ...this.value(), topic: id === '' ? null : id }); }
    protected setLearn(code: string): void { this.changed.emit({ ...this.value(), learn: code === '' ? null : code }); }
    protected setLevel(level: string): void { this.changed.emit({ ...this.value(), level: level === '' ? null : level }); }
    protected setSize(size: string | null): void {
        const next = CATALOG_SIZES.find(item => item.value === size)?.value ?? null;
        this.changed.emit({ ...this.value(), size: next as CatalogSize | null });
    }
    protected setFresh(event: Event): void { this.changed.emit({ ...this.value(), fresh: (event.target as HTMLInputElement).checked }); }

    protected toggleMedia(kind: CatalogMedia, event: Event): void {
        const on = (event.target as HTMLInputElement).checked;
        const rest = this.value().media.filter(value => value !== kind);
        this.changed.emit({ ...this.value(), media: CATALOG_MEDIA.map(item => item.value).filter(value => value === kind ? on : rest.includes(value)) });
    }

    protected toggleMechanic(kind: CatalogMechanic, event: Event): void {
        const on = (event.target as HTMLInputElement).checked;
        const rest = this.value().mechanics.filter(value => value !== kind);
        this.changed.emit({ ...this.value(), mechanics: CATALOG_MECHANICS.map(item => item.value).filter(value => value === kind ? on : rest.includes(value)) });
    }

    protected remove(chip: FilterChip, index: number): void {
        this.request(index, [chip.id]);
        this.changed.emit(withoutFilter(this.value(), chip.ref));
    }

    protected reset(): void {
        this.request(-1, this.chips().map(chip => chip.id));
        this.changed.emit(withoutFilters(this.value()));
    }

    /** The focus went somewhere else than the chips (a control the reader chose): the request is void. */
    protected focusMoved(event: FocusEvent): void {
        if (this.focusRequest === null || !(event.target instanceof Element)) return;
        if (!event.target.closest('.chips')) this.clearRequest();
    }

    private request(index: number, removed: readonly string[]): void {
        this.clearRequest();
        this.focusRequest = { index, removed };
        this.requestTimer = setTimeout(() => this.clearRequest(), FOCUS_REQUEST_MS);
    }

    private clearRequest(): void {
        if (this.requestTimer !== null) clearTimeout(this.requestTimer);
        this.requestTimer = null;
        this.focusRequest = null;
    }

    /** The chip that took the place of the removed one, else the last chip, else the button (or the first control where the button is not drawn). */
    private placeFocus(index: number): void {
        const chips = [...this.host.nativeElement.querySelectorAll<HTMLButtonElement>('.chip')];
        if (index >= 0 && chips.length > 0) { chips[Math.min(index, chips.length - 1)].focus(); return; }
        const toggle = this.toggle().nativeElement;
        toggle.focus();
        if (this.document.activeElement !== toggle) this.host.nativeElement.querySelector<HTMLElement>('.panel button, .panel input')?.focus();
    }
}
