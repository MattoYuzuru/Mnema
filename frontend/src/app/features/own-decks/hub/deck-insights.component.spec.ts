import { ComponentFixture, DeferBlockState, TestBed } from '@angular/core/testing';

import hub from '../../../../../../contracts/decks/hub.json';
import { DeckInsightsComponent, InsightsState } from './deck-insights.component';
import { DeckInsights, parseInsights } from './deck-hub.models';

describe('DeckInsightsComponent', () => {
    const insights: DeckInsights = parseInsights(hub.insights.response);
    const emptyDeck: DeckInsights = parseInsights(hub.insights.emptyDeck);
    let fixture: ComponentFixture<DeckInsightsComponent>;
    const root = () => fixture.nativeElement as HTMLElement;

    async function open(state: InsightsState, render = true) {
        fixture = TestBed.createComponent(DeckInsightsComponent);
        fixture.componentRef.setInput('state', state);
        fixture.detectChanges();
        if (render) {
            for (const block of await fixture.getDeferBlocks()) await block.render(DeferBlockState.Complete);
            fixture.detectChanges();
        }
        return fixture.componentInstance;
    }

    beforeEach(() => TestBed.configureTestingModule({ imports: [DeckInsightsComponent] }));

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
        expect(captions[0]).toContain('чаще всего «Выбрать ответ»: 6');
        expect(captions[1]).toBe('С упражнениями 7 из 10 материалов. 3 материала без упражнений не попадут в занятия.');
        expect(captions[2]).toBe('К повторению 2, учится 2, в порядке 2, не начато 4.');
        expect(captions[3]).toBe('Сегодня можно повторить 2 материала.');
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

    it('keeps all numeric legends without tables or redirect actions', async () => {
        await open({ phase: 'ready', insights });
        expect(root().querySelectorAll('details, table, .action, a')).toHaveLength(0);
        expect(Array.from(root().querySelectorAll('.mechanic-labels strong')).map(el => el.textContent))
            .toEqual(['4', '2', '2', '6', '0', '0', '0']);
        expect(Array.from(root().querySelectorAll('.day-labels strong')).map(el => el.textContent))
            .toEqual(insights.dueByDay.map(day => String(day.materials)));
        expect(root().querySelector('.mechanics-widget h3')?.textContent).toBe('Разнообразие упражнений');
        expect(root().querySelector('.coverage-widget h3')?.textContent).toBe('Покрытие упражнениями');
    });

    it('keeps captions before deferred drawings arrive', async () => {
        await open({ phase: 'ready', insights }, false);
        expect(root().querySelectorAll('figcaption')).toHaveLength(5);
        expect(root().querySelectorAll('.plot-placeholder')).toHaveLength(5);
        expect(root().querySelector('svg[role="img"]')).toBeNull();
        expect(root().querySelectorAll('.mechanic-labels li')).toHaveLength(7);
        expect(root().querySelectorAll('.day-labels li')).toHaveLength(7);
        expect(root().querySelectorAll('.legend li')).toHaveLength(4);
    });

    it('explains empty data without adding actions', async () => {
        await open({ phase: 'ready', insights: emptyDeck });
        expect(root().querySelectorAll('a, button')).toHaveLength(0);
        expect(root().querySelector('.coverage-widget figcaption')?.textContent).toContain('В колоде пока нет материалов');
        expect(root().querySelector('.legend')).not.toBeNull();
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

    it('labels the unified section and shows no vanity metrics', async () => {
        await open({ phase: 'ready', insights });
        const section = root().querySelector('section')!;
        expect(root().querySelector('h2')?.id).toBe(section.getAttribute('aria-labelledby'));
        expect(root().querySelector('.widgets')?.hasAttribute('tabindex')).toBe(false);
        const text = root().textContent!.toLowerCase();
        for (const word of ['стрик', 'серия дней', 'мастерство', 'время занятий', 'открытий']) expect(text).not.toContain(word);
    });
});
