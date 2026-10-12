import { formatDate } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';

import { UPDATE_TEXT } from '../../../shared/copy-text';
import { OwnDeckProtocolError, isCanonicalEntityId } from '../own-deck.models';
import { WIRE_KINDS, WireKind, newUpdateCommandId } from './update-plan.models';

/**
 * Wire shapes of «Что нового» and of taking a unit back (`contracts/decks/updates.json`: `history`, `seen`, `revert`; Updates/6 part B).
 * Parsing is strict: a body that is not exactly the contract's is a protocol error. The contract words are mirrored here once; the texts
 * are `UPDATE_TEXT`.
 */

/** `historyDefaultLimit`, `historyMaxLimit`: the screen asks for the default and no more. */
export const HISTORY_DEFAULT_LIMIT = 5;
export const HISTORY_MAX_LIMIT = 20;
/** `historyMaxUnits`: an entry lists at most this many units. */
export const HISTORY_MAX_UNITS = 100;
/** `revertMaxWith`. */
export const REVERT_MAX_WITH = 20;

export const HISTORY_MODES = ['AUTO', 'MANUAL'] as const;
export type HistoryMode = (typeof HISTORY_MODES)[number];

export const HISTORY_CHANGES = ['ADDED', 'CHANGED', 'REMOVED', 'REORDERED'] as const;
export type HistoryChange = (typeof HISTORY_CHANGES)[number];

export const HISTORY_UNIT_STATES = ['CURRENT', 'RETURNED', 'CHANGED_SINCE'] as const;
export type HistoryUnitState = (typeof HISTORY_UNIT_STATES)[number];

export const REVERTIBLE_KINDS = ['MATERIAL', 'EXERCISE'] as const;
export type RevertibleKind = (typeof REVERTIBLE_KINDS)[number];

export const REVERT_REASONS = ['unit_needed', 'material_still_used', 'duplicate_unit', 'unit_not_returnable', 'publication_refused'] as const;
export type RevertReason = (typeof REVERT_REASONS)[number];

const TITLE_MAX = 160;
const UNIT_ID = /^(METADATA|MATERIAL|EXERCISE|OBJECTIVE|ORDER):[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

/** One unit an apply changed in the copy, and where it stands now. */
export interface HistoryUnit {
    readonly unitId: string;
    readonly kind: WireKind;
    readonly key: string;
    readonly change: HistoryChange;
    /** The title at the apply; empty for a taken-down revision, `null` for the order unit. */
    readonly title: string | null;
    /** `null` for the order and the title of the deck. */
    readonly state: HistoryUnitState | null;
    /** Only a material or an exercise exactly as the apply made it, of a copy that is not detached. */
    readonly revertible: boolean;
}

/** One apply that made a revision of the copy. */
export interface HistoryEntry {
    readonly deckRevisionId: string;
    readonly mode: HistoryMode;
    readonly appliedAt: string;
    readonly seen: boolean;
    readonly counts: { readonly applied: number; readonly pending: number };
    readonly units: readonly HistoryUnit[];
    readonly unitsTruncated: boolean;
}

export interface UpdateHistory {
    readonly deckId: string;
    readonly entries: readonly HistoryEntry[];
}

export interface RevertCommand {
    readonly commandId: string;
    /** The OTHER units of the same apply that are returned in the same command (`unit_needed`). */
    readonly with: readonly string[];
}

export interface Reverted {
    readonly commandId: string;
    readonly deckRevisionId: string;
    /** The units that were returned, the one of the path first. */
    readonly units: readonly string[];
}

export function newRevertCommandId(): string {
    return newUpdateCommandId();
}

// ---------------------------------------------------------------------------------------------
// Reading
// ---------------------------------------------------------------------------------------------

export function parseUpdateHistory(value: unknown, deckId: string, limit: number): UpdateHistory {
    const object = record(value);
    exactKeys(object, ['deckId', 'entries']);
    sameId(object['deckId'], deckId, 'History identity does not match the request.');
    const entries = array(object['entries'], 'entries').map(parseEntry);
    if (entries.length > limit) throw new OwnDeckProtocolError('More entries than asked for.');
    if (new Set(entries.map(entry => entry.deckRevisionId)).size !== entries.length) throw new OwnDeckProtocolError('An entry is listed twice.');
    return { deckId: deckId.toLowerCase(), entries };
}

function parseEntry(value: unknown): HistoryEntry {
    const object = record(value);
    exactKeys(object, ['deckRevisionId', 'upstreamRevisionId', 'mode', 'appliedAt', 'seen', 'counts', 'units', 'unitsTruncated']);
    const deckRevisionId = entityId(object['deckRevisionId'], 'deckRevisionId');
    entityId(object['upstreamRevisionId'], 'upstreamRevisionId');
    const mode = oneOf(object['mode'], HISTORY_MODES, 'mode');
    const appliedAt = object['appliedAt'];
    if (typeof appliedAt !== 'string' || Number.isNaN(Date.parse(appliedAt))) throw new OwnDeckProtocolError('Invalid apply time.');
    const counts = record(object['counts']);
    exactKeys(counts, ['applied', 'pending']);
    if (typeof object['seen'] !== 'boolean' || typeof object['unitsTruncated'] !== 'boolean') throw new OwnDeckProtocolError('Invalid history flag.');
    const units = array(object['units'], 'units').map(parseUnit);
    if (units.length > HISTORY_MAX_UNITS) throw new OwnDeckProtocolError('Too many units in an entry.');
    if (new Set(units.map(unit => unit.unitId)).size !== units.length) throw new OwnDeckProtocolError('A unit is listed twice.');
    return {
        deckRevisionId: deckRevisionId.toLowerCase(), mode, appliedAt, seen: object['seen'],
        counts: { applied: count(counts['applied']), pending: count(counts['pending']) }, units, unitsTruncated: object['unitsTruncated']
    };
}

function parseUnit(value: unknown): HistoryUnit {
    const object = record(value);
    exactKeys(object, ['unitId', 'kind', 'key', 'change', 'title', 'previousRevisionId', 'revisionId', 'state', 'revertible']);
    const kind = oneOf(object['kind'], WIRE_KINDS, 'unit kind');
    const change = oneOf(object['change'], HISTORY_CHANGES, 'change');
    const key = object['key'];
    if (typeof key !== 'string' || !isCanonicalEntityId(key)) throw new OwnDeckProtocolError('Invalid unit key.');
    const unitId = object['unitId'];
    if (typeof unitId !== 'string' || unitId !== `${kind}:${key}` || !UNIT_ID.test(unitId)) throw new OwnDeckProtocolError('Invalid unit identity.');
    const title = object['title'];
    if (title !== null && (typeof title !== 'string' || Array.from(title).length > TITLE_MAX)) throw new OwnDeckProtocolError('Invalid unit title.');
    const previous = object['previousRevisionId'];
    const revision = object['revisionId'];
    for (const id of [previous, revision]) if (id !== null) entityId(id, 'revision');
    const carriesRevisions = kind !== 'METADATA' && kind !== 'ORDER';
    // METADATA and ORDER carry no revisions and no state; the others say what the apply replaced (none for an addition) and made (none for a removal).
    if (carriesRevisions ? (change === 'ADDED') !== (previous === null) || (change === 'REMOVED') !== (revision === null) || change === 'REORDERED' || (previous === null && revision === null)
        : previous !== null || revision !== null) throw new OwnDeckProtocolError('Unit revisions disagree with its change.');
    if ((change === 'REORDERED') !== (kind === 'ORDER')) throw new OwnDeckProtocolError('Only the order moves.');
    if ((kind === 'ORDER') !== (title === null)) throw new OwnDeckProtocolError('Unit title disagrees with its kind.');
    const state = object['state'];
    if (state !== null) oneOf(state, HISTORY_UNIT_STATES, 'unit state');
    if ((state === null) === carriesRevisions) throw new OwnDeckProtocolError('Unit state disagrees with its kind.');
    const revertible = object['revertible'];
    if (typeof revertible !== 'boolean') throw new OwnDeckProtocolError('Invalid revertible flag.');
    // A unit that is not what the apply made it, or is not a material or an exercise, can never be returned.
    if (revertible && (!(REVERTIBLE_KINDS as readonly string[]).includes(kind) || state !== 'CURRENT')) throw new OwnDeckProtocolError('A unit that cannot be returned is marked revertible.');
    return { unitId, kind, key: key.toLowerCase(), change, title: title as string | null, state: state as HistoryUnitState | null, revertible };
}

/** `POST …:seen`: the entry is marked. */
export function parseSeen(value: unknown, deckId: string, deckRevisionId: string): true {
    const object = record(value);
    exactKeys(object, ['deckId', 'deckRevisionId', 'seen']);
    sameId(object['deckId'], deckId, 'Seen answer identity does not match the request.');
    sameId(object['deckRevisionId'], deckRevisionId, 'Seen answer entry does not match the request.');
    if (object['seen'] !== true) throw new OwnDeckProtocolError('Unexpected seen answer.');
    return true;
}

/** `POST …:revert`: 200 `REVERTED` (also the stored answer of the same command again). */
export function parseReverted(value: unknown, status: number, commandId: string, unitId: string): Reverted {
    const object = record(value);
    exactKeys(object, ['commandId', 'state', 'deckRevisionId', 'units']);
    if (status !== 200 || object['state'] !== 'REVERTED') throw new OwnDeckProtocolError('Unexpected revert answer.');
    if (typeof object['commandId'] !== 'string' || object['commandId'].toLowerCase() !== commandId.toLowerCase()) {
        throw new OwnDeckProtocolError('Revert acknowledgement command ID does not match the request.');
    }
    const units = array(object['units'], 'units').map(item => {
        if (typeof item !== 'string' || !UNIT_ID.test(item)) throw new OwnDeckProtocolError('Invalid returned unit.');
        return item;
    });
    if (units.length === 0 || units.length > REVERT_MAX_WITH + 1 || units[0]!.toLowerCase() !== unitId.toLowerCase() || new Set(units).size !== units.length) {
        throw new OwnDeckProtocolError('Returned units disagree with the request.');
    }
    return { commandId: commandId.toLowerCase(), deckRevisionId: entityId(object['deckRevisionId'], 'deckRevisionId').toLowerCase(), units };
}

// ---------------------------------------------------------------------------------------------
// Failures
// ---------------------------------------------------------------------------------------------

export type RevertFailure =
    /** 400 `unit_needed`: the group of the same apply must be returned together; `units` are the ones to send in `with`. */
    | { readonly kind: 'unit-needed'; readonly unitId: string | null; readonly units: readonly string[] }
    /** 400 `material_still_used`: an exercise of the copy that was not part of the apply is bound to the material; `dependency` is it. */
    | { readonly kind: 'still-used'; readonly unitId: string | null; readonly dependency: string | null }
    | { readonly kind: 'not-returnable' }
    /** 400 `publication_refused`, `duplicate_unit` or a 400 without a reason of the contract: nothing was written. */
    | { readonly kind: 'refused' }
    /** 409 `UPDATE_REVERT_STALE`: `unitId` is the unit that is not what the apply made it; absent when the copy moved while the command was built. */
    | { readonly kind: 'stale'; readonly unitId: string | null }
    | { readonly kind: 'detached' }
    | { readonly kind: 'preparing' }
    | { readonly kind: 'not-found' }
    | { readonly kind: 'rate-limited' }
    /** No answer, a 5xx (a busy server included) or an answer that does not match the contract: the outcome is unknown, the same command may be sent again. */
    | { readonly kind: 'unknown' }
    | { readonly kind: 'other' };

export function revertFailureOf(error: unknown): RevertFailure {
    if (!(error instanceof HttpErrorResponse)) return { kind: 'unknown' };
    const body = error.error !== null && typeof error.error === 'object' && !Array.isArray(error.error) ? error.error as Record<string, unknown> : {};
    const code = typeof body['code'] === 'string' ? body['code'] : null;
    const unit = (value: unknown): string | null => typeof value === 'string' && UNIT_ID.test(value) ? value : null;
    switch (error.status) {
        case 0: return { kind: 'unknown' };
        case 400: {
            const reason = REVERT_REASONS.find(candidate => candidate === body['reason']);
            if (reason === 'unit_needed') {
                const units = Array.isArray(body['units']) ? body['units'].filter((item): item is string => unit(item) !== null) : [];
                return { kind: 'unit-needed', unitId: unit(body['unitId']), units: [...new Set(units)] };
            }
            if (reason === 'material_still_used') return { kind: 'still-used', unitId: unit(body['unitId']), dependency: unit(body['dependency']) };
            return reason === 'unit_not_returnable' ? { kind: 'not-returnable' } : { kind: 'refused' };
        }
        case 404: return { kind: 'not-found' };
        case 409:
            return code === 'UPDATE_REVERT_STALE' ? { kind: 'stale', unitId: unit(body['unitId']) }
                : code === 'FORK_DETACHED' ? { kind: 'detached' } : code === 'DECK_PREPARING' ? { kind: 'preparing' } : { kind: 'other' };
        case 429: return { kind: 'rate-limited' };
        default: return error.status >= 500 ? { kind: 'unknown' } : { kind: 'other' };
    }
}

// ---------------------------------------------------------------------------------------------
// Words
// ---------------------------------------------------------------------------------------------

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * «5 минут назад», «вчера»: the apply time in words relative to `now`; `null` when it is a week or more ago (the exact date stands alone) or in
 * the future (a clock that differs). The exact time is always shown next to it by the page, in a `<time>` element.
 */
export function relativeWhen(appliedAt: string, now: number): string | null {
    const age = now - Date.parse(appliedAt);
    if (Number.isNaN(age) || age < -MINUTE || age >= 7 * DAY) return null;
    if (age < MINUTE) return UPDATE_TEXT.justNow;
    const format = new Intl.RelativeTimeFormat('ru', { numeric: 'auto' });
    if (age < HOUR) return format.format(-Math.floor(age / MINUTE), 'minute');
    if (age < DAY) return format.format(-Math.floor(age / HOUR), 'hour');
    return format.format(-Math.floor(age / DAY), 'day');
}

/** How a time is written next to «Что нового»: the relative phrase when there is one, and the exact date and time always. */
export function whenOf(appliedAt: string, now: number, locale: string): { readonly relative: string | null; readonly exact: string } {
    return { relative: relativeWhen(appliedAt, now), exact: formatDate(appliedAt, 'dd.MM.y, HH:mm', locale) };
}

/** The kind and the key out of a `KIND:key` unit id. */
export function splitUnitId(unitId: string): { readonly kind: WireKind; readonly key: string } | null {
    const separator = unitId.indexOf(':');
    const kind = unitId.slice(0, separator);
    const key = unitId.slice(separator + 1);
    return (WIRE_KINDS as readonly string[]).includes(kind) && isCanonicalEntityId(key) ? { kind: kind as WireKind, key: key.toLowerCase() } : null;
}

export function isRevertibleKind(kind: WireKind): kind is RevertibleKind {
    return (REVERTIBLE_KINDS as readonly string[]).includes(kind);
}

// ---------------------------------------------------------------------------------------------
// Strict-reading helpers
// ---------------------------------------------------------------------------------------------

function record(value: unknown): Record<string, unknown> {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new OwnDeckProtocolError('Expected an object response.');
    return value as Record<string, unknown>;
}

function exactKeys(object: Record<string, unknown>, required: readonly string[]): void {
    const keys = Object.keys(object);
    if (required.some(key => !keys.includes(key)) || keys.some(key => !required.includes(key))) throw new OwnDeckProtocolError('Response object has an unexpected shape.');
}

function array(value: unknown, name: string): unknown[] {
    if (!Array.isArray(value)) throw new OwnDeckProtocolError(`Invalid ${name}.`);
    return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], name: string): T {
    if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) throw new OwnDeckProtocolError(`Invalid ${name}.`);
    return value as T;
}

function count(value: unknown): number {
    if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) throw new OwnDeckProtocolError('Invalid count.');
    return value;
}

function entityId(value: unknown, name: string): string {
    if (typeof value !== 'string' || !isCanonicalEntityId(value)) throw new OwnDeckProtocolError(`Invalid ${name}.`);
    return value;
}

function sameId(value: unknown, expected: string, message: string): void {
    if (typeof value !== 'string' || value.toLowerCase() !== expected.toLowerCase()) throw new OwnDeckProtocolError(message);
}
