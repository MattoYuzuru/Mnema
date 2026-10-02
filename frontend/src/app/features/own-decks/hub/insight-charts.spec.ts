import hub from '../../../../../../contracts/decks/hub.json';
import { DeckInsights, parseInsights } from './deck-hub.models';
import {
    capturesSentence,
    coverageArc,
    coverageHint,
    coverageSentence,
    dayLabel,
    dueBars,
    dueSentence,
    hasUnusedMechanic,
    mechanicBars,
    mechanicsSentence,
    stateSegments,
    statesSentence,
    STATE_BAR_WIDTH
} from './insight-charts';
import { materialsText, plural } from './deck-hub.text';

describe('insight charts and wording', () => {
    const insights: DeckInsights = parseInsights(hub.insights.response);
    const empty: DeckInsights = parseInsights(hub.insights.emptyDeck);

    it('declines Russian counts', () => {
        expect([1, 2, 5, 11, 12, 21, 22, 25, 111].map(count => plural(count, 'материал', 'материала', 'материалов')))
            .toEqual(['материал', 'материала', 'материалов', 'материалов', 'материалов', 'материал', 'материала', 'материалов', 'материалов']);
        expect(materialsText(7)).toBe('7 материалов');
    });

    it('draws coverage as a share of one ring, empty for an empty deck', () => {
        expect(coverageArc(insights.coverage)).toBe(70);
        expect(coverageArc(empty.coverage)).toBe(0);
        expect(coverageSentence(insights.coverage)).toBe('С упражнениями 7 из 10 материалов.');
        expect(coverageHint(insights.coverage)).toBe('3 материала без упражнений не попадут в занятия.');
        expect(coverageHint({ total: 4, withExercises: 3, withoutExercises: 1 })).toContain('не попадёт');
        expect(coverageHint({ total: 4, withExercises: 4, withoutExercises: 0 })).toBe('Каждый материал попадёт в занятия.');
        expect(coverageSentence(empty.coverage)).toBe('В колоде пока нет материалов.');
    });

    it('lays the state bar out proportionally, skips empty states and keeps the full width', () => {
        const segments = stateSegments(insights.states);
        expect(segments.map(segment => segment.state)).toEqual(['ON_TRACK', 'LEARNING', 'DUE', 'NOT_STARTED']);
        expect(segments.reduce((sum, segment) => sum + segment.width, 0)).toBeCloseTo(STATE_BAR_WIDTH);
        expect(segments[0].x).toBe(0);
        expect(stateSegments({ NOT_STARTED: 3, LEARNING: 0, DUE: 0, ON_TRACK: 0 }).map(segment => segment.state)).toEqual(['NOT_STARTED']);
        expect(stateSegments(empty.states)).toEqual([]);
        expect(statesSentence(insights.states)).toBe('К повторению 2, учится 2, в порядке 2, не начато 4.');
        expect(statesSentence(empty.states)).toContain('появятся');
    });

    it('scales due-day columns to the busiest day and keeps zero days drawn', () => {
        const bars = dueBars(insights.dueByDay);
        expect(bars).toHaveLength(7);
        expect(bars[0].label).toBe('сегодня');
        expect(bars[0].height).toBeGreaterThan(bars[2].height);
        expect(bars[1].materials).toBe(0);
        expect(bars[1].height).toBeGreaterThan(0);
        expect(bars[0].tableLabel).toMatch(/^Сегодня, /);
        expect(dayLabel('2026-10-04', 2)).toMatch(/вс/);
        expect(dueSentence(insights.dueByDay)).toBe('Сегодня можно повторить 2 материала.');
        const later = insights.dueByDay.map((day, index) => ({ ...day, materials: index === 3 ? 4 : 0 }));
        expect(dueSentence(later)).toMatch(/^Следующее повторение: .*, 4 материала\.$/);
        expect(dueSentence(empty.dueByDay)).toBe('На ближайшие 7 дней повторений нет.');
        expect(dueBars(empty.dueByDay).every(bar => bar.height === 2)).toBe(true);
    });

    it('draws one bar per mechanic, names an unused one, and describes the mix', () => {
        const bars = mechanicBars(insights.exercisesByMechanic);
        expect(bars.map(bar => bar.mechanic)).toEqual([...hub.constants.mechanics]);
        expect(bars.find(bar => bar.mechanic === 'MATCH')?.width).toBe(0);
        expect(bars.find(bar => bar.mechanic === 'CHOICE')?.width).toBe(100);
        expect(hasUnusedMechanic(insights.exercisesByMechanic)).toBe(true);
        expect(hasUnusedMechanic(empty.exercisesByMechanic)).toBe(false);
        expect(mechanicsSentence(insights.exercisesByMechanic)).toBe('Всего 14 упражнений; чаще всего «Выбрать ответ»: 6. Не использовано 3 механики из 7.');
        expect(mechanicsSentence(empty.exercisesByMechanic)).toBe('Упражнений пока нет.');
    });

    it('says how long the oldest capture has waited, relative to the snapshot clock', () => {
        expect(capturesSentence(insights.captures, insights.asOf)).toBe('Ждут разбора 4 заметки; самая давняя — 19 дней назад.');
        expect(capturesSentence(empty.captures, empty.asOf)).toBe('Неразобранных заметок нет.');
        expect(capturesSentence({ open: 1, oldestOpenCreatedAt: insights.asOf }, insights.asOf)).toContain('сегодня');
    });

    it('never mentions vanity metrics', () => {
        const text = [coverageSentence(insights.coverage), coverageHint(insights.coverage), statesSentence(insights.states),
            dueSentence(insights.dueByDay), mechanicsSentence(insights.exercisesByMechanic), capturesSentence(insights.captures, insights.asOf)].join(' ');
        for (const word of ['серия', 'стрик', 'открыт', 'мастерство', '%', 'время']) expect(text.toLowerCase()).not.toContain(word);
    });
});
