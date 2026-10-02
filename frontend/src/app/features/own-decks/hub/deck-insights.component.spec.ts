import { ComponentFixture, DeferBlockState, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import hub from '../../../../../../contracts/decks/hub.json';
import { DeckInsightsComponent, InsightsState } from './deck-insights.component';
import { DeckInsights, parseInsights } from './deck-hub.models';

describe('DeckInsightsComponent', () => {
    const deckId = hub.insights.response.deckId;
    const insights: DeckInsights = parseInsights(hub.insights.response);
    const emptyDeck: DeckInsights = parseInsights(hub.insights.emptyDeck);
    let fixture: ComponentFixture<DeckInsightsComponent>;
    const root = () => fixture.nativeElement as HTMLElement;

    async function open(state: InsightsState, render = true) {
        fixture = TestBed.createComponent(DeckInsightsComponent);
        fixture.componentRef.setInput('deckId', deckId);
        fixture.componentRef.setInput('state', state);
        fixture.detectChanges();
        if (render) {
            for (const block of await fixture.getDeferBlocks()) await block.render(DeferBlockState.Complete);
            fixture.detectChanges();
        }
        return fixture.componentInstance;
    }

    beforeEach(() => TestBed.configureTestingModule({ imports: [DeckInsightsComponent], providers: [provideRouter([])] }));

    it('draws five widgets, each a figure whose image is named by a caption carrying the numbers', async () => {
        await open({ phase: 'ready', insights });
        const widgets = root().querySelectorAll('article.widget');
        expect(widgets).toHaveLength(5);
        const figures = root().querySelectorAll('figure');
        expect(figures).toHaveLength(5);
        for (const figure of Array.from(figures)) {
            const image = figure.querySelector('svg[role="img"]')!;
            const caption = figure.querySelector('figcaption')!;
            expect(image.getAttribute('aria-labelledby')).toBe(caption.id);
            expect(caption.textContent?.trim().length).toBeGreaterThan(10);
        }
        const captions = Array.from(root().querySelectorAll('figcaption')).map(caption => caption.textContent?.replace(/\s+/g, ' ').trim());
        expect(captions[0]).toBe('С упражнениями 7 из 10 материалов. 3 материала без упражнений не попадут в занятия.');
        expect(captions[1]).toBe('К повторению 2, учится 2, в порядке 2, не начато 4.');
        expect(captions[2]).toBe('Сегодня можно повторить 2 материала.');
        expect(captions[3]).toContain('чаще всего «Выбрать ответ»: 6');
        expect(captions[4]).toContain('Ждут разбора 4 заметки');
    });

    it('tells states apart without colour: hatch patterns, outlines and a legend with numbers', async () => {
        await open({ phase: 'ready', insights });
        expect(root().querySelectorAll('pattern')).toHaveLength(2);
        const fills = Array.from(root().querySelectorAll('.bar rect.segment')).map(rect => rect.getAttribute('fill'));
        expect(fills[0]).toBe('currentColor');
        expect(fills[1]).toMatch(/^url\(#.*-diag\)$/);
        expect(fills[2]).toMatch(/^url\(#.*-cross\)$/);
        expect(fills[3]).toBe('none');
        const legend = Array.from(root().querySelectorAll('.legend li')).map(item => item.textContent?.replace(/\s+/g, ' ').trim());
        expect(legend).toEqual(['Не начато 4', 'Учится 2', 'К повторению 2', 'В порядке 2']);
    });

    it('offers every figure as a real table with the same numbers', async () => {
        await open({ phase: 'ready', insights });
        const tables = root().querySelectorAll('details table');
        expect(tables).toHaveLength(5);
        for (const summary of Array.from(root().querySelectorAll('details summary'))) expect(summary.textContent?.trim()).toBe('Показать таблицей');
        const cells = (index: number) => Array.from(tables[index].querySelectorAll('tbody tr'))
            .map(row => Array.from(row.children).map(cell => cell.textContent?.trim()));
        expect(cells(0)).toEqual([['С упражнениями', '7'], ['Без упражнений', '3'], ['Всего', '10']]);
        expect(cells(1)).toEqual([['Не начато', '4'], ['Учится', '2'], ['К повторению', '2'], ['В порядке', '2']]);
        expect(cells(2)).toHaveLength(7);
        expect(cells(2)[0][1]).toBe('2');
        expect(cells(3)).toHaveLength(7);
        expect(cells(3)[6]).toEqual(['Распределить', '0']);
        for (const table of Array.from(tables)) {
            expect(table.querySelector('caption')).not.toBeNull();
            expect(table.querySelectorAll('thead th[scope="col"]').length).toBeGreaterThan(0);
        }
    });

    it('keeps captions, tables and actions as ordinary content before the drawings are deferred in', async () => {
        await open({ phase: 'ready', insights }, false);
        expect(root().querySelectorAll('figcaption')).toHaveLength(5);
        expect(root().querySelectorAll('details table')).toHaveLength(5);
        expect(root().querySelectorAll('.plot-placeholder').length).toBe(5);
        expect(root().querySelector('svg[role="img"]')).toBeNull();
    });

    it('ends every widget in an action that fits the numbers', async () => {
        const component = await open({ phase: 'ready', insights });
        let shown = 0;
        component.showMissing.subscribe(() => shown++);
        const actions = Array.from(root().querySelectorAll('.widget .action')).map(action => action.textContent?.trim());
        expect(actions).toEqual(['Показать материалы без упражнений', 'Учить: к повторению', 'Начать занятие',
            'Выбрать материал для упражнения', 'Разобрать заметки']);
        root().querySelector<HTMLButtonElement>('.widget .action button')!.click();
        expect(shown).toBe(1);
        const hrefs = Array.from(root().querySelectorAll<HTMLAnchorElement>('.widget .action a')).map(link => link.getAttribute('href'));
        expect(hrefs).toEqual([`/decks/${deckId}/study`, `/decks/${deckId}/study`, `/decks/${deckId}/capture`]);
        root().querySelectorAll<HTMLButtonElement>('.widget .action button')[1].click();
        expect(shown).toBe(2);
    });

    it('still ends in an action for an empty deck, and adds a material instead of filtering', async () => {
        await open({ phase: 'ready', insights: emptyDeck });
        const actions = Array.from(root().querySelectorAll('.widget .action a')).map(link => [link.textContent?.trim(), link.getAttribute('href')]);
        expect(actions).toEqual([
            ['Добавить материал', `/decks/${deckId}/materials/new`], ['Добавить материал', `/decks/${deckId}/materials/new`],
            ['Учить', `/decks/${deckId}/study`], ['Учить', `/decks/${deckId}/study`], ['Открыть «На потом»', `/decks/${deckId}/capture`]
        ]);
        expect(root().querySelector('.legend')).not.toBeNull();
        expect(root().querySelector('figcaption')?.textContent).toContain('В колоде пока нет материалов');
    });

    it('reduces to a short note with a retry when statistics fail, and says nothing wrong while loading', async () => {
        const component = await open({ phase: 'error' });
        expect(root().querySelector('.note.error')?.textContent).toContain('Статистика сейчас недоступна');
        expect(root().querySelector('article')).toBeNull();
        let retried = 0;
        component.retry.subscribe(() => retried++);
        root().querySelector<HTMLButtonElement>('.note button')!.click();
        expect(retried).toBe(1);
        fixture.destroy();
        await open({ phase: 'loading' });
        expect(root().querySelector('[role="status"]')?.textContent).toContain('Считаем');
    });

    it('is a keyboard-scrollable labelled region and shows no vanity metrics', async () => {
        await open({ phase: 'ready', insights });
        const strip = root().querySelector('.widgets')!;
        expect(strip.getAttribute('role')).toBe('region');
        expect(strip.getAttribute('aria-label')).toBe('Статистика колоды');
        expect(strip.getAttribute('tabindex')).toBe('0');
        const text = root().textContent!.toLowerCase();
        for (const word of ['стрик', 'серия дней', 'мастерство', 'время занятий', 'открытий']) expect(text).not.toContain(word);
    });
});
