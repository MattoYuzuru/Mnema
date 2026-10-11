import { HttpErrorResponse } from '@angular/common/http';

import { NativeDocument } from '../../content/native-document';
import { readRetainedNativeDocument } from '../../content/native-document-boundary';
import { ACCOUNT_ID } from '../../public-profile';
import { isCanonicalEntityId } from '../own-decks/own-deck.models';

/**
 * Wire shapes of the read-only public view of a deck (`contracts/decks/public-read.json`, Share/7). Parsing is strict:
 * a missing, mistyped or unknown field is a protocol failure, never a silently ignored extra. The answers carry no
 * deck id or revision on purpose; the code is the only name of the deck here.
 */

/** Ten characters of base58 (no `0`, `I`, `O`, `l`). */
export const PUBLIC_DECK_CODE = /^[1-9A-HJ-NP-Za-km-z]{10}$/u;
/** Materials and exercises per request; the server caps a page at 100. */
export const PUBLIC_PAGE_SIZE = 50;

export type PublicVisibility = 'PUBLIC' | 'LINK' | 'INVITE';
export type PublicAccess = 'OWNER' | 'GRANTEE' | 'PUBLIC' | 'LINK';

export interface PublicDeck {
    readonly code: string;
    readonly visibility: PublicVisibility;
    readonly access: PublicAccess;
    readonly title: string;
    readonly description: string;
    readonly memberCount: number;
    readonly exerciseCount: number;
    readonly publishedAt: string;
    readonly ownerId: string;
    /** The transliterated title of a PUBLIC deck; `null` for every other level (a link never shows a slug). */
    readonly slug: string | null;
}

export interface PublicMaterial {
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly ordinal: number;
    readonly title: string;
}

export interface PublicMaterialPage {
    readonly code: string;
    readonly total: number;
    readonly items: readonly PublicMaterial[];
    readonly nextCursor: string | null;
}

export interface PublicMaterialDocument {
    readonly code: string;
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly ordinal: number;
    readonly document: NativeDocument;
}

export interface PublicExercise {
    readonly exerciseId: string;
    readonly exerciseRevisionId: string;
    readonly ordinal: number;
    /** The mechanic name as the server sends it; the screen names the known ones and says «Упражнение» for a newer one. */
    readonly type: string;
    readonly enabled: boolean;
    /** A plain-text summary of the question (at most 200 code points), `null` when the question has no plain text. */
    readonly prompt: string | null;
}

export interface PublicExercisePage {
    readonly code: string;
    readonly total: number;
    readonly exercises: readonly PublicExercise[];
    readonly nextCursor: string | null;
}

/** What went wrong with a public read, in the words the screens need. */
export type PublicDeckFailureKind = 'not-found' | 'invite-only' | 'rate-limited' | 'busy' | 'stale' | 'unavailable';

export class PublicDeckFailure extends Error {
    /** Seconds the server asked the client to wait (`Retry-After`), when it said so. */
    constructor(readonly kind: PublicDeckFailureKind, readonly retryAfter: number | null = null) {
        super(`Public deck read failed: ${kind}`);
        this.name = 'PublicDeckFailure';
    }
}

/**
 * Classifies a failed public read. 404 is one answer for an unknown, private, rotated, deleted or unpublished deck;
 * 403 is `invite-only` only when the problem says `DECK_INVITE_ONLY` (a valid token without scope is `ACCESS_DENIED`
 * and is not an invitation screen); 429 and 503 carry the delay; 412 is a cursor of an older publication.
 */
export function publicFailureOf(error: unknown): PublicDeckFailure {
    if (error instanceof PublicDeckFailure) return error;
    if (!(error instanceof HttpErrorResponse)) return new PublicDeckFailure('unavailable');
    const code = problemCode(error.error);
    switch (error.status) {
        case 404: return new PublicDeckFailure('not-found');
        case 403: return new PublicDeckFailure(code === 'DECK_INVITE_ONLY' ? 'invite-only' : 'unavailable');
        case 412: return new PublicDeckFailure('stale');
        case 429: return new PublicDeckFailure('rate-limited', retryAfterOf(error));
        case 503: return new PublicDeckFailure(code === 'PUBLIC_READ_BUSY' ? 'busy' : 'unavailable', retryAfterOf(error));
        default: return new PublicDeckFailure('unavailable');
    }
}

function problemCode(body: unknown): string | null {
    return isRecord(body) && typeof body['code'] === 'string' ? body['code'] : null;
}

function retryAfterOf(error: HttpErrorResponse): number | null {
    const header = error.headers?.get('Retry-After') ?? null;
    const body = isRecord(error.error) ? error.error['retryAfter'] : null;
    const seconds = header !== null && /^\d{1,5}$/u.test(header) ? Number(header) : body;
    return typeof seconds === 'number' && Number.isInteger(seconds) && seconds >= 1 && seconds <= 3600 ? seconds : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function exact(value: unknown, required: readonly string[], optional: readonly string[] = []): Record<string, unknown> {
    if (!isRecord(value)) throw new PublicDeckFailure('unavailable');
    const keys = Object.keys(value);
    if (!required.every(key => Object.hasOwn(value, key)) || keys.some(key => !required.includes(key) && !optional.includes(key))) {
        throw new PublicDeckFailure('unavailable');
    }
    return value;
}

function text(value: unknown, maximum: number, allowEmpty: boolean): string {
    if (typeof value !== 'string' || (!allowEmpty && value.length === 0) || Array.from(value).length > maximum) {
        throw new PublicDeckFailure('unavailable');
    }
    return value;
}

function count(value: unknown, maximum: number): number {
    if (!Number.isSafeInteger(value) || (value as number) < 0 || (value as number) > maximum) throw new PublicDeckFailure('unavailable');
    return value as number;
}

function entity(value: unknown): string {
    if (typeof value !== 'string' || !isCanonicalEntityId(value)) throw new PublicDeckFailure('unavailable');
    return value.toLowerCase();
}

function cursor(value: unknown): string | null {
    if (value === null) return null;
    if (typeof value !== 'string' || value.length === 0 || value.length > 4096) throw new PublicDeckFailure('unavailable');
    return value;
}

function instant(value: unknown): string {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/u.test(value) || !Number.isFinite(Date.parse(value))) {
        throw new PublicDeckFailure('unavailable');
    }
    return value;
}

function sameCode(value: unknown, code: string): void {
    if (value !== code) throw new PublicDeckFailure('unavailable');
}

const VISIBILITIES: readonly PublicVisibility[] = ['PUBLIC', 'LINK', 'INVITE'];
const ACCESSES: readonly PublicAccess[] = ['OWNER', 'GRANTEE', 'PUBLIC', 'LINK'];
/** A path segment: no separator, no query or fragment, no whitespace, no escape. */
const SLUG = /^[^\s/?#%\\]{1,80}$/u;

export function parsePublicDeck(value: unknown, code: string): PublicDeck {
    const f = exact(value, ['code', 'visibility', 'access', 'title', 'description', 'memberCount', 'exerciseCount', 'publishedAt', 'ownerId'], ['slug']);
    sameCode(f['code'], code);
    const visibility = f['visibility'];
    const access = f['access'];
    if (!VISIBILITIES.includes(visibility as PublicVisibility) || !ACCESSES.includes(access as PublicAccess)) throw new PublicDeckFailure('unavailable');
    const slug = f['slug'];
    // «slug is present only when visibility is PUBLIC»: a link must never show one.
    if (slug !== undefined && (visibility !== 'PUBLIC' || typeof slug !== 'string' || !SLUG.test(slug))) throw new PublicDeckFailure('unavailable');
    if (typeof f['ownerId'] !== 'string' || !ACCOUNT_ID.test(f['ownerId'])) throw new PublicDeckFailure('unavailable');
    return {
        code, visibility: visibility as PublicVisibility, access: access as PublicAccess,
        title: text(f['title'], 400, false), description: text(f['description'], 8192, true),
        memberCount: count(f['memberCount'], 100_000), exerciseCount: count(f['exerciseCount'], 1_000_000),
        publishedAt: instant(f['publishedAt']), ownerId: f['ownerId'].toLowerCase(), slug: typeof slug === 'string' ? slug : null
    };
}

export function parsePublicMaterialPage(value: unknown, code: string, limit: number): PublicMaterialPage {
    const f = exact(value, ['code', 'total', 'items', 'nextCursor']);
    sameCode(f['code'], code);
    if (!Array.isArray(f['items']) || f['items'].length > limit) throw new PublicDeckFailure('unavailable');
    const items = f['items'].map((entry): PublicMaterial => {
        const item = exact(entry, ['memberKey', 'itemRevisionId', 'ordinal', 'title']);
        return { memberKey: entity(item['memberKey']), itemRevisionId: entity(item['itemRevisionId']),
            ordinal: count(item['ordinal'], 99_999), title: text(item['title'], 400, true) };
    });
    return { code, total: count(f['total'], 100_000), items, nextCursor: cursor(f['nextCursor']) };
}

export function parsePublicMaterialDocument(value: unknown, code: string, memberKey: string): PublicMaterialDocument {
    const f = exact(value, ['code', 'memberKey', 'itemRevisionId', 'ordinal', 'formatVersion', 'document']);
    sameCode(f['code'], code);
    if (entity(f['memberKey']) !== memberKey.toLowerCase() || f['formatVersion'] !== 1) throw new PublicDeckFailure('unavailable');
    let document: NativeDocument;
    try { document = readRetainedNativeDocument(f['document']); } catch { throw new PublicDeckFailure('unavailable'); }
    return { code, memberKey: memberKey.toLowerCase(), itemRevisionId: entity(f['itemRevisionId']), ordinal: count(f['ordinal'], 99_999), document };
}

export function parsePublicExercisePage(value: unknown, code: string, limit: number): PublicExercisePage {
    const f = exact(value, ['code', 'total', 'exercises', 'nextCursor']);
    sameCode(f['code'], code);
    if (!Array.isArray(f['exercises']) || f['exercises'].length > limit) throw new PublicDeckFailure('unavailable');
    const exercises = f['exercises'].map((entry): PublicExercise => {
        const item = exact(entry, ['exerciseId', 'exerciseRevisionId', 'ordinal', 'type', 'enabled', 'prompt']);
        if (typeof item['type'] !== 'string' || !/^[A-Z][A-Z_]{0,39}$/u.test(item['type']) || typeof item['enabled'] !== 'boolean') {
            throw new PublicDeckFailure('unavailable');
        }
        return { exerciseId: entity(item['exerciseId']), exerciseRevisionId: entity(item['exerciseRevisionId']),
            ordinal: count(item['ordinal'], 999_999), type: item['type'], enabled: item['enabled'],
            prompt: item['prompt'] === null ? null : text(item['prompt'], 200 + 1, true) };
    });
    return { code, total: count(f['total'], 1_000_000), exercises, nextCursor: cursor(f['nextCursor']) };
}

const PUBLISHED = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' });
const PUBLISHED_WITH_YEAR = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });

/** «Опубликовано 3 октября»; the year is added when it is not the current one. An unreadable date gives `null`. */
export function publishedLabel(iso: string, now: Date = new Date()): string | null {
    const time = Date.parse(iso);
    if (Number.isNaN(time)) return null;
    const date = new Date(time);
    const format = date.getFullYear() === now.getFullYear() ? PUBLISHED : PUBLISHED_WITH_YEAR;
    return `Опубликовано ${format.format(date).replace(/\s*г\.$/u, '')}`;
}

/** Who may see the deck, in the words of the level mark. */
export const LEVEL_LABELS: Readonly<Record<PublicVisibility, string>> = {
    PUBLIC: 'Публичная', LINK: 'По ссылке', INVITE: 'По приглашению'
};

/** The address of the page: the slug only for a PUBLIC deck, never for a link. */
export function canonicalPath(deck: Pick<PublicDeck, 'code' | 'visibility' | 'slug'>): readonly string[] {
    return deck.visibility === 'PUBLIC' && deck.slug !== null ? ['/d', deck.code, deck.slug] : ['/d', deck.code];
}
