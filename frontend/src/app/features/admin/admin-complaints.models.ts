import {
    AdminProtocolError, array, enumeration, flag, instant, integer, nullable, object, str, uuid
} from './admin.models';

/**
 * Owner-console projections of the complaint queue (`contracts/moderation/complaints.json`, Share/15, #437). The queue and the detail carry
 * opaque account ids only, never emails or names; the console resolves them through the Identity directory. Parsing is strict on types and
 * closed lists; the executable fixtures compare the property sets.
 */
export const COMPLAINT_TARGETS = ['DECK', 'PROFILE'] as const;
export type ComplaintTarget = (typeof COMPLAINT_TARGETS)[number];
export const COMPLAINT_STATUSES = ['OPEN', 'DECIDED'] as const;
export type ComplaintStatus = (typeof COMPLAINT_STATUSES)[number];
export const COMPLAINT_DECISIONS = ['REJECTED', 'HIDDEN', 'TAKEN_DOWN', 'ACCOUNT_REFERRED'] as const;
export type ComplaintDecision = (typeof COMPLAINT_DECISIONS)[number];
export const JOURNAL_ACTIONS = [...COMPLAINT_DECISIONS, 'UNHIDDEN'] as const;
export type JournalAction = (typeof JOURNAL_ACTIONS)[number];
export const UNIT_KINDS = ['ITEM', 'EXERCISE'] as const;
export type UnitKind = (typeof UNIT_KINDS)[number];
export const REASONS = ['COPYRIGHT', 'PERSONAL_DATA', 'ILLEGAL', 'SPAM', 'OTHER'] as const;
export type Reason = (typeof REASONS)[number];

export interface ComplaintRow {
    readonly complaintId: string;
    readonly targetKind: ComplaintTarget;
    readonly reason: Reason;
    readonly status: ComplaintStatus;
    readonly decision: ComplaintDecision | null;
    readonly createdAt: string;
    readonly dueAt: string;
    /** The server's flag: an OPEN complaint whose due date has passed. */
    readonly overdue: boolean;
    readonly decidedAt: string | null;
    readonly deckId: string | null;
    readonly publicCode: string | null;
    readonly deckTitle: string | null;
    readonly profileAccountId: string | null;
    readonly profileUsername: string | null;
}

export interface ComplaintPage {
    readonly items: readonly ComplaintRow[];
    readonly next: string | null;
}

export interface ComplaintDeck {
    readonly deckId: string;
    /** `false`: the deck is deleted or gone; the other fields are what the complaint remembers. */
    readonly live: boolean;
    readonly ownerId: string | null;
    readonly publicCode: string | null;
    readonly title: string | null;
    readonly visibility: string;
    readonly published: boolean;
    readonly memberCount: number;
    readonly exerciseCount: number;
    readonly hiddenByModeration: boolean;
    readonly hiddenAt: string | null;
    readonly openComplaints: number;
}

export interface ComplaintTakedown {
    readonly takedownId: string;
    readonly unitKind: UnitKind;
    readonly unitKey: string;
    readonly revisionId: string;
    readonly createdAt: string;
}

export interface ComplaintDetail {
    readonly complaintId: string;
    readonly targetKind: ComplaintTarget;
    readonly reason: Reason;
    readonly text: string | null;
    readonly status: ComplaintStatus;
    readonly decision: ComplaintDecision | null;
    readonly reporterId: string;
    readonly createdAt: string;
    readonly dueAt: string;
    readonly overdue: boolean;
    readonly decidedAt: string | null;
    readonly decidedBy: string | null;
    readonly retainUntil: string;
    readonly deck: ComplaintDeck | null;
    readonly profile: { readonly accountId: string; readonly username: string } | null;
    readonly takedowns: { readonly count: number; readonly truncated: boolean; readonly items: readonly ComplaintTakedown[] };
    /** Present on the answers of `:decide` and `:unhide` only: `false` for a repeat that changed nothing. */
    readonly changed: boolean | null;
}

/** One material or exercise of the deck's published revision, as the unit picker lists it. */
export interface UnitRow {
    readonly kind: UnitKind;
    readonly key: string;
    readonly revisionId: string;
    readonly ordinal: number;
    /** The material title, or the plain-text summary of the exercise question. */
    readonly label: string;
    /** The mechanic of an exercise (`FREE_RESPONSE`), `null` for a material. */
    readonly type: string | null;
    readonly takenDown: boolean;
}

export interface UnitsPage {
    readonly kind: UnitKind;
    readonly total: number;
    readonly units: readonly UnitRow[];
    readonly next: string | null;
}

export interface JournalEntry {
    readonly journalId: string;
    readonly complaintId: string;
    readonly action: JournalAction;
    readonly actorId: string;
    readonly occurredAt: string;
    readonly targetKind: ComplaintTarget;
    readonly deckId: string | null;
    readonly publicCode: string | null;
    readonly profileAccountId: string | null;
    readonly unitCount: number;
}

export interface JournalPage {
    readonly items: readonly JournalEntry[];
    readonly next: string | null;
}

/** A unit the legal takedown names; `revisionId: null` means «the revision the published deck holds». */
export interface TakedownUnit {
    readonly kind: UnitKind;
    readonly key: string;
    readonly revisionId: string | null;
}

export type DecideRequest =
    | { readonly decision: 'REJECTED' | 'HIDDEN' | 'ACCOUNT_REFERRED' }
    | { readonly decision: 'TAKEN_DOWN'; readonly units: readonly TakedownUnit[]; readonly hide: boolean };

const cursor = (value: unknown): string => str(value, 4096);

/** A complaint id from the address: a UUID or nothing (the page then says the complaint is not found, without a request). */
export function uuidOrNull(value: string | null): string | null {
    return value !== null && /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/iu.test(value) ? value.toLowerCase() : null;
}

function optionalStr(value: unknown, maximum: number): string | null { return nullable(value, item => str(item, maximum)); }

export function parseComplaintRow(value: unknown): ComplaintRow {
    const o = object(value);
    const row: ComplaintRow = {
        complaintId: uuid(o['complaintId']),
        targetKind: enumeration(o['targetKind'], COMPLAINT_TARGETS),
        reason: enumeration(o['reason'], REASONS),
        status: enumeration(o['status'], COMPLAINT_STATUSES),
        decision: nullable(o['decision'], item => enumeration(item, COMPLAINT_DECISIONS)),
        createdAt: instant(o['createdAt']),
        dueAt: instant(o['dueAt']),
        overdue: flag(o['overdue']),
        decidedAt: nullable(o['decidedAt'], instant),
        deckId: nullable(o['deckId'], uuid),
        publicCode: optionalStr(o['publicCode'], 40),
        deckTitle: optionalStr(o['deckTitle'], 500),
        profileAccountId: nullable(o['profileAccountId'], uuid),
        profileUsername: optionalStr(o['profileUsername'], 64)
    };
    if ((row.status === 'OPEN') !== (row.decision === null)) throw new AdminProtocolError();
    return row;
}

export function parseComplaintPage(value: unknown): ComplaintPage {
    const o = object(value);
    return { items: array(o['items'], parseComplaintRow, 100), next: nullable(o['nextCursor'], cursor) };
}

function parseDeck(value: unknown): ComplaintDeck {
    const o = object(value);
    return {
        deckId: uuid(o['deckId']),
        live: flag(o['live']),
        ownerId: nullable(o['ownerId'], uuid),
        publicCode: optionalStr(o['publicCode'], 40),
        title: optionalStr(o['title'], 500),
        visibility: str(o['visibility'], 20),
        published: flag(o['published']),
        memberCount: integer(o['memberCount']),
        exerciseCount: integer(o['exerciseCount']),
        hiddenByModeration: flag(o['hiddenByModeration']),
        hiddenAt: nullable(o['hiddenAt'], instant),
        openComplaints: integer(o['openComplaints'])
    };
}

function parseTakedown(value: unknown): ComplaintTakedown {
    const o = object(value);
    return {
        takedownId: uuid(o['takedownId']),
        unitKind: enumeration(o['unitKind'], UNIT_KINDS),
        unitKey: uuid(o['unitKey']),
        revisionId: uuid(o['revisionId']),
        createdAt: instant(o['createdAt'])
    };
}

export function parseComplaintDetail(value: unknown): ComplaintDetail {
    const o = object(value);
    const takedowns = object(o['takedowns']);
    const detail: ComplaintDetail = {
        complaintId: uuid(o['complaintId']),
        targetKind: enumeration(o['targetKind'], COMPLAINT_TARGETS),
        reason: enumeration(o['reason'], REASONS),
        text: optionalStr(o['text'], 4000),
        status: enumeration(o['status'], COMPLAINT_STATUSES),
        decision: nullable(o['decision'], item => enumeration(item, COMPLAINT_DECISIONS)),
        reporterId: uuid(o['reporterId']),
        createdAt: instant(o['createdAt']),
        dueAt: instant(o['dueAt']),
        overdue: flag(o['overdue']),
        decidedAt: nullable(o['decidedAt'], instant),
        decidedBy: nullable(o['decidedBy'], uuid),
        retainUntil: instant(o['retainUntil']),
        deck: nullable(o['deck'], parseDeck),
        profile: nullable(o['profile'], item => {
            const profile = object(item);
            return { accountId: uuid(profile['accountId']), username: str(profile['username'], 64) };
        }),
        takedowns: { count: integer(takedowns['count']), truncated: flag(takedowns['truncated']), items: array(takedowns['items'], parseTakedown, 200) },
        changed: o['changed'] === undefined ? null : flag(o['changed'])
    };
    if ((detail.targetKind === 'DECK') !== (detail.deck !== null) || (detail.targetKind === 'PROFILE') !== (detail.profile !== null)) throw new AdminProtocolError();
    if ((detail.status === 'OPEN') !== (detail.decision === null)) throw new AdminProtocolError();
    return detail;
}

export function parseUnitsPage(value: unknown): UnitsPage {
    const o = object(value);
    const kind = enumeration(o['kind'], UNIT_KINDS);
    return {
        kind,
        total: integer(o['total']),
        units: array(o['units'], item => {
            const unit = object(item);
            const unitKind = enumeration(unit['kind'], UNIT_KINDS);
            if (unitKind !== kind) throw new AdminProtocolError();
            return {
                kind: unitKind,
                key: uuid(unit['key']),
                revisionId: uuid(unit['revisionId']),
                ordinal: integer(unit['ordinal']),
                label: unitKind === 'ITEM' ? str(unit['title'], 1000) : str(unit['prompt'], 1000),
                type: unitKind === 'ITEM' ? null : str(unit['type'], 40),
                takenDown: flag(unit['takenDown'])
            };
        }, 100),
        next: nullable(o['nextCursor'], cursor)
    };
}

export function parseJournalPage(value: unknown): JournalPage {
    const o = object(value);
    return {
        items: array(o['items'], item => {
            const entry = object(item);
            return {
                journalId: uuid(entry['journalId']),
                complaintId: uuid(entry['complaintId']),
                action: enumeration(entry['action'], JOURNAL_ACTIONS),
                actorId: uuid(entry['actorId']),
                occurredAt: instant(entry['occurredAt']),
                targetKind: enumeration(entry['targetKind'], COMPLAINT_TARGETS),
                deckId: nullable(entry['deckId'], uuid),
                publicCode: optionalStr(entry['publicCode'], 40),
                profileAccountId: nullable(entry['profileAccountId'], uuid),
                unitCount: integer(entry['unitCount'])
            };
        }, 100),
        next: nullable(o['nextCursor'], cursor)
    };
}
