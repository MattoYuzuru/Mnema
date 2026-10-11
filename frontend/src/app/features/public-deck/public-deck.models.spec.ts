import { HttpErrorResponse } from '@angular/common/http';

import { LEVEL_LABELS, PublicDeckFailure, canonicalPath, parsePublicExercisePage, parsePublicMaterialPage, publicFailureOf, publishedLabel } from './public-deck.models';
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

    describe('taken down after a complaint (Share/15)', () => {
        const code = 'Kq7xT3mNpR';
        const id = (suffix: string) => `7f1c2d3e-4a5b-4c6d-8e7f-${suffix.padStart(12, '0')}`;
        const materials = (items: unknown[]) => ({ code, total: items.length, items, nextCursor: null });
        const exercises = (items: unknown[]) => ({ code, total: items.length, exercises: items, nextCursor: null });
        const entry = { memberKey: id('1'), itemRevisionId: id('2'), ordinal: 0 };
        const exercise = { exerciseId: id('1'), exerciseRevisionId: id('2'), ordinal: 0, type: 'FREE_RESPONSE', enabled: true };

        it('reads 410 CONTENT_UNAVAILABLE as «removed», apart from not found and from a failure', () => {
            expect(publicFailureOf(new HttpErrorResponse({ status: 410, error: { code: 'CONTENT_UNAVAILABLE' } })).kind).toBe('removed');
            expect(publicFailureOf(new HttpErrorResponse({ status: 404 })).kind).toBe('not-found');
            expect(publicFailureOf(new HttpErrorResponse({ status: 500 })).kind).toBe('unavailable');
        });

        it('parses the marker of a blocked material (empty title) and of a blocked exercise (empty prompt)', () => {
            const page = parsePublicMaterialPage(materials([{ ...entry, title: '', unavailable: true }, { ...entry, memberKey: id('3'), title: 'Тема' }]), code, 50);
            expect(page.items.map(item => item.unavailable)).toEqual([true, false]);
            const list = parsePublicExercisePage(exercises([{ ...exercise, prompt: '', unavailable: true }, { ...exercise, exerciseId: id('4'), prompt: 'Вопрос' }]), code, 50);
            expect(list.exercises.map(item => item.unavailable)).toEqual([true, false]);
        });

        it('refuses a marker that is not exactly true and a blocked entry that still carries its text', () => {
            expect(() => parsePublicMaterialPage(materials([{ ...entry, title: '', unavailable: false }]), code, 50)).toThrow(PublicDeckFailure);
            expect(() => parsePublicMaterialPage(materials([{ ...entry, title: 'Спорный', unavailable: true }]), code, 50)).toThrow(PublicDeckFailure);
            expect(() => parsePublicExercisePage(exercises([{ ...exercise, prompt: '', unavailable: 1 }]), code, 50)).toThrow(PublicDeckFailure);
        });
    });
});
