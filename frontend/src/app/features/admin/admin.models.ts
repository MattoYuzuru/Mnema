/** Owner-console projections; no learning content, provider prompts or secrets cross this boundary. */
export interface AdminAccess {
    readonly owner: true;
    readonly permissions: {
        readonly events: boolean;
        readonly promos: boolean;
        readonly moderation: boolean;
        readonly support: boolean;
    };
}

export interface Percentiles {
    readonly sample: number;
    readonly p50: number | null;
    readonly p95: number | null;
    readonly p99: number | null;
}

export interface FeatureUsage {
    readonly operation: string;
    readonly users: number;
    readonly events: number;
    readonly creditsDebited: number;
    readonly units: number;
    readonly share: number | null;
}

export interface AdminReport {
    readonly from: string;
    readonly to: string;
    readonly generatedAt: string;
    readonly ai: {
        readonly currency: 'USD';
        readonly accuracy: 'ESTIMATE';
        readonly calls: number;
        readonly pendingCalls: number;
        readonly failedCalls: number;
        readonly estimatedCostMicros: number;
        readonly latency: {
            readonly p50Ms: number | null;
            readonly p95Ms: number | null;
            readonly p99Ms: number | null;
        };
        readonly journalRetentionDays: number;
        readonly retentionWindowStart: string;
        readonly rangeIncludesExpiredData: boolean;
        readonly failedCallCostCoverage: 'INCOMPLETE';
        readonly routeGroupsTruncated: boolean;
        readonly byDay: readonly {
            readonly date: string;
            readonly calls: number;
            readonly failedCalls: number;
            readonly estimatedCostMicros: number;
        }[];
        readonly byRoute: readonly {
            readonly capability: string;
            readonly provider: string;
            readonly model: string;
            readonly calls: number;
            readonly failedCalls: number;
            readonly estimatedCostMicros: number;
        }[];
    };
    readonly usage: {
        readonly activeUsers: number;
        readonly creditsDebited: number;
        readonly creditsPerUser: Percentiles;
        readonly features: readonly FeatureUsage[];
        readonly topUsers: readonly {
            readonly accountId: string;
            readonly events: number;
            readonly creditsDebited: number;
        }[];
    };
    readonly learning: {
        readonly studyUsers: number;
        readonly attempts: number;
        readonly completedSessions: number;
        readonly generationUsers: number;
        readonly publishedArtifacts: number;
        readonly generationCoverage: 'RETAINED_SESSIONS';
    };
    readonly media: {
        readonly accuracy: 'INVENTORY';
        readonly blobBytes: number;
        readonly assets: number;
        readonly byState: readonly {
            readonly state: string;
            readonly count: number;
        }[];
    };
    readonly financial: {
        readonly providerInvoices: {
            readonly status: 'UNAVAILABLE';
            readonly reason: 'INVOICE_SOURCE_NOT_CONNECTED';
        };
        readonly revenue: {
            readonly status: 'UNAVAILABLE';
            readonly reason: 'BILLING_NOT_CONNECTED';
        };
        readonly infrastructure: {
            readonly status: 'UNAVAILABLE';
            readonly reason: 'INVOICE_SOURCE_NOT_CONNECTED';
        };
    };
}

export interface DirectoryAccount {
    readonly accountId: string;
    readonly email: string;
    readonly emailVerified: boolean;
    readonly profileUsername: string | null;
    readonly displayName: string | null;
    readonly status: string;
    readonly admin: boolean;
    readonly deletionState: string;
    readonly createdAt: string;
    readonly lastLoginAt: string | null;
}

export interface DirectoryDetail extends DirectoryAccount {
    readonly bannedAt: string | null;
    readonly banReason: string | null;
}

export interface DirectoryPage {
    readonly accounts: readonly DirectoryAccount[];
    readonly next: string | null;
}

export interface UserReport {
    readonly accountId: string;
    readonly from: string;
    readonly to: string;
    readonly generatedAt: string;
    readonly allowanceCoverage: 'STORED_SNAPSHOTS';
    readonly currentEntitlement: {
        readonly plan: 'FREE' | 'PLUS' | 'PRO' | 'MAX';
        readonly source: 'CONFIG' | 'PROMO' | 'BILLING';
        readonly period: 'MONTH' | 'YEAR';
        readonly validUntil: string;
    };
    readonly usage: {
        readonly creditsDebited: number;
        readonly operations: readonly FeatureUsage[];
    };
    readonly learning: {
        readonly decks: number;
        readonly items: number;
        readonly studyAttempts: number;
        readonly completedSessions: number;
        readonly publishedArtifacts: number;
    };
    readonly media: {
        readonly assets: number;
        readonly sourceBytes: number;
    };
    readonly allowances: readonly {
        readonly periodId: string;
        readonly plan: string;
        readonly source: string;
        readonly total: number;
        readonly unlocked: number;
        readonly used: number;
        readonly reserved: number;
        readonly validUntil: string;
        readonly updatedAt: string;
    }[];
}

export type PromoKind = 'TIER_DAYS' | 'TIER_MONTHS' | 'DISCOUNT_PERCENT';
export interface AdminPromo {
    readonly codeId: string;
    readonly hint: string;
    readonly type: PromoKind;
    readonly plan: 'PLUS' | 'PRO' | null;
    readonly days: number | null;
    readonly months: number | null;
    readonly percent: number | null;
    readonly validFrom: string;
    readonly validUntil: string | null;
    readonly maxRedemptions: number;
    readonly oncePerAccount: boolean;
    readonly channel: string | null;
    readonly enabled: boolean;
    readonly redemptions: number;
    readonly createdAt: string;
}

export interface PromoCreate {
    readonly type: PromoKind;
    readonly plan: 'PLUS' | 'PRO' | null;
    readonly days: number | null;
    readonly months: number | null;
    readonly percent: number | null;
    readonly validFrom: string | null;
    readonly validUntil: string | null;
    readonly maxRedemptions: number;
    readonly oncePerAccount: boolean;
    readonly channel: string | null;
    readonly code: string;
}

export interface PromoPage {
    readonly codes: readonly AdminPromo[];
    readonly next: string | null;
}

export interface AuditEntry {
    readonly auditId: string;
    readonly actorAccountId: string;
    readonly action: string;
    readonly resourceId: string;
    readonly commandId: string | null;
    readonly occurredAt: string;
    readonly reason?: string | null;
}

export interface AuditPage {
    readonly entries: readonly AuditEntry[];
    readonly next: string | null;
}

export type TicketStatus = 'open' | 'working' | 'waiting' | 'closed';
export type DeliveryState = 'queued' | 'sending' | 'sent' | 'failed' | 'uncertain';
export interface SupportTicket {
    readonly id: string;
    readonly version: number;
    readonly category: 'bug' | 'idea' | 'question' | 'other';
    readonly status: TicketStatus;
    readonly userId: string;
    readonly username: string | null;
    readonly firstName: string;
    readonly accountId: string | null;
    readonly createdAt: string;
    readonly submittedAt: string | null;
    readonly updatedAt: string;
    readonly latestDelivery: DeliveryState | null;
}

export interface SupportPage {
    readonly entries: readonly SupportTicket[];
    readonly nextCursor: string | null;
}

export interface SupportMessage {
    readonly id: string;
    readonly direction: 'in' | 'out' | 'note';
    readonly text: string;
    readonly createdAt: string;
    readonly attachment: {
        readonly kind: string;
        readonly name: string | null;
        readonly size: number | null;
        readonly mimeType: string | null;
    } | null;
    readonly delivery: DeliveryState | null;
}

export interface Conversation {
    readonly ticket: SupportTicket;
    readonly messages: readonly SupportMessage[];
    readonly nextMessageCursor: string | null;
}

export interface TicketCommand {
    readonly commandId: string;
    readonly expectedVersion: number;
    readonly type: 'reply' | 'note' | 'status';
    readonly text?: string;
    readonly status?: TicketStatus;
}

export interface CommandReceipt {
    readonly commandId: string;
    readonly ticketId: string;
    readonly version: number;
    readonly messageId: string | null;
    readonly outboxId: string | null;
    readonly delivery: DeliveryState | null;
}

export class AdminProtocolError extends Error {
    constructor() {
        super('Invalid administrative response');
        this.name = 'AdminProtocolError';
    }
}

export function object(value: unknown): Record<string, unknown> {
    if (!value || typeof value !== 'object' || Array.isArray(value))
        throw new AdminProtocolError();
    return value as Record<string, unknown>;
}

export function str(value: unknown, maximum = 500): string {
    if (typeof value !== 'string' || value.length > maximum)
        throw new AdminProtocolError();
    return value;
}

export function num(value: unknown): number {
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > Number.MAX_SAFE_INTEGER)
        throw new AdminProtocolError();
    return value;
}

export function integer(value: unknown): number {
    const n = num(value);
    if (!Number.isSafeInteger(n))
        throw new AdminProtocolError();
    return n;
}

export function flag(value: unknown): boolean {
    if (typeof value !== 'boolean')
        throw new AdminProtocolError();
    return value;
}

export function nullable<T>(value: unknown, parser: (value: unknown) => T): T | null {
    return value === null ? null : parser(value);
}

export function enumeration<T extends string>(value: unknown, values: readonly T[]): T {
    const result = str(value);
    if (!values.includes(result as T))
        throw new AdminProtocolError();
    return result as T;
}

export function uuid(value: unknown): string {
    const result = str(value, 36);
    if (!/^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/iu.test(result))
        throw new AdminProtocolError();
    return result;
}

export function instant(value: unknown): string {
    const result = str(value, 40);
    if (!/^\d{4}-\d{2}-\d{2}T/u.test(result) || !Number.isFinite(Date.parse(result)))
        throw new AdminProtocolError();
    return result;
}

export function day(value: unknown): string {
    const result = str(value, 10);
    if (!/^\d{4}-\d{2}-\d{2}$/u.test(result) || new Date(`${result}T00:00:00Z`).toISOString().slice(0, 10) !== result)
        throw new AdminProtocolError();
    return result;
}

export function array<T>(value: unknown, parse: (value: unknown) => T, maximum = 200): readonly T[] {
    if (!Array.isArray(value) || value.length > maximum)
        throw new AdminProtocolError();
    return value.map(parse);
}
const decimalId = (value: unknown) => {
    const result = str(value, 24);
    if (!/^[1-9]\d*$/u.test(result))
        throw new AdminProtocolError();
    return result;
};
const delivery = (value: unknown) => enumeration(value, ['queued', 'sending', 'sent', 'failed', 'uncertain'] as const);
export function parseAccess(value: unknown): AdminAccess {
    const o = object(value);
    if (o['owner'] !== true)
        throw new AdminProtocolError();
    const p = object(o['permissions']);
    return {
        owner: true,
        permissions: {
            events: flag(p['events']),
            promos: flag(p['promos']),
            moderation: flag(p['moderation']),
            support: flag(p['support'])
        }
    };
}
function parsePercentiles(value: unknown): Percentiles {
    const o = object(value);
    return {
        sample: integer(o['sample']),
        p50: nullable(o['p50'], num),
        p95: nullable(o['p95'], num),
        p99: nullable(o['p99'], num)
    };
}

export function parseFeature(value: unknown): FeatureUsage {
    const o = object(value);
    const share = nullable(o['share'], num);
    if (share !== null && share > 1)
        throw new AdminProtocolError();
    return {
        operation: str(o['operation']),
        users: integer(o['users']),
        events: integer(o['events']),
        creditsDebited: integer(o['creditsDebited']),
        units: num(o['units']),
        share
    };
}

export function parseReport(value: unknown): AdminReport {
    const o = object(value), ai = object(o['ai']), latency = object(ai['latency']), usage = object(o['usage']), learning = object(o['learning']), media = object(o['media']), financial = object(o['financial']);
    const revenue = object(financial['revenue']), infrastructure = object(financial['infrastructure']), invoices = object(financial['providerInvoices']);
    return {
        from: day(o['from']),
        to: day(o['to']),
        generatedAt: instant(o['generatedAt']),
        ai: {
            currency: enumeration(ai['currency'], ['USD'] as const),
            accuracy: enumeration(ai['accuracy'], ['ESTIMATE'] as const),
            calls: integer(ai['calls']),
            pendingCalls: integer(ai['pendingCalls']),
            failedCalls: integer(ai['failedCalls']),
            estimatedCostMicros: integer(ai['estimatedCostMicros']),
            latency: {
                p50Ms: nullable(latency['p50Ms'], num),
                p95Ms: nullable(latency['p95Ms'], num),
                p99Ms: nullable(latency['p99Ms'], num)
            },
            journalRetentionDays: integer(ai['journalRetentionDays']),
            retentionWindowStart: instant(ai['retentionWindowStart']),
            rangeIncludesExpiredData: flag(ai['rangeIncludesExpiredData']),
            failedCallCostCoverage: enumeration(ai['failedCallCostCoverage'], ['INCOMPLETE'] as const),
            routeGroupsTruncated: flag(ai['routeGroupsTruncated']),
            byDay: array(ai['byDay'], value => {
                const d = object(value);
                return {
                    date: day(d['date']),
                    calls: integer(d['calls']),
                    failedCalls: integer(d['failedCalls']),
                    estimatedCostMicros: integer(d['estimatedCostMicros'])
                };
            }, 90),
            byRoute: array(ai['byRoute'], value => {
                const r = object(value);
                return {
                    capability: str(r['capability']),
                    provider: str(r['provider']),
                    model: str(r['model']),
                    calls: integer(r['calls']),
                    failedCalls: integer(r['failedCalls']),
                    estimatedCostMicros: integer(r['estimatedCostMicros'])
                };
            }, 1000)
        },
        usage: {
            activeUsers: integer(usage['activeUsers']),
            creditsDebited: integer(usage['creditsDebited']),
            creditsPerUser: parsePercentiles(usage['creditsPerUser']),
            features: array(usage['features'], parseFeature),
            topUsers: array(usage['topUsers'], value => {
                const u = object(value);
                return {
                    accountId: uuid(u['accountId']),
                    events: integer(u['events']),
                    creditsDebited: integer(u['creditsDebited'])
                };
            }, 20)
        },
        learning: {
            studyUsers: integer(learning['studyUsers']),
            attempts: integer(learning['attempts']),
            completedSessions: integer(learning['completedSessions']),
            generationUsers: integer(learning['generationUsers']),
            publishedArtifacts: integer(learning['publishedArtifacts']),
            generationCoverage: enumeration(learning['generationCoverage'], ['RETAINED_SESSIONS'] as const)
        },
        media: {
            accuracy: enumeration(media['accuracy'], ['INVENTORY'] as const),
            blobBytes: integer(media['blobBytes']),
            assets: integer(media['assets']),
            byState: array(media['byState'], value => {
                const s = object(value);
                return {
                    state: str(s['state']),
                    count: integer(s['count'])
                };
            })
        },
        financial: {
            providerInvoices: {
                status: enumeration(invoices['status'], ['UNAVAILABLE'] as const),
                reason: enumeration(invoices['reason'], ['INVOICE_SOURCE_NOT_CONNECTED'] as const)
            },
            revenue: {
                status: enumeration(revenue['status'], ['UNAVAILABLE'] as const),
                reason: enumeration(revenue['reason'], ['BILLING_NOT_CONNECTED'] as const)
            },
            infrastructure: {
                status: enumeration(infrastructure['status'], ['UNAVAILABLE'] as const),
                reason: enumeration(infrastructure['reason'], ['INVOICE_SOURCE_NOT_CONNECTED'] as const)
            }
        }
    };
}

export function parseAccount(value: unknown): DirectoryAccount {
    const o = object(value);
    return {
        accountId: uuid(o['accountId']),
        email: str(o['email'], 320),
        emailVerified: flag(o['emailVerified']),
        profileUsername: nullable(o['profileUsername'], str),
        displayName: nullable(o['displayName'], str),
        status: str(o['status'], 40),
        admin: flag(o['admin']),
        deletionState: str(o['deletionState'], 40),
        createdAt: instant(o['createdAt']),
        lastLoginAt: nullable(o['lastLoginAt'], instant)
    };
}

export function parseDetail(value: unknown): DirectoryDetail {
    const o = object(value);
    return {
        ...parseAccount(value),
        bannedAt: nullable(o['bannedAt'], instant),
        banReason: nullable(o['banReason'], str)
    };
}

export function parseDirectory(value: unknown): DirectoryPage {
    const o = object(value);
    return {
        accounts: array(o['accounts'], parseAccount),
        next: nullable(o['next'], value => str(value, 1000))
    };
}

export function parseUserReport(value: unknown): UserReport {
    const o = object(value), entitlement = object(o['currentEntitlement']), usage = object(o['usage']), learning = object(o['learning']), media = object(o['media']);
    return {
        accountId: uuid(o['accountId']),
        from: day(o['from']),
        to: day(o['to']),
        generatedAt: instant(o['generatedAt']),
        allowanceCoverage: enumeration(o['allowanceCoverage'], ['STORED_SNAPSHOTS'] as const),
        currentEntitlement: {
            plan: enumeration(entitlement['plan'], ['FREE', 'PLUS', 'PRO', 'MAX'] as const),
            source: enumeration(entitlement['source'], ['CONFIG', 'PROMO', 'BILLING'] as const),
            period: enumeration(entitlement['period'], ['MONTH', 'YEAR'] as const),
            validUntil: instant(entitlement['validUntil'])
        },
        usage: {
            creditsDebited: integer(usage['creditsDebited']),
            operations: array(usage['operations'], parseFeature)
        },
        learning: {
            decks: integer(learning['decks']),
            items: integer(learning['items']),
            studyAttempts: integer(learning['studyAttempts']),
            completedSessions: integer(learning['completedSessions']),
            publishedArtifacts: integer(learning['publishedArtifacts'])
        },
        media: {
            assets: integer(media['assets']),
            sourceBytes: integer(media['sourceBytes'])
        },
        allowances: array(o['allowances'], value => {
            const a = object(value);
            return {
                periodId: str(a['periodId']),
                plan: str(a['plan']),
                source: str(a['source']),
                total: integer(a['total']),
                unlocked: integer(a['unlocked']),
                used: integer(a['used']),
                reserved: integer(a['reserved']),
                validUntil: instant(a['validUntil']),
                updatedAt: instant(a['updatedAt'])
            };
        })
    };
}

export function parsePromo(value: unknown): AdminPromo {
    const o = object(value);
    return {
        codeId: uuid(o['codeId']),
        hint: str(o['hint'], 64),
        type: enumeration(o['type'], ['TIER_DAYS', 'TIER_MONTHS', 'DISCOUNT_PERCENT'] as const),
        plan: nullable(o['plan'], value => enumeration(value, ['PLUS', 'PRO'] as const)),
        days: nullable(o['days'], integer),
        months: nullable(o['months'], integer),
        percent: nullable(o['percent'], integer),
        validFrom: instant(o['validFrom']),
        validUntil: nullable(o['validUntil'], instant),
        maxRedemptions: integer(o['maxRedemptions']),
        oncePerAccount: flag(o['oncePerAccount']),
        channel: nullable(o['channel'], str),
        enabled: flag(o['enabled']),
        redemptions: integer(o['redemptions']),
        createdAt: instant(o['createdAt'])
    };
}

export function parsePromoPage(value: unknown): PromoPage {
    const o = object(value);
    return {
        codes: array(o['codes'], parsePromo),
        next: nullable(o['next'], uuid)
    };
}

export function parseAuditPage(value: unknown): AuditPage {
    const o = object(value);
    return {
        entries: array(o['entries'], value => {
            const r = object(value);
            return {
                auditId: uuid(r['auditId']),
                actorAccountId: uuid(r['actorAccountId']),
                action: str(r['action']),
                resourceId: str(r['resourceId']),
                commandId: nullable(r['commandId'], uuid),
                occurredAt: instant(r['occurredAt']),
                ...('reason' in r ? {
                    reason: nullable(r['reason'], str)
                } : {})
            };
        }),
        next: nullable(o['next'], uuid)
    };
}

export function parseTicket(value: unknown): SupportTicket {
    const o = object(value);
    return {
        id: decimalId(o['id']),
        version: integer(o['version']),
        category: enumeration(o['category'], ['bug', 'idea', 'question', 'other'] as const),
        status: enumeration(o['status'], ['open', 'working', 'waiting', 'closed'] as const),
        userId: decimalId(o['userId']),
        username: nullable(o['username'], str),
        firstName: str(o['firstName']),
        accountId: nullable(o['accountId'], uuid),
        createdAt: instant(o['createdAt']),
        submittedAt: nullable(o['submittedAt'], instant),
        updatedAt: instant(o['updatedAt']),
        latestDelivery: nullable(o['latestDelivery'], delivery)
    };
}

export function parseSupportPage(value: unknown): SupportPage {
    const o = object(value);
    return {
        entries: array(o['entries'], parseTicket, 100),
        nextCursor: nullable(o['nextCursor'], decimalId)
    };
}

export function parseConversation(value: unknown): Conversation {
    const o = object(value);
    return {
        ticket: parseTicket(o['ticket']),
        messages: array(o['messages'], value => {
            const m = object(value);
            return {
                id: decimalId(m['id']),
                direction: enumeration(m['direction'], ['in', 'out', 'note'] as const),
                text: str(m['text'], 16000),
                createdAt: instant(m['createdAt']),
                delivery: nullable(m['delivery'], delivery),
                attachment: nullable(m['attachment'], value => {
                    const a = object(value);
                    return {
                        kind: str(a['kind']),
                        name: nullable(a['name'], str),
                        size: nullable(a['size'], integer),
                        mimeType: nullable(a['mimeType'], str)
                    };
                })
            };
        }, 100),
        nextMessageCursor: nullable(o['nextMessageCursor'], decimalId)
    };
}

export function parseReceipt(value: unknown): CommandReceipt {
    const o = object(value);
    return {
        commandId: uuid(o['commandId']),
        ticketId: decimalId(o['ticketId']),
        version: integer(o['version']),
        messageId: nullable(o['messageId'], decimalId),
        outboxId: nullable(o['outboxId'], decimalId),
        delivery: nullable(o['delivery'], delivery)
    };
}
