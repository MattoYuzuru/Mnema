import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { CatalogFilters, LanguageOption, NO_FILTERS, TopicOption } from './catalog-filters';
import { CatalogFiltersComponent } from './catalog-filters.component';

const TOPICS: readonly TopicOption[] = [
    { id: 'languages', name: 'Языки' },
    { id: 'spanish', name: 'Испанский язык', parentName: 'Языки' },
    { id: 'japanese', name: 'Японский язык', parentName: 'Языки' },
    { id: 'science', name: 'Наука' },
    { id: 'anatomy', name: 'Анатомия', parentName: 'Наука' }
];
const LANGUAGES: readonly LanguageOption[] = [{ code: 'es', name: 'Испанский' }, { code: 'ja', name: 'Японский' }];

@Component({
    imports: [CatalogFiltersComponent],
    template: `<app-catalog-filters [value]="value()" [topics]="topics" [languages]="languages" (changed)="onChanged($event)" />`
})
class HostComponent {
    readonly value = signal<CatalogFilters>(NO_FILTERS);
    readonly topics = TOPICS;
    readonly languages = LANGUAGES;
    readonly emitted: CatalogFilters[] = [];
    /** The page applies the new value at once; a spec may switch that off. */
    apply = true;
    onChanged(next: CatalogFilters): void { this.emitted.push(next); if (this.apply) this.value.set(next); }
}

describe('CatalogFiltersComponent', () => {
    let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
    let host: HostComponent;
    let root: HTMLElement;

    const create = (value: CatalogFilters = NO_FILTERS): void => {
        fixture = TestBed.createComponent(HostComponent);
        host = fixture.componentInstance;
        host.value.set(value);
        root = fixture.nativeElement as HTMLElement;
        fixture.detectChanges();
    };
    const toggle = (): HTMLButtonElement => root.querySelector<HTMLButtonElement>('button.disclosure')!;
    const chips = (): HTMLButtonElement[] => [...root.querySelectorAll<HTMLButtonElement>('.chip')];
    const chipNames = (): (string | null)[] => chips().map(chip => chip.getAttribute('aria-label'));
    const last = (): CatalogFilters => host.emitted[host.emitted.length - 1];
    const checkbox = (name: string): HTMLInputElement =>
        [...root.querySelectorAll<HTMLLabelElement>('.check-row')].find(label => label.textContent?.trim() === name)!.querySelector('input')!;
    const tick = (box: HTMLInputElement, checked: boolean): void => { box.checked = checked; box.dispatchEvent(new Event('change', { bubbles: true })); fixture.detectChanges(); };
    const radio = (group: string, label: string): HTMLInputElement =>
        [...[...root.querySelectorAll<HTMLElement>('app-segmented-choice')].find(item => item.querySelector('legend')?.textContent === group)!
            .querySelectorAll<HTMLLabelElement>('label.segment')].find(item => item.textContent?.trim() === label)!.querySelector('input')!;
    const pick = (group: string, label: string): void => { const input = radio(group, label); input.checked = true; input.dispatchEvent(new Event('change', { bubbles: true })); fixture.detectChanges(); };
    const select = (name: string): HTMLButtonElement => root.querySelector<HTMLButtonElement>(`button.select-trigger[aria-label^="${name}:"]`)!;

    describe('layout', () => {
        it('has one disclosure button that controls the panel and reports its state', () => {
            create();
            expect(toggle().textContent?.trim()).toBe('Фильтры');
            expect(toggle().getAttribute('aria-expanded')).toBe('false');
            expect(toggle().getAttribute('aria-controls')).toBe(root.querySelector('.panel')!.id);
            expect(root.querySelector('.panel')!.classList.contains('is-open')).toBe(false);
            toggle().click();
            fixture.detectChanges();
            expect(toggle().getAttribute('aria-expanded')).toBe('true');
            expect(root.querySelector('.panel')!.classList.contains('is-open')).toBe(true);
        });

        it('shows the number of filters that are on, and not the sorting', () => {
            create({ ...NO_FILTERS, sort: 'new', learn: 'es', media: ['audio', 'video'] });
            expect(toggle().textContent?.trim()).toBe('Фильтры · 3');
        });

        it('offers the existing controls with visible labels', () => {
            create();
            const labels = [...root.querySelectorAll('.field > label')].map(label => label.textContent);
            expect(labels).toEqual(['Тема', 'Что учим', 'Уровень']);
            expect(root.querySelectorAll('app-mnema-select')).toHaveLength(3);
            expect([...root.querySelectorAll('fieldset.check-group > legend')].map(legend => legend.textContent)).toEqual(['Внутри', 'Упражнения']);
            expect([...root.querySelectorAll('app-segmented-choice legend')].map(legend => legend.textContent)).toEqual(['Сортировка', 'Размер']);
            expect([...root.querySelectorAll('.check-row')].map(row => row.textContent?.trim()))
                .toEqual(['Аудио', 'Картинки', 'Видео', 'На слух', 'Сопоставление', 'Письменный ответ', 'Выбор варианта', 'По порядку', 'Живая']);
            expect(root.querySelector<HTMLInputElement>('.check-field input')!.getAttribute('aria-describedby')).toBe(root.querySelector('.check-field .hint')!.id);
        });

        it('draws the four sortings and no «Для вас»', () => {
            create();
            const sort = [...root.querySelectorAll('app-segmented-choice')].find(item => item.querySelector('legend')?.textContent === 'Сортировка')!;
            expect([...sort.querySelectorAll('label.segment')].map(item => item.textContent?.trim())).toEqual(['Популярные', 'Учат сейчас', 'Новые', 'Обновлённые']);
            expect(sort.querySelector<HTMLInputElement>('input:checked')!.value).toBe('popular');
        });

        it('draws the size with «Любой» first and explains the chosen size', () => {
            create({ ...NO_FILTERS, size: 'month' });
            const size = [...root.querySelectorAll('app-segmented-choice')].find(item => item.querySelector('legend')?.textContent === 'Размер')!;
            expect([...size.querySelectorAll('label.segment')].map(item => item.textContent?.trim())).toEqual(['Любой', 'На вечер', 'На месяц', 'Большой курс']);
            expect(size.querySelector<HTMLInputElement>('input:checked')!.value).toBe('month');
            expect(size.textContent).toContain('От 50 до 300 материалов');
        });

        it('lists topics under their parent heading and keeps «Любая» first', () => {
            create();
            select('Тема').click();
            fixture.detectChanges();
            expect([...root.querySelectorAll('[role=option]')].map(option => option.textContent?.trim()))
                .toEqual(['Любая', 'Языки', 'Испанский язык', 'Японский язык', 'Наука', 'Анатомия']);
            expect([...root.querySelectorAll('.group-label')].map(label => label.textContent?.trim())).toEqual(['Языки', 'Наука']);
        });
    });

    describe('changing the value', () => {
        it('reports a chosen topic, language and level, and «Любой» as none', () => {
            create();
            select('Тема').click();
            fixture.detectChanges();
            root.querySelectorAll<HTMLElement>('[role=option]')[2].click();
            fixture.detectChanges();
            expect(last().topic).toBe('spanish');
            select('Что учим').click();
            fixture.detectChanges();
            root.querySelectorAll<HTMLElement>('[role=option]')[1].click();
            fixture.detectChanges();
            expect(last()).toMatchObject({ topic: 'spanish', learn: 'es' });
            select('Уровень').click();
            fixture.detectChanges();
            [...root.querySelectorAll<HTMLElement>('[role=option]')].find(option => option.textContent?.trim() === 'Средний')!.click();
            fixture.detectChanges();
            expect(last()).toMatchObject({ level: 'INTERMEDIATE' });
            select('Что учим').click();
            fixture.detectChanges();
            root.querySelectorAll<HTMLElement>('[role=option]')[0].click();
            fixture.detectChanges();
            expect(last().learn).toBeNull();
        });

        it('keeps the media and exercise lists in the order of the catalogue, whatever order they were ticked in', () => {
            create();
            tick(checkbox('Видео'), true);
            tick(checkbox('Аудио'), true);
            expect(last().media).toEqual(['audio', 'video']);
            tick(checkbox('Аудио'), false);
            expect(last().media).toEqual(['video']);
            tick(checkbox('По порядку'), true);
            tick(checkbox('На слух'), true);
            expect(last().mechanics).toEqual(['listening', 'order']);
        });

        it('reports «Живая»', () => {
            create();
            tick(checkbox('Живая'), true);
            expect(last().fresh).toBe(true);
            tick(checkbox('Живая'), false);
            expect(last().fresh).toBe(false);
        });

        it('reports the size, and «Любой» as none', () => {
            create();
            pick('Размер', 'Большой курс');
            expect(last().size).toBe('course');
            pick('Размер', 'Любой');
            expect(last().size).toBeNull();
        });

        it('reports the sorting only when it really changes', () => {
            create();
            pick('Сортировка', 'Новые');
            expect(last()).toEqual({ ...NO_FILTERS, sort: 'new' });
            const count = host.emitted.length;
            pick('Сортировка', 'Новые');
            expect(host.emitted).toHaveLength(count);
        });

        it('owns no state: it draws what the page gives it', () => {
            create();
            host.apply = false;
            tick(checkbox('Живая'), true);
            expect(host.value().fresh).toBe(false);
            host.value.set({ ...NO_FILTERS, fresh: true, media: ['image'] });
            fixture.detectChanges();
            expect(checkbox('Живая').checked).toBe(true);
            expect(checkbox('Картинки').checked).toBe(true);
            expect(checkbox('Аудио').checked).toBe(false);
        });
    });

    describe('chips', () => {
        const ACTIVE: CatalogFilters = { ...NO_FILTERS, topic: 'spanish', learn: 'es', level: 'B1', size: 'evening', media: ['audio'], mechanics: ['order'], fresh: true };

        it('draws nothing when no filter is on', () => {
            create();
            expect(root.querySelector('.chips')).toBeNull();
            expect(root.textContent).not.toContain('Сбросить всё');
        });

        it('draws every filter that is on as a button with an accessible name', () => {
            create(ACTIVE);
            expect(root.querySelector('.chips')!.getAttribute('aria-label')).toBe('Выбранные фильтры');
            expect(chipNames()).toEqual([
                'Убрать фильтр «Тема: Испанский язык»', 'Убрать фильтр «Что учим: Испанский»', 'Убрать фильтр «Уровень: B1»', 'Убрать фильтр «Размер: на вечер»',
                'Убрать фильтр «Внутри: аудио»', 'Убрать фильтр «Упражнения: по порядку»', 'Убрать фильтр «Живая»'
            ]);
            expect(chips()[1].querySelector('[aria-hidden=true]')!.textContent).toBe('×');
            expect(chips()[1].textContent).toContain('Что учим: Испанский');
        });

        it('says its category in the visible text too, so the accessible name always contains what is seen', () => {
            create(ACTIVE);
            for (const chip of chips()) {
                const visible = chip.querySelector('span:not([aria-hidden])')!.textContent!;
                expect(chip.getAttribute('aria-label')).toContain(visible);
            }
        });

        it('names a filter whose option has not loaded by its code instead of hiding it', () => {
            create({ ...NO_FILTERS, topic: 'lost-topic', learn: 'xx' });
            expect(chipNames()).toEqual(['Убрать фильтр «Тема: lost-topic»', 'Убрать фильтр «Что учим: xx»']);
        });

        it('removes one filter and nothing else', () => {
            create(ACTIVE);
            chips()[1].click();
            fixture.detectChanges();
            expect(last()).toEqual({ ...ACTIVE, learn: null });
            expect(chips()).toHaveLength(6);
        });

        it('removes a media chip from the list', () => {
            create({ ...NO_FILTERS, media: ['audio', 'video'] });
            chips()[0].click();
            fixture.detectChanges();
            expect(last().media).toEqual(['video']);
        });

        it('moves the focus to the next chip after a removal', () => {
            create(ACTIVE);
            chips()[1].focus();
            chips()[1].click();
            fixture.detectChanges();
            expect(document.activeElement).toBe(chips()[1]);
            expect(chips()[1].getAttribute('aria-label')).toBe('Убрать фильтр «Уровень: B1»');
        });

        it('keeps the focus on the previous chip when the last one is removed', () => {
            create({ ...NO_FILTERS, learn: 'es', fresh: true });
            chips()[1].focus();
            chips()[1].click();
            fixture.detectChanges();
            expect(chips()).toHaveLength(1);
            expect(document.activeElement).toBe(chips()[0]);
        });

        it('moves the focus to the «Фильтры» button after the only chip is removed', () => {
            create({ ...NO_FILTERS, learn: 'es' });
            chips()[0].focus();
            chips()[0].click();
            fixture.detectChanges();
            expect(document.activeElement).toBe(toggle());
        });

        it('resets everything but the sorting, and puts the focus on the «Фильтры» button', () => {
            create({ ...ACTIVE, sort: 'updated' });
            const reset = [...root.querySelectorAll<HTMLButtonElement>('.active > button')].find(button => button.textContent?.trim() === 'Сбросить всё')!;
            reset.focus();
            reset.click();
            fixture.detectChanges();
            expect(last()).toEqual({ ...NO_FILTERS, sort: 'updated' });
            expect(root.querySelector('.chips')).toBeNull();
            expect(document.activeElement).toBe(toggle());
        });

        it('waits for a value that arrives late, then places the focus', async () => {
            create(ACTIVE);
            host.apply = false;
            chips()[0].focus();
            chips()[0].click();
            fixture.detectChanges();
            expect(chips()).toHaveLength(7);
            host.value.set(last());
            fixture.detectChanges();
            expect(chips()).toHaveLength(6);
            expect(document.activeElement).toBe(chips()[0]);
        });

        it('never takes the focus from a control the reader moved to while the parent was slow', () => {
            create(ACTIVE);
            host.apply = false;
            chips()[0].focus();
            chips()[0].click();
            fixture.detectChanges();
            const elsewhere = document.createElement('button');
            document.body.append(elsewhere);
            elsewhere.focus();
            host.value.set(last());
            fixture.detectChanges();
            expect(chips()).toHaveLength(6);
            expect(document.activeElement).toBe(elsewhere);
            elsewhere.remove();
        });

        it('acts only when the chip it removed is really gone, not on any other change of the chips', () => {
            create(ACTIVE);
            host.apply = false;
            chips()[0].click();
            fixture.detectChanges();
            host.value.set({ ...ACTIVE, fresh: false });
            fixture.detectChanges();
            expect(document.activeElement).not.toBe(chips()[0]);
            host.value.set(last());
            fixture.detectChanges();
            expect(document.activeElement).toBe(chips()[0]);
        });

        describe('with timers', () => {
            beforeEach(() => vi.useFakeTimers());
            afterEach(() => vi.useRealTimers());

            it('does not take the focus later for a removal the page never applied', () => {
                create(ACTIVE);
                host.apply = false;
                chips()[0].click();
                fixture.detectChanges();
                vi.advanceTimersByTime(2500);
                const outside = document.createElement('button');
                document.body.append(outside);
                outside.focus();
                host.value.set({ ...ACTIVE, fresh: false });
                fixture.detectChanges();
                expect(document.activeElement).toBe(outside);
                outside.remove();
            });

            it('clears its timer when destroyed', () => {
                create(ACTIVE);
                host.apply = false;
                const set = vi.spyOn(globalThis, 'setTimeout');
                const clear = vi.spyOn(globalThis, 'clearTimeout');
                chips()[0].click();
                const index = set.mock.calls.findIndex(call => call[1] === 2000);
                expect(index).toBeGreaterThanOrEqual(0);
                const timer = set.mock.results[index].value;
                fixture.destroy();
                expect(clear).toHaveBeenCalledWith(timer);
            });
        });
    });
});

@Component({
    imports: [CatalogFiltersComponent],
    template: `<app-catalog-filters [value]="value" [topics]="topics" [languages]="languages" [searching]="searching()" />`
})
class SortHostComponent {
    readonly value = NO_FILTERS;
    readonly topics = TOPICS;
    readonly languages = LANGUAGES;
    readonly searching = signal(false);
}

describe('CatalogFiltersComponent behind a text search', () => {
    const labels = (root: HTMLElement) => [...root.querySelectorAll('.sort fieldset.segmented label')].map(label => label.textContent!.trim());

    it('offers «По близости к запросу» first, only while there is a text, and no line about a disabled sorting', () => {
        const fixture = TestBed.createComponent(SortHostComponent);
        const root = fixture.nativeElement as HTMLElement;
        fixture.detectChanges();
        expect(labels(root)).toEqual(['Популярные', 'Учат сейчас', 'Новые', 'Обновлённые']);
        fixture.componentInstance.searching.set(true);
        fixture.detectChanges();
        expect(labels(root)).toEqual(['По близости к запросу', 'Популярные', 'Учат сейчас', 'Новые', 'Обновлённые']);
        expect(root.querySelector('.sort-note')).toBeNull();
        const group = root.querySelector('.sort fieldset.segmented')!;
        expect(group.hasAttribute('disabled')).toBe(false);
        expect(root.textContent).not.toContain('Найденные колоды идут');
        // The other controls are not affected.
        expect(root.querySelector('.check-row input')!.matches(':disabled')).toBe(false);
    });
});
