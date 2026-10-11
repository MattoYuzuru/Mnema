import { MAX_TAGS, TAG_MAX_CODE_POINTS, normalizeTag, tagProblem } from './tags';

describe('tag rules', () => {
    it('normalizes like the server: trim, NFKC, lowercase, inner whitespace collapsed', () => {
        expect(normalizeTag('  JLPT   N5 ')).toBe('jlpt n5');
        expect(normalizeTag('ＡＢＣ')).toBe('abc');
        expect(normalizeTag(' Кандзи ')).toBe('кандзи');
    });

    it('accepts letters, digits, space and - _ + # . and refuses anything else', () => {
        for (const tag of ['c#', 'c++', 'a-b_c.d', 'jlpt n5', 'кандзи 漢字']) expect(tagProblem(tag, [])).toBeNull();
        // Combining marks are letters of the server's alphabet (\\p{M}): Devanagari, Thai, a decomposed accent.
        for (const tag of ['हिन्दी', 'ภาษาไทย', 'e\u0301cole']) expect(tagProblem(normalizeTag(tag), [])).toBeNull();
        for (const tag of ['a,b', 'what?', 'a/b', '😀']) expect(tagProblem(tag, [])).toBe('chars');
    });

    it('counts code points, duplicates and the limit', () => {
        expect(tagProblem('', [])).toBe('empty');
        expect(tagProblem('я'.repeat(TAG_MAX_CODE_POINTS), [])).toBeNull();
        expect(tagProblem('я'.repeat(TAG_MAX_CODE_POINTS + 1), [])).toBe('long');
        expect(tagProblem('a', ['a'])).toBe('duplicate');
        expect(tagProblem('f', ['a', 'b', 'c', 'd', 'e'])).toBe('full');
        expect(MAX_TAGS).toBe(5);
    });
});
