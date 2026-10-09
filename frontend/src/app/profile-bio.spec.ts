import { isProfileBio, normalizeProfileBio, validProfileBioEdit } from './profile-bio';

describe('profile bio text', () => {
    it('preserves Unicode, emoji and paragraphs while bounding whitespace', () => {
        const raw = '\r\n  Учусь\t\tкаждый  день ✨  \r\n\r\n \t\r\n\r\n  • Математика\u00a0\u00a0и языки  \r\n';
        expect(isProfileBio(raw)).toBe(true);
        expect(normalizeProfileBio(raw)).toBe('Учусь каждый день ✨\n\n• Математика и языки');
        expect(validProfileBioEdit(raw)).toBe(true);
    });

    it('accepts empty text, 200 characters and six lines, rejecting their upper bounds', () => {
        expect(normalizeProfileBio(' \t\n\n\n ')).toBe('');
        expect(validProfileBioEdit('')).toBe(true);
        expect(validProfileBioEdit('а'.repeat(200))).toBe(true);
        expect(validProfileBioEdit('а'.repeat(201))).toBe(false);
        expect(validProfileBioEdit(Array(6).fill('строка').join('\n'))).toBe(true);
        expect(validProfileBioEdit(Array(7).fill('строка').join('\n'))).toBe(false);
        expect(validProfileBioEdit('Первая' + '\n'.repeat(100) + 'Вторая')).toBe(true);
        expect(normalizeProfileBio('Первая' + '\n'.repeat(100) + 'Вторая')).toBe('Первая\n\nВторая');
    });

    it.each(['\u0000', '\u0001', '\u000b', '\u001b', '\u007f'])('rejects a forbidden control %j', control => {
        expect(isProfileBio('Текст' + control)).toBe(false);
        expect(validProfileBioEdit('Текст' + control)).toBe(false);
    });

    it('rejects non-string protocol values', () => {
        for (const value of [null, undefined, 1, {}, []]) expect(isProfileBio(value)).toBe(false);
    });
});
