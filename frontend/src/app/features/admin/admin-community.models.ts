import { HttpErrorResponse } from '@angular/common/http';
import { COMPLAINT_DECK_CODE } from '../../shared/complaint.models';
import { AdminProtocolError, array, instant, integer, nullable, num, object, str, uuid } from './admin.models';

/**
 * Owner-console projections of the community catalogue (`contracts/community/catalog.json`, sections `editorial` and `anomalies`,
 * Community/7 and Community/3). The shelf «Выбор Мнемы» is one ordered list of public deck codes guarded by a row version; the
 * anomaly flags are a keyset list whose cursor is called `next`. Parsing is strict on types, the code alphabet and the limits.
 */
export const EDITORIAL_MAX = 24;
/** The public code of a deck: ten characters of base58 (the server is the judge of whether the deck exists and is public). */
export const DECK_CODE = COMPLAINT_DECK_CODE;
export const ANOMALY_PAGE = 50;

export interface EditorialEntry {
    readonly code: string;
    readonly title: string;
    /** `false`: the deck is hidden or no longer public; it stays in the list and the shelf skips it. */
    readonly listed: boolean;
    readonly addedAt: string;
}

export interface EditorialState {
    readonly rowVersion: string;
    readonly entries: readonly EditorialEntry[];
}

export interface EditorialCommand {
    readonly commandId: string;
    readonly codes: readonly string[];
}

export interface AnomalyFlag {
    readonly code: string;
    readonly title: string;
    readonly ownerId: string;
    readonly detectedAt: string;
    /** Copies made in the last 7 days. */
    readonly windowForks: number;
    /** The share of those copies made by accounts younger than 48 hours, 0..1. */
    readonly newShare: number;
    readonly dismissedAt: string | null;
}

export interface AnomalyPage {
    readonly items: readonly AnomalyFlag[];
    readonly next: string | null;
}

const deckCode = (value: unknown): string => {
    const code = str(value, 10);
    if (!DECK_CODE.test(code)) throw new AdminProtocolError();
    return code;
};

export function parseEditorialEntry(value: unknown): EditorialEntry {
    const o = object(value);
    if (typeof o['listed'] !== 'boolean') throw new AdminProtocolError();
    return { code: deckCode(o['code']), title: str(o['title'], 500), listed: o['listed'], addedAt: instant(o['addedAt']) };
}

export function parseEditorial(value: unknown): EditorialState {
    const o = object(value);
    const rowVersion = str(o['rowVersion'], 20);
    if (!/^\d+$/u.test(rowVersion)) throw new AdminProtocolError();
    const entries = array(o['entries'], parseEditorialEntry, EDITORIAL_MAX);
    if (new Set(entries.map(entry => entry.code)).size !== entries.length) throw new AdminProtocolError();
    return { rowVersion, entries };
}

export function parseAnomalyFlag(value: unknown): AnomalyFlag {
    const o = object(value);
    const share = num(o['newShare']);
    if (share > 1) throw new AdminProtocolError();
    return {
        code: deckCode(o['code']),
        title: str(o['title'], 500),
        ownerId: uuid(o['ownerId']),
        detectedAt: instant(o['detectedAt']),
        windowForks: integer(o['windowForks']),
        newShare: share,
        dismissedAt: nullable(o['dismissedAt'], instant)
    };
}

export function parseAnomalyPage(value: unknown): AnomalyPage {
    const o = object(value);
    return { items: array(o['entries'], parseAnomalyFlag, 100), next: nullable(o['next'], item => str(item, 4096)) };
}

export type DeckInput = { readonly code: string } | { readonly error: string };

const BAD_INPUT = 'Введите ссылку на колоду вида https://mnema.app/d/Kq7xT3mNpR или её код из 10 знаков.';

/**
 * A pasted public link `https://…/d/{code}[/{slug}]` or a bare code. Only the alphabet and the length are checked here;
 * whether the deck exists and is public is the server's answer on save.
 */
export function parseDeckInput(text: string): DeckInput {
    const value = text.trim();
    if (value === '') return { error: BAD_INPUT };
    if (DECK_CODE.test(value)) return { code: value };
    let url: URL;
    try {
        url = new URL(/^[a-z][a-z\d+.-]*:\/\//iu.test(value) ? value : `https://${value}`);
    } catch {
        return { error: BAD_INPUT };
    }
    if (url.protocol !== 'https:' && url.protocol !== 'http:') return { error: BAD_INPUT };
    const segments = url.pathname.split('/').filter(segment => segment !== '');
    if ((segments.length !== 2 && segments.length !== 3) || segments[0] !== 'd') return { error: BAD_INPUT };
    let code: string;
    try {
        code = decodeURIComponent(segments[1]);
    } catch {
        return { error: BAD_INPUT };
    }
    return DECK_CODE.test(code) ? { code } : { error: 'В ссылке неверный код колоды: он состоит из 10 знаков без 0, O, I и l.' };
}

/**
 * The codes a `400` names as unfit for the shelf. The problem body carries them as an extension member (an array of codes);
 * every array member is scanned so the page does not depend on the member's name, and anything that is not a deck code is ignored.
 */
export function offendingCodes(error: unknown): readonly string[] {
    if (!(error instanceof HttpErrorResponse) || error.status !== 400) return [];
    const body: unknown = error.error;
    if (body === null || typeof body !== 'object' || Array.isArray(body)) return [];
    const found = new Set<string>();
    const scan = (value: unknown, depth: number): void => {
        if (typeof value === 'string') {
            if (DECK_CODE.test(value)) found.add(value);
        } else if (depth < 3 && Array.isArray(value)) {
            for (const item of value) scan(item, depth + 1);
        } else if (depth < 3 && value !== null && typeof value === 'object') {
            for (const [key, item] of Object.entries(value)) if (key === 'code') scan(item, depth + 1);
        }
    };
    for (const [key, value] of Object.entries(body))
        if (Array.isArray(value) && !['type', 'title', 'detail', 'instance'].includes(key)) scan(value, 0);
    return [...found];
}
