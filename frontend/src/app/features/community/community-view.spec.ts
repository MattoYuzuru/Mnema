import { convertToParamMap } from '@angular/router';

import contract from '../../../../../contracts/community/catalog.json';
import { NO_FILTERS } from '../../shared/catalog-filters';
import {
    SHELF_DECKS_MAX, SHELF_MIN_DECKS, SHELF_ROW_MAX, addressOf, addressParams, displayCard, isShelvesView, languageName, languageOptions, moreParams, requestKey,
    shelfKey, shelfLimit, shelfTitle, topicOptions
} from './community-view';
import { CommunityCard, CommunityShelf, parseCommunityCard, parseCommunityFacets } from './community.models';

const CARD = parseCommunityCard(contract.card);
const NOBODY = { username: null, avatarSrc: null };
const address = (params: Record<string, string | string[]>) => addressOf(convertToParamMap(params));

describe('the address of the community page', () => {
    it('is the shelves when nothing narrows or orders the catalogue', () => {
        expect(isShelvesView(address({}))).toBe(true);
        // Junk and unknown values are dropped (they never reach the server) and leave the shelves.
        expect(isShelvesView(address({ topic: '!!!', sort: 'bogus', size: 'huge', media: 'hologram' }))).toBe(true);
        expect(isShelvesView(address({ q: '   ' }))).toBe(true);
    });

    it('is the result grid with a text, a filter or any named sorting, the default one too', () => {
        for (const params of <Record<string, string | string[]>[]>[{ q: 'кот' }, { topic: 'spanish' }, { fresh: 'true' }, { media: ['audio', 'video'] }, { sort: 'new' }, { sort: 'popular' }]) {
            expect(isShelvesView(address(params)), JSON.stringify(params)).toBe(false);
        }
    });

    it('reads the text trimmed and cut at 100 code points, and the filters strictly', () => {
        const read = address({ q: `  ${'я'.repeat(120)} `, topic: 'spanish', media: ['video', 'audio', 'audio'], level: 'B1', sort: 'new' });
        expect(Array.from(read.q)).toHaveLength(100);
        expect(read.filters).toEqual({ ...NO_FILTERS, topic: 'spanish', media: ['audio', 'video'], level: 'B1', sort: 'new' });
        expect(read.sortGiven).toBe(true);
        expect(address({ sort: 'bogus' }).sortGiven).toBe(false);
    });

    it('writes the text and the filters back, repeated lists as they are, the sorting behind a text, and nothing that is off', () => {
        expect(addressParams('', NO_FILTERS)).toEqual({});
        expect(addressParams('  кот ', { ...NO_FILTERS, topic: 'spanish', media: ['audio', 'video'], sort: 'new' })).toEqual({ q: 'кот', topic: 'spanish', media: ['audio', 'video'], sort: 'new' });
        expect(addressParams('кот', { ...NO_FILTERS, sort: 'relevance' })).toEqual({ q: 'кот' });
        expect(addressParams('кот', { ...NO_FILTERS, sort: 'popular' })).toEqual({ q: 'кот', sort: 'popular' });
        expect(addressParams('', { ...NO_FILTERS, sort: 'relevance' })).toEqual({});
        expect(addressParams('', { ...NO_FILTERS, fresh: true, sort: 'learning' })).toEqual({ fresh: 'true', sort: 'learning' });
    });

    it('has a request key that changes with the request only', () => {
        const key = (params: Record<string, string | string[]>) => requestKey(address(params));
        expect(key({})).toBe(key({ topic: '!!!' }));
        expect(key({ topic: 'spanish' })).not.toBe(key({}));
        expect(key({ q: 'кот', sort: 'new' })).not.toBe(key({ q: 'кот' }));
        expect(key({ q: 'кот', sort: 'bogus' })).toBe(key({ q: 'кот' }));
        expect(key({ q: 'кот' })).not.toBe(key({ q: 'пёс' }));
        expect(key({ sort: 'new' })).not.toBe(key({ sort: 'learning' }));
        expect(key({ media: ['video', 'audio'] })).toBe(key({ media: ['audio', 'video'] }));
    });
});

describe('what the facets give the filters', () => {
    const facets = parseCommunityFacets(contract.facets.response);

    it('draws a parent alone and its children under its heading, in the order of the server', () => {
        expect(topicOptions(facets)).toEqual([
            { id: 'languages', name: 'Языки' },
            { id: 'spanish', name: 'Испанский', parentName: 'Языки' },
            { id: 'english', name: 'Английский', parentName: 'Языки' },
            { id: 'it', name: 'IT и программирование' },
            { id: 'sql-data', name: 'SQL и данные', parentName: 'IT и программирование' }
        ]);
        expect(topicOptions(null)).toEqual([]);
    });

    it('keeps a child whose parent is not in the facets rather than losing it', () => {
        const lonely = { ...facets, topics: [{ id: 'sql-data', parentId: 'it', name: 'SQL и данные', count: 2 }] };
        expect(topicOptions(lonely)).toEqual([{ id: 'sql-data', name: 'SQL и данные' }]);
    });

    it('offers only the languages that are in the facets, by their Russian names', () => {
        expect(languageOptions(facets)).toEqual([{ code: 'es', name: 'Испанский' }, { code: 'en', name: 'Английский' }]);
        expect(languageOptions(null)).toEqual([]);
    });

    it('names a language in Russian, with a capital, and falls back to the code', () => {
        expect(languageName('ja')).toBe('Японский');
        expect(languageName('de')).toBe('Немецкий');
        expect(languageName('zz-unknown-code')).toMatch(/\S/u);
        expect(languageName('')).toBe('');
        expect(languageName('not a code at all!')).toBe('not a code at all!');
    });
});

describe('shelves', () => {
    const shelf = (patch: Partial<CommunityShelf>): CommunityShelf => ({ kind: 'new', topic: null, decks: [CARD], more: null, ...patch });

    it('are titled by the client for the editorial, learning and new shelves, and by the topic for a topic shelf', () => {
        expect(shelfTitle(shelf({ kind: 'editorial' }))).toBe('Выбор Мнемы');
        expect(shelfTitle(shelf({ kind: 'learning' }))).toBe('Учат сейчас');
        expect(shelfTitle(shelf({ kind: 'new' }))).toBe('Новое');
        expect(shelfTitle(shelf({ kind: 'topic', topic: { id: 'spanish', name: 'Испанский', parentId: 'languages' } }))).toBe('Испанский');
    });

    it('have a key per shelf and a «Все» query of their own, none for the editorial', () => {
        expect(shelfKey(shelf({ kind: 'learning' }))).toBe('learning');
        expect(shelfKey(shelf({ kind: 'topic', topic: { id: 'spanish', name: 'x', parentId: null } }))).toBe('topic:spanish');
        expect(moreParams(null)).toBeNull();
        expect(moreParams({ topic: 'spanish', sort: 'popular' })).toEqual({ topic: 'spanish', sort: 'popular' });
        expect(moreParams({ sort: 'learning' })).toEqual({ sort: 'learning' });
    });

    it('show one row of at most six cards where «Все» holds the rest, every card where there is no «Все», and none under four', () => {
        expect(SHELF_ROW_MAX).toBe(6);
        expect(SHELF_MIN_DECKS).toBe(4);
        expect(SHELF_DECKS_MAX).toBe(contract.constants.shelfMaxDecks);
        expect(shelfLimit(shelf({ kind: 'editorial', more: null }))).toBe(12);
        expect(shelfLimit(shelf({ kind: 'new', more: { sort: 'new' } }))).toBe(6);
    });
});

describe('the card as the page shows it', () => {
    const origin = 'https://mnema.app';

    it('names the topic, the language that is learned and the author, and carries the counts and the address', () => {
        const card = displayCard(CARD, { username: 'anna.k', avatarSrc: '/a.png' }, origin);
        expect(card).toEqual({
            id: CARD.code, title: CARD.title, description: CARD.description, topic: 'Испанский', language: 'Испанский',
            author: { username: 'anna.k', avatarSrc: '/a.png' }, materialCount: 40, exerciseCount: 120, media: ['image', 'audio'],
            addedCount: 1200, learningNowCount: 34, updatedAt: CARD.updatedAt, shareUrl: `${origin}/d/${CARD.code}/${CARD.slug}`
        });
    });

    it('says the content language when no language is learned, and nothing for a deck without topic or language', () => {
        const spoken: CommunityCard = { ...CARD, targetLanguage: null, contentLanguage: 'ru' };
        expect(displayCard(spoken, NOBODY, origin).language).toBe('Русский');
        const bare: CommunityCard = { ...CARD, targetLanguage: null, contentLanguage: null, topic: null };
        expect(displayCard(bare, NOBODY, origin)).toMatchObject({ topic: '', language: '' });
    });

    it('hides a small audience even if the wire let one through, and escapes the slug in the address', () => {
        expect(displayCard({ ...CARD, addedCount: 7, learningNowCount: 10 }, NOBODY, origin)).toMatchObject({ addedCount: null, learningNowCount: 10 });
        expect(displayCard({ ...CARD, slug: 'a&b' }, NOBODY, origin).shareUrl).toBe(`${origin}/d/${CARD.code}/a%26b`);
    });
});
