import { CaptureNote } from '../authoring/authoring.models';
import { MaterialsSpec } from './generation.models';
import { DEFAULT_SETTINGS } from './generation-settings.component';
import { buildMaterialsSpec } from './generation-composer.component';
import { noteIds } from './generation-test-data';
import {
    MAX_NOTES, NO_OVERRIDE, customizedCount, customizedSummary, isCustomized, noteExcerpt, noteSource, overridesOf, parseNoteIds,
    refusalMessage, refusalOf, serializeNoteIds, sourceKey
} from './note-sources';

const DECK = '11111111-1111-4111-8111-111111111111';
const note = (patch: Partial<CaptureNote> = {}): CaptureNote => ({ noteId: noteIds.first, deckId: DECK, rowVersion: '7', source: 'manual',
    text: 'Глаголы движения', contentBytes: 30, archived: false, createdAt: '2026-10-01T09:00:00Z', updatedAt: '2026-10-01T09:00:00Z',
    conversion: null, ...patch });

describe('note sources (#290)', () => {
    describe('the notes query parameter', () => {
        it('round-trips the ids and keeps their order', () => {
            expect(parseNoteIds(serializeNoteIds([noteIds.second, noteIds.first]))).toEqual({ ids: [noteIds.second, noteIds.first], rejected: 0 });
            expect(parseNoteIds(null)).toEqual({ ids: [], rejected: 0 });
            expect(parseNoteIds('  ')).toEqual({ ids: [], rejected: 0 });
        });

        it('folds repeats, lower-cases, rejects what is not an id and stops at the limit of 20', () => {
            expect(parseNoteIds(`${noteIds.first.toUpperCase()},${noteIds.first}`)).toEqual({ ids: [noteIds.first], rejected: 0 });
            expect(parseNoteIds(`${noteIds.first},oops,`).rejected).toBe(2);
            const many = Array.from({ length: MAX_NOTES + 3 }, (_, position) => `20700000-0000-4000-8000-${String(position).padStart(12, '0')}`);
            const parsed = parseNoteIds(many.join(','));
            expect(parsed.ids).toHaveLength(MAX_NOTES);
            expect(parsed.rejected).toBe(3);
        });
    });

    describe('chips', () => {
        it('shortens a long note to one line and keeps a short one as it is', () => {
            expect(noteExcerpt('  Глаголы\n движения  ')).toBe('Глаголы движения');
            const long = noteExcerpt('слово '.repeat(40));
            expect(long.endsWith('…')).toBe(true);
            expect(long.length).toBeLessThanOrEqual(61);
        });

        it('pins the note at the row version it has now and labels the chip with its start', () => {
            const source = noteSource(note());
            expect(source).toEqual({ label: 'Глаголы движения', spec: { role: 'SOURCE', type: 'NOTE', noteId: noteIds.first, noteRowVersion: '7' } });
            expect(sourceKey(source)).toBe(noteIds.first);
        });

        it('refuses an archived note and one of another deck, and nothing else', () => {
            expect(refusalOf(note(), DECK)).toBeNull();
            expect(refusalOf(note({ archived: true }), DECK)).toBe('ARCHIVED');
            expect(refusalOf(note({ deckId: '22222222-2222-4222-8222-222222222222' }), DECK)).toBe('OTHER_DECK');
            expect(refusalOf(note(), DECK.toUpperCase())).toBeNull();
        });

        it('says calmly what was left out, and nothing when everything was usable', () => {
            expect(refusalMessage(0, 0, 0)).toBeNull();
            expect(refusalMessage(1, 0, 0)).toBe('Одна заметка уже в архиве. В запрос они не попали, остальные заметки на месте.');
            expect(refusalMessage(2, 1, 3)).toContain('Заметок в архиве: 2. Одна заметка из другой колоды. Заметок не удалось найти: 3.');
        });
    });

    describe('per-note settings', () => {
        const session = { audioLang: 'ko', audioVoice: 'any' as const };
        const all = { image: true, audio: true };

        it('counts only the notes that carry a setting of their own, and says it in words', () => {
            expect(isCustomized(undefined)).toBe(false);
            expect(isCustomized(NO_OVERRIDE)).toBe(false);
            expect(isCustomized({ ...NO_OVERRIDE, audio: false })).toBe(true);
            const map = { [noteIds.first]: { ...NO_OVERRIDE, effort: 'SHORT' as const }, [noteIds.second]: NO_OVERRIDE };
            expect(customizedCount(map, [noteIds.first, noteIds.second, noteIds.third])).toBe(1);
            expect(customizedCount(map, [noteIds.second])).toBe(0);
            expect([0, 1, 2, 4, 5, 11, 12, 21, 22, 25].map(customizedSummary)).toEqual([
                'Все заметки — с общими настройками', '1 заметка настроена отдельно', '2 заметки настроены отдельно',
                '4 заметки настроены отдельно', '5 заметок настроено отдельно', '11 заметок настроено отдельно',
                '12 заметок настроено отдельно', '21 заметка настроена отдельно', '22 заметки настроены отдельно', '25 заметок настроено отдельно']);
        });

        it('turns a draft into the sparse wire overrides: only what was changed, with the session language and voice', () => {
            expect(overridesOf(undefined, session, all)).toBeUndefined();
            expect(overridesOf(NO_OVERRIDE, session, all)).toBeUndefined();
            expect(overridesOf({ ...NO_OVERRIDE, effort: 'DETAILED' }, session, all)).toEqual({ effort: 'DETAILED' });
            expect(overridesOf({ ...NO_OVERRIDE, imageSearch: false }, session, all)).toEqual({ media: { imageSearch: false } });
            expect(overridesOf({ effort: 'SHORT', imageSearch: true, audio: true }, { audioLang: 'ko', audioVoice: 'female' }, all))
                .toEqual({ effort: 'SHORT', media: { audio: { enabled: true, lang: 'ko', voice: 'female' }, imageSearch: true } });
        });

        it('leaves out a media switch the server does not offer, so the whole request is not refused for it', () => {
            expect(overridesOf({ ...NO_OVERRIDE, audio: true }, session, { image: true, audio: false })).toBeUndefined();
            expect(overridesOf({ ...NO_OVERRIDE, imageSearch: true }, session, { image: false, audio: true })).toBeUndefined();
            expect(overridesOf({ effort: 'SHORT', imageSearch: true, audio: null }, session, { image: false, audio: false })).toEqual({ effort: 'SHORT' });
        });

        it('puts the overrides only on SOURCE notes of a one-per-note request with two or more of them', () => {
            const sources = [noteIds.first, noteIds.second].map(noteId => ({ role: 'SOURCE' as const, type: 'NOTE' as const, noteId, noteRowVersion: '3' }));
            const drafts = { [noteIds.second]: { ...NO_OVERRIDE, effort: 'SHORT' as const } };
            const build = (settings = DEFAULT_SETTINGS, list: MaterialsSpec['sources'] = sources): MaterialsSpec =>
                buildMaterialsSpec('', settings, list, all, drafts);
            expect(build().sources).toEqual([sources[0], { ...sources[1], overrides: { effort: 'SHORT' } }]);
            expect(build({ ...DEFAULT_SETTINGS, notesMode: 'MERGE_INTO_ONE' }).sources).toEqual(sources);
            expect(build(DEFAULT_SETTINGS, [sources[1]!]).sources).toEqual([sources[1]]);
            const asExample = [sources[0]!, { ...sources[1]!, role: 'STYLE_EXAMPLE' as const }];
            expect(build(DEFAULT_SETTINGS, asExample).sources).toEqual(asExample);
        });
    });
});
