/** Wire and view models of the notification center (contracts/notifications, `notifications-v1`). */

export const NOTIFICATION_SEVERITIES = ['INFO', 'WARNING', 'ERROR'] as const;
export type NotificationSeverity = (typeof NOTIFICATION_SEVERITIES)[number];

export const NOTIFICATION_ROUTES = ['WORKSHOP', 'DECK', 'PLANS', 'NONE'] as const;
export type NotificationRoute = (typeof NOTIFICATION_ROUTES)[number];

/**
 * One notification as the server sends it. `kind` stays a plain string: an unknown kind is kept (it still counts
 * as unread) and rendered as nothing. `params` holds identifiers, counts and enums only, never prose.
 */
export interface AppNotification {
    readonly notificationId: string;
    /** Decimal string; compare with {@link compareSeq}, never as a number. */
    readonly seq: string;
    readonly kind: string;
    readonly severity: NotificationSeverity;
    readonly params: Readonly<Record<string, unknown>>;
    readonly route: NotificationRoute;
    readonly createdAt: string;
    readonly expiresAt: string;
}

export interface NotificationPage {
    readonly items: readonly AppNotification[];
    readonly unreadCount: number;
    readonly readUpto: string;
    readonly activeWork: number;
    readonly nextCursor: string | null;
}

export interface NotificationQuery {
    readonly limit?: number;
    /** Catch-up mode: only notifications with a greater `seq`, oldest first. Never together with `cursor`. */
    readonly after?: string;
    readonly cursor?: string;
}

export type NotificationListResult =
    | { readonly kind: 'page'; readonly page: NotificationPage; readonly etag: string }
    | { readonly kind: 'not-modified' };

export interface ReadCursorResult {
    readonly readUpto: string;
    readonly unreadCount: number;
}

export class NotificationProtocolError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'NotificationProtocolError';
    }
}

/** Orders two decimal-string sequence numbers; they can exceed 2^53, so they are compared as BigInt. */
export function compareSeq(left: string, right: string): number {
    const a = BigInt(left);
    const b = BigInt(right);
    return a === b ? 0 : a > b ? 1 : -1;
}
