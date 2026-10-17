import { ParamMap, Params } from '@angular/router';

import {
    CATALOG_SORTS, CatalogFilters, LanguageOption, TopicOption, activeCount, fromQueryParams, toQueryParams
} from '../../shared/catalog-filters';
import { PublicDeckAuthor, PublicDeckCard, MIN_SHOWN_COUNT } from '../../shared/public-deck-card';
import { normalizeQuery } from './community-api.service';
import { AuthorDeckCard, CommunityFacets, CommunityShelf, ShelfMore } from './community.models';

/** What the address says: the text of the search and the filters. The address is the state of the page. */
export interface CommunityAddress {
    readonly q: string;
    readonly filters: CatalogFilters;
    /** The address names a sorting itself. */
    readonly sortGiven: boolean;
}

export function addressOf(params: ParamMap): CommunityAddress {
    const sort = params.get('sort');
    return {
        q: normalizeQuery(params.get('q')),
        filters: fromQueryParams(params),
        sortGiven: sort === 'for-you' || CATALOG_SORTS.some(entry => entry.value === sort)
    };
}

/**
 * The shelves are the page when nothing narrows or orders the catalogue: no search text, no filter and no sorting in the address.
 */
export function isShelvesView(address: CommunityAddress): boolean {
    return address.q === '' && activeCount(address.filters) === 0 && !address.sortGiven;
}

/** The address parameters of a search: the text, then the filters (`toQueryParams`); behind a text query «По близости» is the missing `sort`. */
export function addressParams(q: string, filters: CatalogFilters, explicitSort = false): Params {
    const text = normalizeQuery(q);
    return { ...(text === '' ? {} : { q: text }), ...toQueryParams(filters, text !== ''), ...(explicitSort && filters.sort === 'popular' ? { sort: 'popular' } : {}) };
}

/** A key that changes exactly when the request to the server changes. */
export function requestKey(address: CommunityAddress): string {
    return JSON.stringify(isShelvesView(address) ? { shelves: true } : addressParams(address.q, address.filters, address.sortGiven));
}

let languageNames: Intl.DisplayNames | null | undefined;

/** «Испанский» for `es`; the code itself where the platform does not know the language. */
export function languageName(code: string): string {
    if (languageNames === undefined) {
        try { languageNames = new Intl.DisplayNames(['ru'], { type: 'language' }); } catch { languageNames = null; }
    }
    let name: string | undefined;
    try { name = languageNames?.of(code); } catch { name = undefined; }
    if (name === undefined || name === '' || name === code) return code;
    return name.charAt(0).toLocaleUpperCase('ru') + name.slice(1);
}

/** The topics of the facets for the filter: a parent stands alone, its children follow under its heading; ordered as the server counts them. */
export function topicOptions(facets: CommunityFacets | null): readonly TopicOption[] {
    if (facets === null) return [];
    const parents = facets.topics.filter(topic => topic.parentId === null);
    const names = new Map(facets.topics.map(topic => [topic.id, topic.name] as const));
    const options: TopicOption[] = [];
    for (const parent of parents) {
        options.push({ id: parent.id, name: parent.name });
        for (const child of facets.topics) if (child.parentId === parent.id) options.push({ id: child.id, name: child.name, parentName: parent.name });
    }
    // A child whose parent has no listed decks of its own cannot happen (a parent counts its children), but must not vanish if it does.
    for (const topic of facets.topics) {
        if (topic.parentId !== null && !names.has(topic.parentId)) options.push({ id: topic.id, name: topic.name });
    }
    return options;
}

/** Only the languages that are in the facets are offered. */
export function languageOptions(facets: CommunityFacets | null): readonly LanguageOption[] {
    return (facets?.languages ?? []).map(language => ({ code: language.code, name: languageName(language.code) }));
}

/** The title of a shelf: the client's words for the editorial, learning and new shelves, the name of the topic for a topic shelf. */
export function shelfTitle(shelf: CommunityShelf): string {
    switch (shelf.kind) {
        case 'editorial': return 'Выбор Мнемы';
        case 'learning': return 'Учат сейчас';
        case 'new': return 'Новое';
        case 'topic': return shelf.topic?.name ?? '';
    }
}

/** A stable key of a shelf inside one answer. */
export function shelfKey(shelf: CommunityShelf): string {
    return shelf.kind === 'topic' ? `topic:${shelf.topic?.id ?? ''}` : shelf.kind;
}

/** «Все» of a shelf is `/community` with the shelf's own browse query; the editorial shelf has none. */
export function moreParams(more: ShelfMore | null): Params | null {
    return more === null ? null : { ...(more.topic === undefined ? {} : { topic: more.topic }), ...(more.sort === undefined ? {} : { sort: more.sort }) };
}

/** One row of a shelf is shown; the rest is behind «Все». A shelf with fewer than four decks is not drawn at all. */
export const SHELF_ROW_MAX = 6;
export const SHELF_MIN_DECKS = 4;
/** A shelf without «Все» (the editorial one) has nowhere to send the rest, so it shows everything the server sends (`shelfMaxDecks`). */
export const SHELF_DECKS_MAX = 12;

/** How many cards of a shelf are drawn: one row where «Все» holds the rest, all of them where there is no «Все». */
export function shelfLimit(shelf: CommunityShelf): number {
    return shelf.more === null ? SHELF_DECKS_MAX : SHELF_ROW_MAX;
}

/** The wire card (the catalogue's, or an author's list entry) as the shared card shows it. `author` is what Identity said (a hidden profile is `username: null`); small audiences stay hidden. */
export function displayCard(card: AuthorDeckCard, author: PublicDeckAuthor, origin: string): PublicDeckCard {
    const spoken = card.targetLanguage ?? card.contentLanguage;
    return {
        id: card.code,
        title: card.title,
        description: card.description,
        topic: card.topic?.name ?? '',
        language: spoken === null ? '' : languageName(spoken),
        author,
        materialCount: card.itemCount,
        exerciseCount: card.exerciseCount,
        media: card.media,
        addedCount: shown(card.addedCount),
        learningNowCount: shown(card.learningNowCount),
        updatedAt: card.updatedAt,
        // An invitation-only deck has no slug and so no title in its address.
        shareUrl: card.slug === null ? `${origin}/d/${card.code}` : `${origin}/d/${card.code}/${encodeURIComponent(card.slug)}`
    };
}

function shown(count: number | null): number | null {
    return count !== null && count >= MIN_SHOWN_COUNT ? count : null;
}
