import { MIN_SHOWN_COUNT, audienceCount, sizeLine, updatedLabel } from './public-deck-card';

describe('audienceCount', () => {
    it('hides counts below the threshold, and anything that is not a count', () => {
        expect(MIN_SHOWN_COUNT).toBe(10);
        for (const value of [0, 1, 9, 9.9, -5, Number.NaN, Number.POSITIVE_INFINITY]) expect(audienceCount(value), String(value)).toBeNull();
    });

    it.each([
        [10, '10', '10'], [11, '11', '11'], [99, '99', '99'], [100, '100', '100'], [149, '140', '140'], [999, '990', '990'],
        [1000, '1\u00a0тыс.', '1 тысяча'], [1234, '1,2\u00a0тыс.', '1,2 тысячи'], [1999, '1,9\u00a0тыс.', '1,9 тысячи'],
        [2000, '2\u00a0тыс.', '2 тысячи'], [5000, '5\u00a0тыс.', '5 тысяч'], [9999, '9,9\u00a0тыс.', '9,9 тысячи'],
        [12_345, '12\u00a0тыс.', '12 тысяч'], [21_000, '21\u00a0тыс.', '21 тысяча'], [123_456, '120\u00a0тыс.', '120 тысяч'],
        [999_999, '990\u00a0тыс.', '990 тысяч'], [1_000_000, '1\u00a0млн', '1 миллион'], [2_500_000, '2,5\u00a0млн', '2,5 миллиона'],
        [4_000_000, '4\u00a0млн', '4 миллиона'], [1_234_000_000, '1,2\u00a0млрд', '1,2 миллиарда']
    ])('shows %d as «%s» and speaks it as «%s» (rounded down, two significant digits)', (count, short, spoken) => {
        expect(audienceCount(count)).toEqual({ short, spoken });
    });

    it('never shows more than the real number', () => {
        for (const count of [10, 57, 101, 999, 1001, 15_999, 987_654]) {
            const [value, unit] = audienceCount(count)!.short.replace(',', '.').split('\u00a0');
            const shown = Number(value) * (unit === 'тыс.' ? 1000 : unit === 'млн' ? 1_000_000 : 1);
            expect(shown).toBeLessThanOrEqual(count);
        }
    });
});

describe('sizeLine', () => {
    it('declines both nouns', () => {
        expect(sizeLine(1, 1)).toBe('1 материал · 1 упражнение');
        expect(sizeLine(3, 22)).toBe('3 материала · 22 упражнения');
        expect(sizeLine(11, 5)).toBe('11 материалов · 5 упражнений');
        expect(sizeLine(312, 640)).toBe('312 материалов · 640 упражнений');
    });
});

describe('updatedLabel', () => {
    const now = new Date('2026-10-10T12:00:00Z');

    it('names the day and month, and adds the year only for another year', () => {
        expect(updatedLabel('2026-10-03T09:00:00Z', now)).toBe('Обновлена 3 октября');
        expect(updatedLabel('2025-12-20T09:00:00Z', now)).toBe('Обновлена 20 декабря 2025');
    });

    it('gives nothing for an unreadable date', () => {
        expect(updatedLabel('not a date', now)).toBeNull();
    });
});
