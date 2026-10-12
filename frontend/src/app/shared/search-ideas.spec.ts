import { SEARCH_IDEAS } from './search-ideas';

describe('SEARCH_IDEAS', () => {
    it('holds the 50 ideas of the RFC, in its order, without repeats or blanks', () => {
        expect(SEARCH_IDEAS).toHaveLength(50);
        expect(new Set(SEARCH_IDEAS).size).toBe(50);
        expect(SEARCH_IDEAS.every(idea => idea.trim() === idea && idea.length > 0)).toBe(true);
        expect(SEARCH_IDEAS[0]).toBe('испанский с нуля');
        expect(SEARCH_IDEAS[24]).toBe('основы бухучёта');
        expect(SEARCH_IDEAS[49]).toBe('биология клетки');
    });

    it('stays short enough to type over a narrow field', () => {
        expect(Math.max(...SEARCH_IDEAS.map(idea => idea.length))).toBeLessThanOrEqual(30);
    });
});
