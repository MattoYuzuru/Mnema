import { HttpErrorResponse } from '@angular/common/http';

import type { NativeDocument } from '../../../content/native-document';
import { readRetainedNativeDocument } from '../../../content/native-document-boundary';
import { UPDATE_TEXT, UpdateChoice, UpdateUnitClass, UpdateUnitKind } from '../../../shared/copy-text';
import type { UpdateUnitView } from '../../../shared/update-unit-row.component';
import { OwnDeckProtocolError, isCanonicalCommandId, isCanonicalEntityId } from '../own-deck.models';

/**
 * Wire shapes of the update review screen: the plan of a copy, the two sides of one unit and the apply command
 * (`contracts/decks/updates.json`, Updates/3 and Updates/4). Parsing is strict: a body that is not exactly the contract's is a protocol
 * error. The contract words are mirrored here once; the texts are `UPDATE_TEXT`.
 */

/** The limit the screen asks for: the contract's `defaultLimit`. */
export const PLAN_PAGE_LIMIT = 50;

export const PLAN_STATES = ['READY', 'UP_TO_DATE', 'SOURCE_UNAVAILABLE', 'DETACHED', 'PREPARING', 'APPLYING'] as const;
export type PlanState = (typeof PLAN_STATES)[number];

export const WIRE_KINDS = ['METADATA', 'MATERIAL', 'EXERCISE', 'OBJECTIVE', 'ORDER'] as const;
export type WireKind = (typeof WIRE_KINDS)[number];

/** In the order of the plan: the server cuts every page from one ordering by class, so a class is one contiguous run. */
export const WIRE_CLASSES = ['NEW', 'SAFE', 'CONFLICT', 'DELETED', 'ASK_AGAIN'] as const;
export type WireClass = (typeof WIRE_CLASSES)[number];

export const WIRE_CHOICES = ['ACCEPT', 'KEEP_MINE', 'DECLINE', 'DELETE', 'KEEP'] as const;
export type WireChoice = (typeof WIRE_CHOICES)[number];

const REASONS: Readonly<Record<WireClass, readonly (string | null)[]>> = {
    NEW: [null], SAFE: [null], DELETED: [null],
    CONFLICT: ['BOTH_CHANGED', 'DELETED_BY_YOU', 'MATERIAL_CHANGED', 'MATERIAL_DELETED', 'OBJECTIVE_RENAMED'],
    ASK_AGAIN: ['CHANGED_AFTER_DECISION']
};

/** `defaultChoices` of the contract. */
export const WIRE_DEFAULT_CHOICES: Readonly<Record<WireClass, WireChoice>> = {
    NEW: 'ACCEPT', SAFE: 'ACCEPT', CONFLICT: 'KEEP_MINE', ASK_AGAIN: 'KEEP_MINE', DELETED: 'KEEP'
};

const PLAN_VERSION = /^sha256:[0-9a-f]{64}$/u;
const UNIT_ID = /^(METADATA|MATERIAL|EXERCISE|OBJECTIVE|ORDER):[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const TITLE_MAX = 160;
const NOTE_MAX = 500;

export interface PlanSummary {
    readonly newMaterials: number;
    readonly newExercises: number;
    readonly fixes: number;
    readonly conflicts: number;
    readonly deletedByAuthor: number;
    readonly askAgain: number;
}

/** One unit of the plan page, as the wire states it. */
export interface PlanUnit {
    readonly unitId: string;
    readonly kind: WireKind;
    readonly key: string;
    readonly class: WireClass;
    readonly reason: string | null;
    /** `null` for the order unit, empty for a taken-down revision of the copy. */
    readonly title: string | null;
    readonly dependsOn: readonly string[];
    readonly defaultChoice: WireChoice;
}

/** One page of `GET /api/decks/{id}/update-plan`. The states that are not `READY`/`UP_TO_DATE` carry no pins, no summary and no units. */
export interface UpdatePlanPage {
    readonly state: PlanState;
    readonly forkDeckId: string;
    readonly planVersion: string | null;
    readonly releaseNote: string | null;
    readonly summary: PlanSummary | null;
    readonly units: readonly PlanUnit[];
    readonly nextCursor: string | null;
    /** The code of the last background apply that failed (`state: READY` again until the next apply); `null` otherwise. */
    readonly lastApplyFailed: string | null;
    /** The command a running apply job belongs to (`state: APPLYING` only); `null` otherwise. */
    readonly commandId: string | null;
}

// ---------------------------------------------------------------------------------------------
// The plan
// ---------------------------------------------------------------------------------------------

const FULL_KEYS = ['state', 'forkDeckId', 'forkHeadRevisionId', 'upstreamRevisionId', 'baseRevisionId', 'planVersion', 'releaseNote', 'summary', 'units', 'nextCursor'] as const;
const CLOSED_KEYS = ['state', 'forkDeckId', 'units', 'nextCursor'] as const;
const UNIT_KEYS = ['unitId', 'kind', 'key', 'class', 'reason', 'title', 'authorRevisionId', 'mineRevisionId', 'baseRevisionId', 'dependsOn', 'defaultChoice'] as const;
const SUMMARY_KEYS = ['newMaterials', 'newExercises', 'fixes', 'conflicts', 'deletedByAuthor', 'askAgain'] as const;

export function parseUpdatePlan(value: unknown, deckId: string): UpdatePlanPage {
    const object = record(value);
    const state = oneOf(object['state'], PLAN_STATES, 'plan state');
    sameId(object['forkDeckId'], deckId);
    const forkDeckId = deckId.toLowerCase();
    if (state === 'READY' || state === 'UP_TO_DATE') {
        const lastApplyFailed = lastFailure(object);
        exactKeys(object, FULL_KEYS, ['lastApplyFailed']);
        for (const key of ['forkHeadRevisionId', 'upstreamRevisionId', 'baseRevisionId'] as const) entityId(object[key], key);
        const planVersion = object['planVersion'];
        if (typeof planVersion !== 'string' || !PLAN_VERSION.test(planVersion)) throw new OwnDeckProtocolError('Invalid plan version.');
        const releaseNote = optionalText(object['releaseNote'], NOTE_MAX, 'release note');
        const summary = parseSummary(object['summary']);
        const units = array(object['units'], 'units').map(parseUnit);
        const nextCursor = cursor(object['nextCursor']);
        if (state === 'UP_TO_DATE' && (units.length > 0 || nextCursor !== null)) throw new OwnDeckProtocolError('An up-to-date plan lists no units.');
        if (new Set(units.map(unit => unit.unitId)).size !== units.length) throw new OwnDeckProtocolError('A unit is listed twice.');
        return { state, forkDeckId, planVersion, releaseNote, summary, units, nextCursor, lastApplyFailed, commandId: null };
    }
    // PREPARING, DETACHED, SOURCE_UNAVAILABLE: exactly the closed body; APPLYING: the closed body plus the command the job runs.
    let commandId: string | null = null;
    if (state === 'APPLYING') {
        exactKeys(object, [...CLOSED_KEYS, 'commandId'], []);
        const running = object['commandId'];
        if (typeof running !== 'string' || !isCanonicalCommandId(running)) throw new OwnDeckProtocolError('Invalid applying command ID.');
        commandId = running.toLowerCase();
    } else {
        exactKeys(object, CLOSED_KEYS, []);
    }
    if (array(object['units'], 'units').length > 0 || cursor(object['nextCursor']) !== null) throw new OwnDeckProtocolError('A closed plan lists no units.');
    return { state, forkDeckId, planVersion: null, releaseNote: null, summary: null, units: [], nextCursor: null, lastApplyFailed: null, commandId };
}

function lastFailure(object: Record<string, unknown>): string | null {
    if (!('lastApplyFailed' in object) || object['lastApplyFailed'] === null) return null;
    const failure = record(object['lastApplyFailed']);
    const code = failure['code'];
    if (typeof code !== 'string' || code.length === 0 || code.length > 100) throw new OwnDeckProtocolError('Invalid apply failure.');
    return code;
}

function parseSummary(value: unknown): PlanSummary {
    const object = record(value);
    exactKeys(object, SUMMARY_KEYS, []);
    return {
        newMaterials: count(object['newMaterials']), newExercises: count(object['newExercises']), fixes: count(object['fixes']),
        conflicts: count(object['conflicts']), deletedByAuthor: count(object['deletedByAuthor']), askAgain: count(object['askAgain'])
    };
}

function parseUnit(value: unknown): PlanUnit {
    const object = record(value);
    exactKeys(object, UNIT_KEYS, []);
    const kind = oneOf(object['kind'], WIRE_KINDS, 'unit kind');
    const unitClass = oneOf(object['class'], WIRE_CLASSES, 'unit class');
    const key = object['key'];
    if (typeof key !== 'string' || !isCanonicalEntityId(key)) throw new OwnDeckProtocolError('Invalid unit key.');
    const unitId = object['unitId'];
    if (unitId !== `${kind}:${key}` || typeof unitId !== 'string' || !UNIT_ID.test(unitId)) throw new OwnDeckProtocolError('Invalid unit identity.');
    const reason = object['reason'];
    if (reason !== null && typeof reason !== 'string') throw new OwnDeckProtocolError('Invalid unit reason.');
    if (!REASONS[unitClass].includes(reason)) throw new OwnDeckProtocolError('Unit reason disagrees with its class.');
    const title = object['title'] === null ? null : optionalText(object['title'], TITLE_MAX, 'unit title', true);
    for (const revision of ['authorRevisionId', 'mineRevisionId', 'baseRevisionId'] as const) if (object[revision] !== null) entityId(object[revision], revision);
    const dependsOn = array(object['dependsOn'], 'dependsOn').map(item => {
        if (typeof item !== 'string' || !UNIT_ID.test(item)) throw new OwnDeckProtocolError('Invalid dependency.');
        return item;
    });
    const defaultChoice = oneOf(object['defaultChoice'], WIRE_CHOICES, 'default choice');
    if (defaultChoice !== WIRE_DEFAULT_CHOICES[unitClass]) throw new OwnDeckProtocolError('Default choice disagrees with the class.');
    return { unitId, kind, key: key.toLowerCase(), class: unitClass, reason, title, dependsOn, defaultChoice };
}

// ---------------------------------------------------------------------------------------------
// The two sides of one unit
// ---------------------------------------------------------------------------------------------

export interface MaterialSide {
    readonly kind: 'MATERIAL';
    readonly memberKey: string;
    /** `null` when the revision was taken down after a complaint. */
    readonly document: NativeDocument | null;
    readonly unavailable: boolean;
}

export interface ExerciseSide {
    readonly kind: 'EXERCISE';
    readonly exerciseId: string;
    readonly unavailable: boolean;
    readonly type: string;
    readonly enabled: boolean;
    readonly objectiveTitle: string | null;
    /** The exercise revision's content and answer key, as the server stored them; the projection reads them tolerantly. */
    readonly content: unknown;
    readonly answerKey: unknown;
}

export interface ObjectiveSide { readonly kind: 'OBJECTIVE'; readonly title: string; }
export interface MetadataSide { readonly kind: 'METADATA'; readonly title: string; readonly description: string; }

export interface OrderMove {
    readonly memberKey: string;
    readonly title: string | null;
    readonly from: number | null;
    readonly to: number | null;
}

export interface OrderSide { readonly kind: 'ORDER'; readonly moved: readonly OrderMove[]; readonly total: number; readonly truncated: boolean; }

export type UnitSide = MaterialSide | ExerciseSide | ObjectiveSide | MetadataSide | OrderSide;

/** `GET .../update-plan/units/{kind}/{key}?planVersion=`: a side is `null` when the unit does not exist there. */
export interface UnitSides {
    readonly unitId: string;
    readonly kind: WireKind;
    readonly class: WireClass;
    readonly planVersion: string;
    readonly mine: UnitSide | null;
    readonly author: UnitSide | null;
    /** The stable node ids that differ (materials only). The compared document finds the same blocks by node id itself. */
    readonly changedNodeIds: readonly string[];
}

const SIDE_KEYS = ['unitId', 'kind', 'key', 'class', 'reason', 'planVersion', 'mine', 'author', 'changedNodeIds'] as const;

export function parseUnitSides(value: unknown, requested: { readonly kind: WireKind; readonly key: string; readonly planVersion: string }): UnitSides {
    const object = record(value);
    exactKeys(object, SIDE_KEYS, []);
    const kind = oneOf(object['kind'], WIRE_KINDS, 'unit kind');
    const unitClass = oneOf(object['class'], WIRE_CLASSES, 'unit class');
    const key = object['key'];
    if (kind !== requested.kind || typeof key !== 'string' || key.toLowerCase() !== requested.key.toLowerCase()
        || object['unitId'] !== `${kind}:${key}`) throw new OwnDeckProtocolError('Unit answer does not match the request.');
    if (object['planVersion'] !== requested.planVersion) throw new OwnDeckProtocolError('Unit answer belongs to another plan.');
    const reason = object['reason'];
    if (reason !== null && typeof reason !== 'string') throw new OwnDeckProtocolError('Invalid unit reason.');
    const changedNodeIds = array(object['changedNodeIds'], 'changedNodeIds').map(id => {
        if (typeof id !== 'string' || id.length === 0) throw new OwnDeckProtocolError('Invalid node id.');
        return id;
    });
    if (kind !== 'MATERIAL' && changedNodeIds.length > 0) throw new OwnDeckProtocolError('Only a material has changed nodes.');
    return {
        unitId: `${kind}:${key.toLowerCase()}`, kind, class: unitClass, planVersion: requested.planVersion,
        mine: parseSide(kind, object['mine']), author: parseSide(kind, object['author']), changedNodeIds
    };
}

function parseSide(kind: WireKind, value: unknown): UnitSide | null {
    if (value === null) return null;
    const object = record(value);
    switch (kind) {
        case 'MATERIAL': {
            const unavailable = object['unavailable'] === true;
            const memberKey = object['memberKey'];
            if (typeof memberKey !== 'string' || !isCanonicalEntityId(memberKey)) throw new OwnDeckProtocolError('Invalid material side.');
            if (unavailable) return { kind, memberKey: memberKey.toLowerCase(), document: null, unavailable: true };
            exactKeys(object, ['memberKey', 'itemRevisionId', 'document'], []);
            entityId(object['itemRevisionId'], 'itemRevisionId');
            return { kind, memberKey: memberKey.toLowerCase(), document: readDocument(object['document']), unavailable: false };
        }
        case 'EXERCISE': {
            const exerciseId = object['exerciseId'];
            if (typeof exerciseId !== 'string' || !isCanonicalEntityId(exerciseId)) throw new OwnDeckProtocolError('Invalid exercise side.');
            if (object['unavailable'] === true) {
                return { kind, exerciseId: exerciseId.toLowerCase(), unavailable: true, type: '', enabled: false, objectiveTitle: null, content: null, answerKey: null };
            }
            const type = object['type'];
            if (typeof type !== 'string' || type.length === 0 || type.length > 40 || typeof object['enabled'] !== 'boolean') throw new OwnDeckProtocolError('Invalid exercise side.');
            const objective = object['objective'] === null || object['objective'] === undefined ? null : record(object['objective']);
            const objectiveTitle = objective === null ? null : optionalText(objective['title'], TITLE_MAX, 'objective title', true);
            return { kind, exerciseId: exerciseId.toLowerCase(), unavailable: false, type, enabled: object['enabled'], objectiveTitle, content: object['content'], answerKey: object['answerKey'] };
        }
        case 'OBJECTIVE': {
            exactKeys(object, ['objectiveId', 'objectiveRevisionId', 'title'], []);
            return { kind, title: optionalText(object['title'], TITLE_MAX, 'objective title', true) ?? '' };
        }
        case 'METADATA': {
            exactKeys(object, ['title', 'description'], []);
            if (typeof object['title'] !== 'string' || typeof object['description'] !== 'string') throw new OwnDeckProtocolError('Invalid metadata side.');
            return { kind, title: object['title'], description: object['description'] };
        }
        case 'ORDER': {
            exactKeys(object, ['moved', 'total', 'truncated'], []);
            if (typeof object['truncated'] !== 'boolean') throw new OwnDeckProtocolError('Invalid order side.');
            const moved = array(object['moved'], 'moved').map((entry): OrderMove => {
                const move = record(entry);
                exactKeys(move, ['memberKey', 'title', 'from', 'to'], []);
                if (typeof move['memberKey'] !== 'string' || !isCanonicalEntityId(move['memberKey'])) throw new OwnDeckProtocolError('Invalid order entry.');
                return { memberKey: move['memberKey'].toLowerCase(), title: optionalText(move['title'], TITLE_MAX, 'order title', true), from: place(move['from']), to: place(move['to']) };
            });
            return { kind, moved, total: count(object['total']), truncated: object['truncated'] };
        }
    }
}

function readDocument(value: unknown): NativeDocument {
    try {
        return readRetainedNativeDocument(value);
    } catch {
        throw new OwnDeckProtocolError('Invalid native document.');
    }
}

function place(value: unknown): number | null {
    if (value === null) return null;
    return count(value);
}

// ---------------------------------------------------------------------------------------------
// The reader's view of a unit and the mapping between the screen's words and the wire's
// ---------------------------------------------------------------------------------------------

const KIND_VIEW: Readonly<Record<WireKind, UpdateUnitKind>> = { MATERIAL: 'material', EXERCISE: 'exercise', OBJECTIVE: 'goal', METADATA: 'description', ORDER: 'order' };
const CLASS_VIEW: Readonly<Record<WireClass, UpdateUnitClass>> = { NEW: 'new', SAFE: 'fix', CONFLICT: 'conflict', DELETED: 'deleted', ASK_AGAIN: 'changed-again' };

/** A plan unit with the words of the screen: its kind and class in the vocabulary of the shared row. */
export interface ReviewUnit {
    readonly id: string;
    readonly wireKind: WireKind;
    readonly key: string;
    readonly kind: UpdateUnitKind;
    readonly class: UpdateUnitClass;
    readonly wireClass: WireClass;
    readonly title: string;
    readonly dependsOn: readonly string[];
}

/** The word of the shared rows for a wire kind. */
export function viewKindOf(kind: WireKind): UpdateUnitKind {
    return KIND_VIEW[kind];
}

export function toReviewUnit(unit: PlanUnit): ReviewUnit {
    const kind = KIND_VIEW[unit.kind];
    const title = unit.title !== null && unit.title.trim().length > 0 ? unit.title : UPDATE_TEXT.untitled[kind];
    return { id: unit.unitId, wireKind: unit.kind, key: unit.key, kind, class: CLASS_VIEW[unit.class], wireClass: unit.class, title, dependsOn: unit.dependsOn };
}

export function unitView(unit: ReviewUnit, dependency: string | null): UpdateUnitView {
    return { id: unit.id, kind: unit.kind, title: unit.title, class: unit.class, dependency };
}

/** The tabs of the screen. «Конфликты» holds the conflicts and the units changed again after a decision. */
export type UpdateTab = 'new' | 'fix' | 'conflict' | 'deleted';
export const UPDATE_TABS: readonly UpdateTab[] = ['new', 'fix', 'conflict', 'deleted'];

const TAB_OF_CLASS: Readonly<Record<WireClass, UpdateTab>> = { NEW: 'new', SAFE: 'fix', CONFLICT: 'conflict', ASK_AGAIN: 'conflict', DELETED: 'deleted' };

export function tabOf(unit: ReviewUnit): UpdateTab {
    return TAB_OF_CLASS[unit.wireClass];
}

/** The place of a class in the order of the plan: a page never returns a unit of an earlier class after one of a later class. */
export function classRank(wireClass: WireClass): number {
    return WIRE_CLASSES.indexOf(wireClass);
}

/** The last class (in plan order) whose units belong to the tab. */
export function lastRankOf(tab: UpdateTab): number {
    return Math.max(...WIRE_CLASSES.filter(wireClass => TAB_OF_CLASS[wireClass] === tab).map(classRank));
}

/** How many units each tab holds in the whole plan, from its summary. */
export function tabCounts(summary: PlanSummary): Readonly<Record<UpdateTab, number>> {
    return { new: summary.newMaterials + summary.newExercises, fix: summary.fixes, conflict: summary.conflicts + summary.askAgain, deleted: summary.deletedByAuthor };
}

/** The counts of the summary block. */
export function summaryCounts(summary: PlanSummary): { materials: number; exercises: number; fixes: number; conflicts: number; deleted: number } {
    return { materials: summary.newMaterials, exercises: summary.newExercises, fixes: summary.fixes, conflicts: summary.conflicts + summary.askAgain, deleted: summary.deletedByAuthor };
}

/**
 * What a choice of the row means on the wire (`choicesPerClass` of the contract); `null` when the choice does not exist for the class.
 * A conflict and a unit changed again after a decision offer the same two answers, `KEEP_MINE` and `ACCEPT`.
 */
export function choiceToWire(unitClass: UpdateUnitClass, choice: UpdateChoice): WireChoice | null {
    switch (unitClass) {
        case 'conflict':
        case 'changed-again': return choice === 'keep-mine' ? 'KEEP_MINE' : choice === 'take-author' ? 'ACCEPT' : null;
        case 'deleted': return choice === 'keep' ? 'KEEP' : choice === 'delete' ? 'DELETE' : null;
        default: return choice === 'accept' ? 'ACCEPT' : choice === 'skip' ? 'DECLINE' : null;
    }
}

/**
 * The answer a class stands on before the reader picks (`defaultChoices` of the contract). A new or corrected unit has none on screen:
 * «Принять всё безопасное» takes it. A conflict, a unit changed again after a decision and a deletion stand on keeping what the reader
 * has; the server leaves a unit it is not told about pending, so the default is shown but not sent.
 */
export function isDefaultChoice(unitClass: UpdateUnitClass, choice: UpdateChoice): boolean {
    switch (unitClass) {
        case 'new':
        case 'fix': return choice === 'accept';
        case 'conflict':
        case 'changed-again': return choice === 'keep-mine';
        case 'deleted': return choice === 'keep';
    }
}

/** The reader takes the author's version of the unit. */
export function takesAuthor(choice: UpdateChoice | null | undefined): boolean {
    return choice === 'accept' || choice === 'take-author';
}

// ---------------------------------------------------------------------------------------------
// Apply
// ---------------------------------------------------------------------------------------------

export interface ApplyChoice { readonly unitId: string; readonly choice: WireChoice; }

/** `POST /api/decks/{id}/update-plan:apply` (Updates/4). */
export interface ApplyCommand {
    readonly commandId: string;
    readonly planVersion: string;
    readonly acceptSafe: boolean;
    readonly choices: readonly ApplyChoice[];
}

export type ApplyAnswer =
    | { readonly status: 'applied'; readonly applied: number; readonly declined: number; readonly pending: number; readonly alsoAccepted: number; readonly deckRevisionId: string | null }
    | { readonly status: 'applying' };

export function newUpdateCommandId(): string {
    const commandId = crypto.randomUUID();
    if (!isCanonicalCommandId(commandId)) throw new OwnDeckProtocolError('Browser did not produce a canonical UUIDv4 command ID.');
    return commandId;
}

/** 200 is `APPLIED` (also a replay of the same command), 202 is `APPLYING` (the job runs); anything else is a protocol error. */
export function parseApplyAnswer(value: unknown, status: number, commandId: string): ApplyAnswer {
    const object = record(value);
    if (typeof object['commandId'] !== 'string' || object['commandId'].toLowerCase() !== commandId.toLowerCase()) {
        throw new OwnDeckProtocolError('Apply acknowledgement command ID does not match the request.');
    }
    if (status === 202) {
        exactKeys(object, ['commandId', 'state'], []);
        if (object['state'] !== 'APPLYING') throw new OwnDeckProtocolError('Unexpected apply answer.');
        return { status: 'applying' };
    }
    if (status !== 200 || object['state'] !== 'APPLIED') throw new OwnDeckProtocolError('Unexpected apply answer.');
    exactKeys(object, ['commandId', 'state', 'deckRevisionId', 'applied', 'declined', 'pending', 'alsoAccepted', 'baseRevisionId'], []);
    const deckRevisionId = object['deckRevisionId'];
    if (deckRevisionId !== null) entityId(deckRevisionId, 'deckRevisionId');
    entityId(object['baseRevisionId'], 'baseRevisionId');
    return {
        status: 'applied', applied: count(object['applied']), declined: count(object['declined']), pending: count(object['pending']),
        alsoAccepted: count(object['alsoAccepted']), deckRevisionId: deckRevisionId as string | null
    };
}

/** What a failed plan, unit or apply request means for the screen. */
export type UpdateFailureKind =
    | 'not-found'
    /** 409 `UPDATE_PLAN_STALE`: the copy or the source changed after the plan was read. */
    | 'stale'
    | 'preparing'
    /** 409 `FORK_DETACHED`. */
    | 'detached'
    /** 409 `UPDATE_SOURCE_UNAVAILABLE`. */
    | 'source-unavailable'
    /** 400: the choice or the request is not valid (see {@link invalidChoiceOf} for what exactly). */
    | 'invalid'
    /** 422 `RESOURCE_LIMIT_EXCEEDED`: too many units in one command. */
    | 'limit'
    | 'rate-limited'
    /** 503 `UPDATE_PLAN_BUSY`: too many plans are computed right now; nothing was computed, asking again later is safe. */
    | 'busy'
    /** No answer, a 5xx or an answer that does not match the contract: the outcome is unknown, the same command may be sent again. */
    | 'unknown'
    | 'other';

function problemBody(error: unknown): Record<string, unknown> {
    return error instanceof HttpErrorResponse && error.error !== null && typeof error.error === 'object' && !Array.isArray(error.error)
        ? error.error as Record<string, unknown> : {};
}

export function updateFailureOf(error: unknown): UpdateFailureKind {
    if (!(error instanceof HttpErrorResponse)) return 'unknown';
    const code = typeof problemBody(error)['code'] === 'string' ? problemBody(error)['code'] : null;
    switch (error.status) {
        case 0: return 'unknown';
        case 400: return 'invalid';
        case 404: return 'not-found';
        case 409:
            return code === 'UPDATE_PLAN_STALE' ? 'stale' : code === 'DECK_PREPARING' ? 'preparing' : code === 'FORK_DETACHED' ? 'detached'
                : code === 'UPDATE_SOURCE_UNAVAILABLE' ? 'source-unavailable' : 'other';
        case 422: return code === 'RESOURCE_LIMIT_EXCEEDED' ? 'limit' : 'other';
        case 429: return 'rate-limited';
        case 503: return code === 'UPDATE_PLAN_BUSY' ? 'busy' : 'unknown';
        default: return error.status >= 500 ? 'unknown' : 'other';
    }
}

/** The pause the server asks for after `UPDATE_PLAN_BUSY` (`Retry-After`, else the `retryAfter` member), in seconds; `null` if it names none. */
export function retryAfterSeconds(error: unknown): number | null {
    if (!(error instanceof HttpErrorResponse)) return null;
    const header = error.headers?.get('Retry-After') ?? null;
    const seconds = header !== null && /^\d{1,6}$/u.test(header) ? Number(header) : problemBody(error)['retryAfter'];
    return typeof seconds === 'number' && Number.isInteger(seconds) && seconds >= 1 && seconds <= 86_400 ? seconds : null;
}

export const INVALID_REASONS = [
    'unknown_unit', 'choice_not_allowed', 'duplicate_unit', 'exercise_needs_material', 'dependency_declined', 'material_still_used', 'publication_refused'
] as const;
export type InvalidReason = (typeof INVALID_REASONS)[number];

/** The 400 of the apply command that names a choice it cannot honour: the reason, the unit and, for a declined dependency or a material still used, the dependency. */
export interface InvalidChoice {
    readonly reason: InvalidReason;
    readonly unitId: string | null;
    readonly dependency: string | null;
}

/** `null` when the 400 carries no reason of the contract (a malformed request, a body the client never sends). */
export function invalidChoiceOf(error: unknown): InvalidChoice | null {
    if (!(error instanceof HttpErrorResponse) || error.status !== 400) return null;
    const body = problemBody(error);
    const reason = INVALID_REASONS.find(candidate => candidate === body['reason']);
    if (reason === undefined) return null;
    const unit = (value: unknown): string | null => typeof value === 'string' && UNIT_ID.test(value) ? value : null;
    return { reason, unitId: unit(body['unitId']), dependency: reason === 'dependency_declined' || reason === 'material_still_used' ? unit(body['dependency']) : null };
}

// ---------------------------------------------------------------------------------------------
// Strict-reading helpers
// ---------------------------------------------------------------------------------------------

function record(value: unknown): Record<string, unknown> {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new OwnDeckProtocolError('Expected an object response.');
    return value as Record<string, unknown>;
}

function exactKeys(object: Record<string, unknown>, required: readonly string[], optional: readonly string[]): void {
    const keys = Object.keys(object);
    if (required.some(key => !keys.includes(key)) || keys.some(key => !required.includes(key) && !optional.includes(key))) {
        throw new OwnDeckProtocolError('Response object has an unexpected shape.');
    }
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

function sameId(value: unknown, expected: string): void {
    if (typeof value !== 'string' || value.toLowerCase() !== expected.toLowerCase()) throw new OwnDeckProtocolError('Plan identity does not match the request.');
}

function cursor(value: unknown): string | null {
    if (value === null) return null;
    if (typeof value !== 'string' || value.length === 0 || value.length > 4096) throw new OwnDeckProtocolError('Invalid cursor.');
    return value;
}

function optionalText(value: unknown, max: number, name: string, allowEmpty = false): string | null {
    if (value === null) return null;
    if (typeof value !== 'string' || (!allowEmpty && value.length === 0) || Array.from(value).length > max) throw new OwnDeckProtocolError(`Invalid ${name}.`);
    return value;
}
