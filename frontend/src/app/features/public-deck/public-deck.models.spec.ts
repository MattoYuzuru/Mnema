import { LEVEL_LABELS, PublicDeckFailure, canonicalPath, publishedLabel } from './public-deck.models';
import { readFailureText, waitText } from './public-deck.text';

describe('public deck helpers', () => {
    it('names the publication date and adds the year only when it is not the current one', () => {
        const now = new Date('2026-10-11T12:00:00Z');
        expect(publishedLabel('2026-10-03T09:00:00Z', now)).toBe('Опубликовано 3 октября');
        expect(publishedLabel('2025-12-20T09:00:00Z', now)).toBe('Опубликовано 20 декабря 2025');
        expect(publishedLabel('soon', now)).toBeNull();
    });

    it('words the level mark and builds the address with a slug for PUBLIC only', () => {
        expect(LEVEL_LABELS).toEqual({ PUBLIC: 'Публичная', LINK: 'По ссылке', INVITE: 'По приглашению' });
        expect(canonicalPath({ code: 'Kq7xT3mNpR', visibility: 'PUBLIC', slug: 'ispanskiy' })).toEqual(['/d', 'Kq7xT3mNpR', 'ispanskiy']);
        expect(canonicalPath({ code: 'Kq7xT3mNpR', visibility: 'PUBLIC', slug: null })).toEqual(['/d', 'Kq7xT3mNpR']);
        expect(canonicalPath({ code: 'Kq7xT3mNpR', visibility: 'LINK', slug: null })).toEqual(['/d', 'Kq7xT3mNpR']);
    });

    it('words the retry notices, with the delay when the server gave one', () => {
        expect(waitText(37)).toBe('Подождите 37 с и повторите.');
        expect(waitText(null)).toBe('Подождите немного и повторите.');
        expect(readFailureText(new PublicDeckFailure('rate-limited', 5), 'материалы')).toBe('Слишком много запросов. Подождите 5 с и повторите.');
        expect(readFailureText(new PublicDeckFailure('busy', 1), 'материалы')).toContain('Повторите через секунду');
        expect(readFailureText(null, 'упражнения')).toBe('Не удалось загрузить упражнения. Проверьте соединение и повторите.');
    });
});
