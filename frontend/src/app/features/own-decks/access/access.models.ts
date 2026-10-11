import {
    AuthoringProtocolError,
    requireCount,
    requireCursor,
    requireEntity,
    requireInstant,
    requireObject
} from '../../authoring/authoring.models';

/** contracts/decks/access.json: invitations, access requests and grants of an «По приглашению» deck (Share/13). */
export const INVITE_STATUSES = ['ACTIVE', 'EXPIRED', 'EXHAUSTED', 'REVOKED', 'STALE'] as const;
export type InviteStatus = (typeof INVITE_STATUSES)[number];
export const DECISION_STATUSES = ['APPROVED', 'REJECTED'] as const;
export type DecisionStatus = (typeof DECISION_STATUSES)[number];

export const MAX_USES_RANGE = { min: 1, max: 1000 } as const;
export const EXPIRES_IN_DAYS_RANGE = { min: 1, max: 365 } as const;
/** An active invitation per deck is capped at this many (422 `RESOURCE_LIMIT_EXCEEDED` beyond). */
export const ACTIVE_INVITES_LIMIT = 20;
/** Most people who can hold access to one deck: approve and grant-by-login are refused beyond it (422 with this `limit`). */
export const GRANTS_LIMIT = 1000;
export const PAGE_LIMIT = 20;
/** The Identity login of a profile: the only thing «Пригласить по логину» sends. */
export const LOGIN_PATTERN = /^[A-Za-z0-9_.-]{3,50}$/;
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const INVITE_LINK_PATTERN = /^\/d\/([A-Za-z0-9_-]{4,64})\?invite=([A-Za-z0-9_-]{43})$/;
const MAX_ITEMS = 100;

export interface DeckInvite {
    readonly inviteId: string;
    readonly status: InviteStatus;
    /** `null`: unlimited uses. */
    readonly maxUses: number | null;
    readonly uses: number;
    /** `null`: never expires. */
    readonly expiresAt: string | null;
    readonly revokedAt: string | null;
    readonly createdAt: string;
}

/** The creation answer: the only place the token and the ready link ever appear. */
export interface CreatedInvite {
    readonly invite: DeckInvite;
    readonly token: string;
    /** Relative: `/d/{code}?invite={token}`. */
    readonly link: string;
}

export interface CreateInviteRequest {
    readonly maxUses: number | null;
    readonly expiresInDays: number | null;
}

export interface AccessRequest {
    readonly requesterId: string;
    readonly createdAt: string;
}

export interface AccessDecision {
    readonly requesterId: string;
    readonly status: DecisionStatus;
    readonly decidedAt: string;
}

export interface AccessGrant {
    readonly granteeId: string;
    readonly role: 'VIEWER';
    readonly grantedAt: string;
}

export interface KeysetPage<T> {
    readonly items: readonly T[];
    readonly nextCursor: string | null;
}

/** What a failed access request carries: the HTTP status, the stable problem `code` and the `limit` member of a 422. */
export interface AccessFailure {
    readonly status: number;
    readonly code: string | null;
    readonly limit: number | null;
}

export function parseInvite(value: unknown): DeckInvite {
    const object = requireObject(value, ['inviteId', 'status', 'maxUses', 'uses', 'expiresAt', 'revokedAt', 'createdAt']);
    const status = object['status'];
    if (typeof status !== 'string' || !(INVITE_STATUSES as readonly string[]).includes(status)) throw new AuthoringProtocolError('Unknown invitation status.');
    const maxUses = object['maxUses'] === null ? null : requireCount(object['maxUses'], MAX_USES_RANGE.max);
    if (maxUses === 0) throw new AuthoringProtocolError('Invalid invitation limit.');
    const uses = requireCount(object['uses'], MAX_USES_RANGE.max);
    if (maxUses !== null && uses > maxUses) throw new AuthoringProtocolError('Invitation uses exceed the limit.');
    const revokedAt = object['revokedAt'] === null ? null : requireInstant(object['revokedAt']);
    if ((status === 'REVOKED') !== (revokedAt !== null)) throw new AuthoringProtocolError('Invitation status and revocation disagree.');
    return {
        inviteId: requireEntity(object['inviteId']),
        status: status as InviteStatus,
        maxUses, uses,
        expiresAt: object['expiresAt'] === null ? null : requireInstant(object['expiresAt']),
        revokedAt,
        createdAt: requireInstant(object['createdAt'])
    };
}

export function parseCreatedInvite(value: unknown): CreatedInvite {
    const object = requireObject(value, ['invite', 'token', 'link']);
    const token = object['token'];
    const link = object['link'];
    if (typeof token !== 'string' || !TOKEN_PATTERN.test(token)) throw new AuthoringProtocolError('Invalid invitation token.');
    if (typeof link !== 'string' || INVITE_LINK_PATTERN.exec(link)?.[2] !== token) throw new AuthoringProtocolError('Invalid invitation link.');
    return { invite: parseInvite(object['invite']), token, link };
}

export function parseInvitePage(value: unknown): KeysetPage<DeckInvite> {
    return parsePage(value, parseInvite);
}

export function parseRequestPage(value: unknown): KeysetPage<AccessRequest> {
    return parsePage(value, entry => {
        const object = requireObject(entry, ['requesterId', 'createdAt']);
        return { requesterId: requireEntity(object['requesterId']), createdAt: requireInstant(object['createdAt']) };
    });
}

export function parseGrant(value: unknown): AccessGrant {
    const object = requireObject(value, ['granteeId', 'role', 'grantedAt']);
    if (object['role'] !== 'VIEWER') throw new AuthoringProtocolError('Unknown grant role.');
    return { granteeId: requireEntity(object['granteeId']), role: 'VIEWER', grantedAt: requireInstant(object['grantedAt']) };
}

export function parseGrantPage(value: unknown): KeysetPage<AccessGrant> {
    return parsePage(value, parseGrant);
}

export function parseDecision(value: unknown): AccessDecision {
    const object = requireObject(value, ['requesterId', 'status', 'decidedAt']);
    const status = object['status'];
    if (status !== 'APPROVED' && status !== 'REJECTED') throw new AuthoringProtocolError('Unknown decision.');
    return { requesterId: requireEntity(object['requesterId']), status, decidedAt: requireInstant(object['decidedAt']) };
}

/** The answer of redeeming an invitation: `{code}`, the CURRENT public code of the deck (the client opens `/d/{code}`). */
export function parseRedeemed(value: unknown): string {
    const object = requireObject(value, ['code']);
    const code = object['code'];
    if (typeof code !== 'string' || !/^[A-Za-z0-9_-]{4,64}$/.test(code)) throw new AuthoringProtocolError('Invalid public code.');
    return code;
}

/** `{status: PENDING}`: the single answer of an access request. */
export function parseRequestSent(value: unknown): 'PENDING' {
    const object = requireObject(value, ['status']);
    if (object['status'] !== 'PENDING') throw new AuthoringProtocolError('Unexpected request status.');
    return 'PENDING';
}

/** The `AccessFailure` of an HTTP error, or `null` when it is not an HTTP answer (a lost connection is status 0). */
export function accessFailureOf(error: unknown): AccessFailure | null {
    if (error === null || typeof error !== 'object' || !('status' in error) || typeof error.status !== 'number') return null;
    const body = 'error' in error ? error.error : null;
    const code = body !== null && typeof body === 'object' && 'code' in body && typeof body.code === 'string' ? body.code : null;
    const limit = body !== null && typeof body === 'object' && 'limit' in body && typeof body.limit === 'number' ? body.limit : null;
    return { status: error.status, code, limit };
}

/** The absolute address of an invitation: the link of the creation answer on this origin. */
export function absoluteInviteLink(link: string, origin: string): string {
    return new URL(link, origin).toString();
}

/** The login as typed: spaces around it and a leading «@» are not part of it. */
export function normalizeLogin(typed: string): string {
    const trimmed = typed.trim();
    return trimmed.startsWith('@') ? trimmed.slice(1) : trimmed;
}

function parsePage<T>(value: unknown, parseItem: (item: unknown) => T): KeysetPage<T> {
    const object = requireObject(value, ['items', 'nextCursor']);
    const items = object['items'];
    if (!Array.isArray(items) || items.length > MAX_ITEMS) throw new AuthoringProtocolError('Invalid page.');
    return { items: items.map(parseItem), nextCursor: requireCursor(object['nextCursor']) };
}
