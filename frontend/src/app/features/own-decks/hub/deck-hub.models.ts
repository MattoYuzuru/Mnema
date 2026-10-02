import { MECHANICS, Mechanic } from '../../../content/exercise/exercise-content.models';
import {
    AuthoringProtocolError,
    requireCommand,
    requireCount,
    requireEntity,
    requireInstant,
    requireObject,
    requireVersion
} from '../../authoring/authoring.models';

/** Largest deletion the server accepts in one command (`BULK_SELECTION_TOO_LARGE` above it). */
export const BULK_DELETE_MAX = 500;
/** Largest explicit `itemIds` list of one command; a bigger selection is addressed as `allInDeck` + `except`. */
export const BULK_DELETE_EXPLICIT_MAX = 100;
const DUE_DAYS = 7;
const MAX_COUNT = 100_000;

/** The `study-progress` state of one material; Insights never defines another vocabulary. */
export const MATERIAL_STATES = ['NOT_STARTED', 'LEARNING', 'DUE', 'ON_TRACK'] as const;
export type MaterialState = (typeof MATERIAL_STATES)[number];

export interface DueDay { readonly date: string; readonly materials: number; }

/** One snapshot of `GET /api/decks/{id}/insights`: structural facts only, never learner metrics. */
export interface DeckInsights {
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly asOf: string;
    readonly timezone: string;
    readonly coverage: { readonly total: number; readonly withExercises: number; readonly withoutExercises: number };
    readonly states: Readonly<Record<MaterialState, number>>;
    readonly dueByDay: readonly DueDay[];
    readonly exercisesByMechanic: Readonly<Record<Mechanic, number>>;
    readonly captures: { readonly open: number; readonly oldestOpenCreatedAt: string | null };
}

export interface ExemplarAcknowledgement {
    readonly commandId: string;
    readonly deckId: string;
    readonly memberKey: string;
    readonly itemRevisionId: string;
    readonly exemplar: boolean;
    readonly changed: boolean;
    readonly exemplarCount: number;
}

/** The materials a bulk action addresses: explicit keys, or every material of the deck except the listed ones. */
export type DeletionSelection =
    | { readonly itemIds: readonly string[] }
    | { readonly allInDeck: true; readonly except: readonly string[] };

export interface DeletionPreview {
    readonly deckId: string;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly materialCount: number;
    /** Enabled and disabled exercises that assess a selected material (the list's `exerciseCount` is enabled-only). */
    readonly affectedExerciseCount: number;
}

export interface BulkDeleteResult {
    readonly commandId: string;
    readonly deckId: string;
    readonly status: 'COMPLETED' | 'PARTIAL';
    readonly requested: number;
    readonly deleted: number;
    /** Materials that were not attempted; they still exist unchanged. */
    readonly notDeleted: readonly string[];
    readonly stopReason: 'VERSION_CONFLICT' | null;
    readonly deckRevisionId: string;
    readonly deckVersion: string;
    readonly memberCount: number;
}

export interface BulkDeleteReceipt { readonly result: BulkDeleteResult; readonly replayed: boolean; }

/** Facts a hub request failure carries: the HTTP status and the stable problem `code`, if any. */
export interface HubFailure {
    readonly status: number;
    readonly code: string | null;
}

export function parseInsights(value: unknown): DeckInsights {
    const object = requireObject(value, [
        'deckId', 'deckRevisionId', 'deckVersion', 'asOf', 'timezone', 'coverage', 'states', 'dueByDay',
        'exercisesByMechanic', 'captures'
    ]);
    const coverageObject = requireObject(object['coverage'], ['total', 'withExercises', 'withoutExercises']);
    const coverage = {
        total: requireCount(coverageObject['total'], MAX_COUNT),
        withExercises: requireCount(coverageObject['withExercises'], MAX_COUNT),
        withoutExercises: requireCount(coverageObject['withoutExercises'], MAX_COUNT)
    };
    const stateObject = requireObject(object['states'], MATERIAL_STATES);
    const states = Object.fromEntries(MATERIAL_STATES.map(state => [state, requireCount(stateObject[state], MAX_COUNT)])) as
        Record<MaterialState, number>;
    const stateSum = MATERIAL_STATES.reduce((sum, state) => sum + states[state], 0);
    if (coverage.total !== coverage.withExercises + coverage.withoutExercises || coverage.total !== stateSum) {
        throw new AuthoringProtocolError('Inconsistent insights totals.');
    }
    const days = object['dueByDay'];
    if (!Array.isArray(days) || days.length !== DUE_DAYS) throw new AuthoringProtocolError('Invalid due days.');
    const dueByDay = days.map(parseDueDay);
    dueByDay.forEach((day, index) => {
        if (index > 0 && day.date !== addDay(dueByDay[index - 1].date)) throw new AuthoringProtocolError('Due days are not consecutive.');
    });
    if (dueByDay.reduce((sum, day) => sum + day.materials, 0) > coverage.total) {
        throw new AuthoringProtocolError('Due materials exceed the deck.');
    }
    const mechanicObject = requireObject(object['exercisesByMechanic'], MECHANICS);
    const exercisesByMechanic = Object.fromEntries(MECHANICS.map(mechanic => [mechanic, requireCount(mechanicObject[mechanic], MAX_COUNT)])) as
        Record<Mechanic, number>;
    const captureObject = requireObject(object['captures'], ['open', 'oldestOpenCreatedAt']);
    const open = requireCount(captureObject['open'], MAX_COUNT);
    const oldest = captureObject['oldestOpenCreatedAt'] === null ? null : requireInstant(captureObject['oldestOpenCreatedAt']);
    if ((open === 0) !== (oldest === null)) throw new AuthoringProtocolError('Inconsistent capture summary.');
    const timezone = object['timezone'];
    if (typeof timezone !== 'string' || timezone.length === 0 || timezone.length > 64) throw new AuthoringProtocolError('Invalid timezone.');
    return {
        deckId: requireEntity(object['deckId']), deckRevisionId: requireEntity(object['deckRevisionId']),
        deckVersion: requireVersion(object['deckVersion']), asOf: requireInstant(object['asOf']), timezone,
        coverage, states, dueByDay, exercisesByMechanic, captures: { open, oldestOpenCreatedAt: oldest }
    };
}

export function parseExemplarAcknowledgement(value: unknown): ExemplarAcknowledgement {
    const object = requireObject(value, ['commandId', 'deckId', 'memberKey', 'itemRevisionId', 'exemplar', 'changed', 'exemplarCount']);
    if (typeof object['exemplar'] !== 'boolean' || typeof object['changed'] !== 'boolean') {
        throw new AuthoringProtocolError('Invalid exemplar acknowledgement.');
    }
    return {
        commandId: requireCommand(object['commandId']), deckId: requireEntity(object['deckId']),
        memberKey: requireEntity(object['memberKey']), itemRevisionId: requireEntity(object['itemRevisionId']),
        exemplar: object['exemplar'], changed: object['changed'], exemplarCount: requireCount(object['exemplarCount'], 1_000)
    };
}

export function parseDeletionPreview(value: unknown): DeletionPreview {
    const object = requireObject(value, ['deckId', 'deckRevisionId', 'deckVersion', 'materialCount', 'affectedExerciseCount']);
    return {
        deckId: requireEntity(object['deckId']), deckRevisionId: requireEntity(object['deckRevisionId']),
        deckVersion: requireVersion(object['deckVersion']), materialCount: requireCount(object['materialCount'], BULK_DELETE_MAX),
        affectedExerciseCount: requireCount(object['affectedExerciseCount'], MAX_COUNT * 10)
    };
}

export function parseBulkDeleteResult(value: unknown): BulkDeleteResult {
    const object = requireObject(value, [
        'commandId', 'deckId', 'status', 'requested', 'deleted', 'notDeleted', 'stopReason', 'deckRevisionId', 'deckVersion',
        'memberCount'
    ]);
    const status = object['status'];
    if (status !== 'COMPLETED' && status !== 'PARTIAL') throw new AuthoringProtocolError('Invalid deletion status.');
    const notDeletedValue = object['notDeleted'];
    if (!Array.isArray(notDeletedValue) || notDeletedValue.length > BULK_DELETE_MAX) throw new AuthoringProtocolError('Invalid deletion remainder.');
    const notDeleted = notDeletedValue.map(requireEntity);
    if (new Set(notDeleted).size !== notDeleted.length) throw new AuthoringProtocolError('Duplicate deletion remainder.');
    const requested = requireCount(object['requested'], BULK_DELETE_MAX);
    const deleted = requireCount(object['deleted'], BULK_DELETE_MAX);
    const stopReason = object['stopReason'];
    const consistent = requested === deleted + notDeleted.length && (status === 'COMPLETED'
        ? notDeleted.length === 0 && stopReason === null
        : deleted > 0 && notDeleted.length > 0 && stopReason === 'VERSION_CONFLICT');
    if (!consistent) throw new AuthoringProtocolError('Inconsistent deletion result.');
    return {
        commandId: requireCommand(object['commandId']), deckId: requireEntity(object['deckId']), status, requested, deleted,
        notDeleted, stopReason: stopReason as BulkDeleteResult['stopReason'], deckRevisionId: requireEntity(object['deckRevisionId']),
        deckVersion: requireVersion(object['deckVersion']), memberCount: requireCount(object['memberCount'], MAX_COUNT)
    };
}

/** Validates a selection before it is sent; the server enforces the same bounds. */
export function requireSelection(selection: DeletionSelection): Record<string, unknown> {
    if ('allInDeck' in selection) {
        const except = distinctEntities(selection.except, BULK_DELETE_MAX);
        return { allInDeck: true, except };
    }
    const itemIds = distinctEntities(selection.itemIds, BULK_DELETE_EXPLICIT_MAX);
    if (itemIds.length === 0) throw new AuthoringProtocolError('Empty selection.');
    return { itemIds };
}

/** Stable key of a selection, to tell whether a preview still describes what is selected. */
export function selectionKey(selection: DeletionSelection): string {
    return 'allInDeck' in selection ? `all:${[...selection.except].sort().join(',')}` : `ids:${[...selection.itemIds].sort().join(',')}`;
}

/** The HTTP status and RFC 9457 `code` of a failed request, or null when it was not an HTTP failure. */
export function hubFailureOf(error: unknown): HubFailure | null {
    if (error === null || typeof error !== 'object' || !('status' in error) || typeof error.status !== 'number') return null;
    const body = 'error' in error ? error.error : null;
    const code = body !== null && typeof body === 'object' && 'code' in body && typeof body.code === 'string' ? body.code : null;
    return { status: error.status, code };
}

function distinctEntities(values: readonly string[], maximum: number): string[] {
    if (!Array.isArray(values) || values.length > maximum) throw new AuthoringProtocolError('Selection is too large.');
    const normalized = values.map(requireEntity);
    if (new Set(normalized).size !== normalized.length) throw new AuthoringProtocolError('Duplicate selection.');
    return normalized;
}

function parseDueDay(value: unknown): DueDay {
    const object = requireObject(value, ['date', 'materials']);
    const date = object['date'];
    if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(`${date}T00:00:00Z`))) {
        throw new AuthoringProtocolError('Invalid due date.');
    }
    return { date, materials: requireCount(object['materials'], MAX_COUNT) };
}

function addDay(date: string): string {
    const next = new Date(Date.parse(`${date}T00:00:00Z`) + 86_400_000);
    return next.toISOString().slice(0, 10);
}
