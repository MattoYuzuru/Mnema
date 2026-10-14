import { CATALOG_LEVELS, CATALOG_MECHANICS, CATALOG_MEDIA, CATALOG_SORTS, CatalogMechanic, CatalogMedia, CatalogSort } from '../../shared/catalog-filters';
import { ACCOUNT_ID } from '../../public-profile';
import { PublicDeckFailure } from '../public-deck/public-deck.models';

/**
 * Wire shapes of the community catalogue (`contracts/community/catalog.json`, epic 3): the facets, the shelves and a page of browse or
 * search. Parsing is strict like the public deck parser: a missing, mistyped or unknown field is a protocol failure
 * ({@link PublicDeckFailure} `unavailable`), never a silently ignored extra. The decks of an author have their own list shape (`access` with a card,
 * the sum of «Учат сейчас»), and only there a card may be an invitation-only deck without a slug.
 */

/** Cards per request; the contract's default page is 24 and the cap 48. */
export const COMMUNITY_PAGE_SIZE = 24;
export const COMMUNITY_PAGE_MAX = 48;
/** A text query longer than this many code points is refused by the server; the input already limits it. */
export const COMMUNITY_QUERY_MAX = 100;
/** The counts of browse stop here (`total` with `totalCapped`). */
const TOTAL_MAX = 1000;

/** A topic as the catalogue names it. */
export interface CommunityTopic {
    readonly id: string;
    readonly name: string;
    readonly parentId: string | null;
}

/** A deck of the catalogue. No deck id, revision, rank or exact small audience: only what the server publishes. */
export interface CommunityCard {
    readonly code: string;
    /** Always present here: every deck of the catalogue is PUBLIC, and its page is `/d/{code}/{slug}`. */
    readonly slug: string;
    readonly title: string;
    readonly description: string;
    readonly topic: CommunityTopic | null;
    readonly contentLanguage: string | null;
    readonly targetLanguage: string | null;
    readonly level: string | null;
    readonly tags: readonly string[];
    readonly itemCount: number;
    readonly exerciseCount: number;
    readonly media: readonly CatalogMedia[];
    readonly mechanics: readonly CatalogMechanic[];
    /** `null` below 10, otherwise floored to two significant digits by the server. */
    readonly addedCount: number | null;
    readonly learningNowCount: number | null;
    readonly updatedAt: string;
    readonly ownerId: string;
}

/**
 * A deck in an author's list. The same card as the catalogue's, except that an invitation-only deck (`access: 'GRANTEE'`) has no slug: its
 * address is `/d/{code}`, with no title in it.
 */
export interface AuthorDeckCard extends Omit<CommunityCard, 'slug'> {
    readonly slug: string | null;
}

/** `PUBLIC`: a public deck. `GRANTEE`: an invitation-only deck of the author that the signed-in viewer has a grant for («Доступна вам»). */
export type AuthorDeckAccess = 'PUBLIC' | 'GRANTEE';

/** One row of an author's list: the card with the viewer's access to it. The identity of a row is `code`. */
export interface AuthorDeckEntry extends AuthorDeckCard {
    readonly access: AuthorDeckAccess;
}

export interface AuthorDeckPage {
    /** The sum of «Учат сейчас» over the author's public decks; `null` below 10 (the server's `audienceMinShown`). */
    readonly learningNowTotal: number | null;
    readonly decks: readonly AuthorDeckEntry[];
    readonly nextCursor: string | null;
}

export interface FacetTopic { readonly id: string; readonly parentId: string | null; readonly name: string; readonly count: number }
export interface FacetLanguage { readonly code: string; readonly count: number }
export interface FacetLevel { readonly level: string; readonly count: number }

export interface CommunityFacets {
    readonly listedCount: number;
    readonly topics: readonly FacetTopic[];
    readonly languages: readonly FacetLanguage[];
    readonly levels: readonly FacetLevel[];
}

export type ShelfKind = 'editorial' | 'learning' | 'new' | 'topic';

/** The address parameters behind a shelf's «Все». */
export interface ShelfMore {
    readonly topic?: string;
    readonly sort?: CatalogSort;
}

export interface CommunityShelf {
    readonly kind: ShelfKind;
    readonly topic: CommunityTopic | null;
    readonly decks: readonly CommunityCard[];
    readonly more: ShelfMore | null;
}

export interface CommunityShelves {
    readonly listedCount: number;
    readonly shelves: readonly CommunityShelf[];
}

export interface CommunityDeckPage {
    readonly decks: readonly CommunityCard[];
    readonly total: number;
    /** The server counted only up to `total`; the real number may be larger. */
    readonly totalCapped: boolean;
    readonly nextCursor: string | null;
}

function fail(): never { throw new PublicDeckFailure('unavailable'); }

function isRecord(value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function exact(value: unknown, required: readonly string[], optional: readonly string[] = []): Record<string, unknown> {
    if (!isRecord(value)) return fail();
    const keys = Object.keys(value);
    if (!required.every(key => Object.hasOwn(value, key)) || keys.some(key => !required.includes(key) && !optional.includes(key))) return fail();
    return value;
}

function text(value: unknown, maximum: number, allowEmpty: boolean): string {
    if (typeof value !== 'string' || (!allowEmpty && value.length === 0) || Array.from(value).length > maximum) return fail();
    return value;
}

function count(value: unknown, maximum: number, minimum = 0): number {
    if (!Number.isSafeInteger(value) || (value as number) < minimum || (value as number) > maximum) return fail();
    return value as number;
}

function cursor(value: unknown): string | null {
    if (value === null) return null;
    if (typeof value !== 'string' || value.length === 0 || value.length > 4096) return fail();
    return value;
}

function instant(value: unknown): string {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/u.test(value) || !Number.isFinite(Date.parse(value))) return fail();
    return value;
}

/** The server's slug alphabet: transliterated lowercase words joined by single hyphens, at most 80 characters. */
const SLUG = /^(?=.{1,80}$)[a-z0-9]+(?:-[a-z0-9]+)*$/u;
const TOPIC_ID = /^[\p{L}\p{N}][\p{L}\p{N}_.:-]{0,63}$/u;
const CODE = /^[1-9A-HJ-NP-Za-km-z]{10}$/u;
/** A BCP 47 language tag as the catalogue sends it (`es`, `pt-BR`). */
const LANGUAGE = /^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}$/u;

function topicId(value: unknown): string {
    if (typeof value !== 'string' || !TOPIC_ID.test(value)) return fail();
    return value;
}

function language(value: unknown): string | null {
    if (value === null) return null;
    if (typeof value !== 'string' || !LANGUAGE.test(value)) return fail();
    return value;
}

function parseTopic(value: unknown): CommunityTopic {
    const f = exact(value, ['id', 'name', 'parentId']);
    return { id: topicId(f['id']), name: text(f['name'], 100, false), parentId: f['parentId'] === null ? null : topicId(f['parentId']) };
}

/** A list of distinct known values, as the server publishes it (`media`, `mechanics`). */
function known<T extends string>(value: unknown, allowed: readonly { readonly value: T }[]): readonly T[] {
    if (!Array.isArray(value) || value.length > allowed.length) return fail();
    const seen = new Set<unknown>();
    return value.map((item): T => {
        const match = allowed.find(entry => entry.value === item);
        if (match === undefined || seen.has(item)) return fail();
        seen.add(item);
        return match.value;
    });
}

function audience(value: unknown): number | null {
    return value === null ? null : count(value, 1_000_000_000);
}

const CARD_FIELDS = ['code', 'slug', 'title', 'description', 'topic', 'contentLanguage', 'targetLanguage', 'level', 'tags', 'itemCount', 'exerciseCount',
    'media', 'mechanics', 'addedCount', 'learningNowCount', 'updatedAt', 'ownerId'] as const;

export function parseCommunityCard(value: unknown): CommunityCard {
    const card = parseCardFields(value, false);
    return card.slug === null ? fail() : { ...card, slug: card.slug };
}

/** `slugOptional`: the card may be an invitation-only deck of an author's list, which has no slug (`null`). Any slug that is present must be a slug. */
function parseCardFields(value: unknown, slugOptional: boolean): AuthorDeckCard {
    const f = exact(value, CARD_FIELDS);
    if (typeof f['code'] !== 'string' || !CODE.test(f['code'])) return fail();
    if (!(slugOptional && f['slug'] === null) && (typeof f['slug'] !== 'string' || !SLUG.test(f['slug']))) return fail();
    if (typeof f['ownerId'] !== 'string' || !ACCOUNT_ID.test(f['ownerId'])) return fail();
    const level = f['level'];
    if (level !== null && !CATALOG_LEVELS.some(entry => entry.value === level)) return fail();
    if (!Array.isArray(f['tags']) || f['tags'].length > 50) return fail();
    return {
        code: f['code'], slug: f['slug'] as string | null, title: text(f['title'], 400, false), description: text(f['description'], 8192, true),
        topic: f['topic'] === null ? null : parseTopic(f['topic']),
        contentLanguage: language(f['contentLanguage']), targetLanguage: language(f['targetLanguage']), level: level as string | null,
        tags: f['tags'].map(tag => text(tag, 100, false)),
        itemCount: count(f['itemCount'], 100_000), exerciseCount: count(f['exerciseCount'], 1_000_000),
        media: known(f['media'], CATALOG_MEDIA), mechanics: known(f['mechanics'], CATALOG_MECHANICS),
        addedCount: audience(f['addedCount']), learningNowCount: audience(f['learningNowCount']),
        updatedAt: instant(f['updatedAt']), ownerId: f['ownerId'].toLowerCase()
    };
}

function cards(value: unknown, maximum: number): readonly CommunityCard[] {
    if (!Array.isArray(value) || value.length > maximum) return fail();
    return value.map(parseCommunityCard);
}

export function parseCommunityFacets(value: unknown): CommunityFacets {
    const f = exact(value, ['listedCount', 'topics', 'languages', 'levels']);
    if (!Array.isArray(f['topics']) || !Array.isArray(f['languages']) || !Array.isArray(f['levels'])) return fail();
    return {
        listedCount: count(f['listedCount'], 100_000_000),
        topics: f['topics'].map((entry): FacetTopic => {
            const topic = exact(entry, ['id', 'parentId', 'name', 'count']);
            return { id: topicId(topic['id']), parentId: topic['parentId'] === null ? null : topicId(topic['parentId']),
                name: text(topic['name'], 100, false), count: count(topic['count'], 100_000_000, 1) };
        }),
        languages: f['languages'].map((entry): FacetLanguage => {
            const item = exact(entry, ['code', 'count']);
            const code = language(item['code']);
            return code === null ? fail() : { code, count: count(item['count'], 100_000_000, 1) };
        }),
        levels: f['levels'].map((entry): FacetLevel => {
            const item = exact(entry, ['level', 'count']);
            if (!CATALOG_LEVELS.some(level => level.value === item['level'])) return fail();
            return { level: item['level'] as string, count: count(item['count'], 100_000_000, 1) };
        })
    };
}

const SHELF_KINDS: readonly ShelfKind[] = ['editorial', 'learning', 'new', 'topic'];

function parseMore(value: unknown): ShelfMore {
    const f = exact(value, [], ['topic', 'sort']);
    const sort = f['sort'];
    if (sort !== undefined && !CATALOG_SORTS.some(entry => entry.value === sort)) return fail();
    return {
        ...(f['topic'] === undefined ? {} : { topic: topicId(f['topic']) }),
        ...(sort === undefined ? {} : { sort: sort as CatalogSort })
    };
}

export function parseCommunityShelves(value: unknown): CommunityShelves {
    const f = exact(value, ['listedCount', 'shelves']);
    if (!Array.isArray(f['shelves']) || f['shelves'].length > 12) return fail();
    return {
        listedCount: count(f['listedCount'], 100_000_000),
        shelves: f['shelves'].map((entry): CommunityShelf => {
            const shelf = exact(entry, ['kind', 'decks'], ['topic', 'more']);
            const kind = SHELF_KINDS.find(candidate => candidate === shelf['kind']);
            if (kind === undefined) return fail();
            // A topic shelf names its topic; no other shelf does.
            if ((kind === 'topic') !== (shelf['topic'] !== undefined)) return fail();
            return {
                kind, decks: cards(shelf['decks'], 12),
                topic: shelf['topic'] === undefined ? null : parseTopic(shelf['topic']),
                more: shelf['more'] === undefined ? null : parseMore(shelf['more'])
            };
        })
    };
}

/** `limit` is the page size that was asked for: more cards than that is a protocol failure. */
export function parseCommunityDeckPage(value: unknown, limit: number = COMMUNITY_PAGE_MAX): CommunityDeckPage {
    const f = exact(value, ['decks', 'total', 'totalCapped', 'nextCursor']);
    if (typeof f['totalCapped'] !== 'boolean') return fail();
    return { decks: cards(f['decks'], limit), total: count(f['total'], TOTAL_MAX), totalCapped: f['totalCapped'], nextCursor: cursor(f['nextCursor']) };
}

function parseAuthorDeck(value: unknown): AuthorDeckEntry {
    const f = exact(value, ['access', 'deck']);
    if (f['access'] !== 'PUBLIC' && f['access'] !== 'GRANTEE') return fail();
    const access: AuthorDeckAccess = f['access'];
    const card = parseCardFields(f['deck'], access === 'GRANTEE');
    if (access === 'PUBLIC') return card.slug === null ? fail() : { ...card, access };
    // The server keeps back what would show an invitation-only deck in a catalogue: its address has no title and its numbers are not published.
    if (card.slug !== null || card.topic !== null || card.addedCount !== null || card.learningNowCount !== null
        || card.media.length > 0 || card.mechanics.length > 0) return fail();
    return { ...card, access };
}

/** `limit` is the page size that was asked for: more rows than that is a protocol failure, and so is a code that appears twice in one page. */
export function parseAuthorDeckPage(value: unknown, limit: number = COMMUNITY_PAGE_MAX): AuthorDeckPage {
    const f = exact(value, ['learningNowTotal', 'decks', 'nextCursor']);
    if (!Array.isArray(f['decks']) || f['decks'].length > limit) return fail();
    const decks = f['decks'].map(parseAuthorDeck);
    if (new Set(decks.map(entry => entry.code)).size !== decks.length) return fail();
    return { learningNowTotal: audience(f['learningNowTotal']), decks, nextCursor: cursor(f['nextCursor']) };
}
