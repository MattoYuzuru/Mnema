import { HttpErrorResponse } from '@angular/common/http';

import { AuthoringProtocolError } from '../authoring/authoring.models';
import { BlockingBucket, RequestValidationError } from './generation.models';

/**
 * What a failed generation call tells the UI (`contracts/generation/errors.json`, RFC 9457 Problem Details with a stable
 * `code` and additive extension members). Parsed defensively: a member of the wrong type is dropped, never trusted.
 */
export interface GenerationProblem {
    /** HTTP status; `0` for a network failure, `-1` for a response the client refused to read. */
    readonly status: number;
    readonly code: string | null;
    /** `GENERATION_STATE_CONFLICT.reason`, `CAPABILITY_UNAVAILABLE.reason`. */
    readonly reason: string | null;
    /** `RESOURCE_LIMIT_EXCEEDED.limit`. */
    readonly limit: string | null;
    readonly capability: string | null;
    readonly artifactIds: readonly string[];
    readonly activeSessionIds: readonly string[];
    /** `RESOURCE_LIMIT_EXCEEDED.limits`: the configured limits that apply (`maxExerciseTargets`, ...), or `null`. */
    readonly limits: Readonly<Record<string, number>> | null;
    /** The members of `USAGE_LIMIT_REACHED`, when they are all present and well formed. */
    readonly usage: BlockingBucket | null;
    /**
     * The outcome is unknown (no answer, a server error, an unreadable answer): the exact same command may be sent again
     * with the same `commandId`. Any other failure is definitive and the next attempt is a new command.
     */
    readonly uncertain: boolean;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const BUCKET_WINDOWS = ['DAY', 'WEEK', 'MONTH'];
const BUCKET_UNITS = ['CREDITS', 'MINUTES', 'COUNT'];
const PLANS = ['FREE', 'PLUS', 'PRO', 'MAX'];

function memberText(body: Record<string, unknown>, key: string): string | null {
    const value = body[key];
    return typeof value === 'string' && value.length > 0 && value.length <= 64 ? value : null;
}

function memberIds(body: Record<string, unknown>, key: string): readonly string[] {
    const value = body[key];
    return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string' && UUID.test(entry)).slice(0, 20) : [];
}

function readLimitMembers(body: Record<string, unknown>): Readonly<Record<string, number>> | null {
    const value = body['limits'];
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
    const entries = Object.entries(value as Record<string, unknown>)
        .filter((entry): entry is [string, number] => /^max[A-Za-z]{1,40}$/u.test(entry[0]) && count(entry[1]) !== null);
    return entries.length === 0 ? null : Object.fromEntries(entries);
}

function count(value: unknown): number | null {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function instantOrNull(value: unknown): string | null {
    return typeof value === 'string' && Number.isFinite(Date.parse(value)) ? value : null;
}

function readUsage(body: Record<string, unknown>): BlockingBucket | null {
    const { bucket, window, unit, plan } = body;
    const used = count(body['used']);
    const required = count(body['required']);
    const limit = body['limit'] === null ? null : count(body['limit']);
    if (typeof bucket !== 'string' || typeof window !== 'string' || !BUCKET_WINDOWS.includes(window)
        || typeof unit !== 'string' || !BUCKET_UNITS.includes(unit) || typeof plan !== 'string' || !PLANS.includes(plan)
        || used === null || required === null || typeof body['offered'] !== 'boolean'
        || typeof body['fitsAfterRenewal'] !== 'boolean' || (body['limit'] !== null && limit === null)) return null;
    return {
        bucket, window: window as BlockingBucket['window'], unit: unit as BlockingBucket['unit'], limit, used, required,
        offered: body['offered'], renewsAt: body['renewsAt'] === null ? null : instantOrNull(body['renewsAt']),
        fitsAfterRenewal: body['fitsAfterRenewal'], plan: plan as BlockingBucket['plan']
    };
}

export function readProblem(error: unknown): GenerationProblem {
    if (error instanceof HttpErrorResponse) {
        const body = error.error !== null && typeof error.error === 'object' && !Array.isArray(error.error)
            ? error.error as Record<string, unknown> : {};
        const code = memberText(body, 'code');
        return {
            status: error.status, code, reason: memberText(body, 'reason'), limit: memberText(body, 'limit'),
            capability: memberText(body, 'capability'), artifactIds: memberIds(body, 'artifactIds'),
            activeSessionIds: memberIds(body, 'activeSessionIds'), limits: readLimitMembers(body),
            usage: code === 'USAGE_LIMIT_REACHED' ? readUsage(body) : null,
            uncertain: error.status === 0 || error.status >= 500
        };
    }
    // A request the client refused to build was never sent: a definitive validation problem, not an unknown outcome.
    if (error instanceof RequestValidationError) {
        return { status: 400, code: null, reason: null, limit: null, capability: null, artifactIds: [], activeSessionIds: [], limits: null,
            usage: null, uncertain: false };
    }
    // A protocol error means the command may well have been applied: the answer could not be read.
    const unreadable = error instanceof AuthoringProtocolError;
    return { status: unreadable ? -1 : 0, code: null, reason: null, limit: null, capability: null, artifactIds: [],
        activeSessionIds: [], limits: null, usage: null, uncertain: true };
}

export function isStatus(problem: GenerationProblem, status: number, code?: string): boolean {
    return problem.status === status && (code === undefined || problem.code === code);
}
