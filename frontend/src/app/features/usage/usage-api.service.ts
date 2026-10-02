import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { appConfig } from '../../app.config';
import {
    USAGE_PLANS,
    UsageDailyBurst,
    UsageFairUse,
    UsageFairUseBucket,
    UsageProtocolError,
    UsageSnapshot,
    UsageWeeklyUnlock
} from './usage.models';

const RESPONSE_KEYS = ['rateCardVersion', 'plan', 'period', 'entitlement', 'credits', 'dailyBurst', 'weeklyUnlock',
    'fairUse', 'caps', 'updatedAt'];
const RATE_CARD = /^rc-[A-Za-z0-9.-]{1,28}$/u;
const PERIOD_ID = /^\d{4}-\d{2}$/u;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/u;
const BUCKET_KEYS = ['unit', 'used', 'limit', 'usedToday', 'limitToday', 'warn'];

/**
 * HTTP boundary of the usage bar. The envelope is parsed strictly (exact keys, integers, ordered instants); the caller
 * sees only the fields the profile needs. `entitlement` and `caps` are validated but not exposed: nothing reads them yet.
 */
@Injectable({ providedIn: 'root' })
export class UsageApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/u, '');

    load(): Observable<UsageSnapshot> {
        return this.http.get<unknown>(`${this.baseUrl}/usage`, { observe: 'response' }).pipe(map(response => {
            if (response.status !== 200) throw protocol('Unexpected usage status.');
            privateResponse(response);
            return parseUsage(response.body);
        }));
    }
}

function protocol(message: string): UsageProtocolError { return new UsageProtocolError(message); }

function record(value: unknown): Record<string, unknown> {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw protocol('Expected object.');
    return value as Record<string, unknown>;
}

function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
    const object = record(value);
    const actual = Object.keys(object);
    if (actual.length !== keys.length || actual.some(key => !keys.includes(key))) throw protocol('Unexpected shape.');
    return object;
}

function integer(value: unknown, maximum = 1_000_000_000): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0 || value > maximum) throw protocol('Invalid number.');
    return value;
}

function nullable<T>(value: unknown, parse: (value: unknown) => T): T | null { return value === null ? null : parse(value); }

function instant(value: unknown): string {
    if (typeof value !== 'string' || !INSTANT.test(value) || Number.isNaN(Date.parse(value))) throw protocol('Invalid timestamp.');
    return value;
}

function pattern(value: unknown, expected: RegExp): string {
    if (typeof value !== 'string' || !expected.test(value)) throw protocol('Invalid identifier.');
    return value;
}

function bool(value: unknown): boolean {
    if (typeof value !== 'boolean') throw protocol('Invalid flag.');
    return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
    if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) throw protocol('Invalid enum value.');
    return value as T;
}

function parseUsage(value: unknown): UsageSnapshot {
    const body = exact(value, RESPONSE_KEYS);
    const period = exact(body['period'], ['periodId', 'start', 'end']);
    const start = instant(period['start']);
    const end = instant(period['end']);
    if (Date.parse(end) <= Date.parse(start)) throw protocol('Invalid period.');
    exact(body['entitlement'], ['source', 'validUntil']);
    const credits = exact(body['credits'], ['total', 'unlocked', 'used', 'reserved', 'remaining', 'percentUsed']);
    const parsed = {
        total: integer(credits['total']), unlocked: integer(credits['unlocked']), used: integer(credits['used']),
        reserved: integer(credits['reserved']), remaining: integer(credits['remaining']),
        percentUsed: integer(credits['percentUsed'], 100)
    };
    if (parsed.unlocked > parsed.total || parsed.remaining > parsed.unlocked) throw protocol('Inconsistent credits.');
    const caps = exact(body['caps'], ['podcasts', 'qualityImages', 'highFactcheck', 'smartPlan']);
    Object.values(caps).forEach(cap => {
        const entry = exact(cap, ['used', 'limit', 'window']);
        integer(entry['used']);
        integer(entry['limit']);
        oneOf(entry['window'], ['DAY', 'WEEK', 'MONTH']);
    });
    return {
        rateCardVersion: pattern(body['rateCardVersion'], RATE_CARD),
        plan: oneOf(body['plan'], USAGE_PLANS),
        period: { periodId: pattern(period['periodId'], PERIOD_ID), start, end },
        credits: parsed,
        dailyBurst: nullable(body['dailyBurst'], parseDailyBurst),
        weeklyUnlock: nullable(body['weeklyUnlock'], parseWeeklyUnlock),
        fairUse: parseFairUse(body['fairUse']),
        updatedAt: instant(body['updatedAt'])
    };
}

function parseDailyBurst(value: unknown): UsageDailyBurst {
    const burst = exact(value, ['limitCredits', 'debitedTodayCredits', 'remainingTodayCredits', 'resetsAt', 'deferredUntil']);
    return {
        limitCredits: integer(burst['limitCredits']), debitedTodayCredits: integer(burst['debitedTodayCredits']),
        remainingTodayCredits: integer(burst['remainingTodayCredits']), resetsAt: instant(burst['resetsAt']),
        deferredUntil: nullable(burst['deferredUntil'], instant)
    };
}

function parseWeeklyUnlock(value: unknown): UsageWeeklyUnlock {
    const unlock = exact(value, ['portions', 'unlockedPortions', 'nextUnlockAt', 'nextPortionCredits']);
    const portions = unlock['portions'];
    if (!Array.isArray(portions) || portions.length < 1 || portions.length > 12) throw protocol('Invalid portions.');
    const unlocked = integer(unlock['unlockedPortions'], portions.length);
    return {
        portions: portions.map(portion => integer(portion)), unlockedPortions: unlocked,
        nextUnlockAt: nullable(unlock['nextUnlockAt'], instant), nextPortionCredits: nullable(unlock['nextPortionCredits'], integer)
    };
}

function parseFairUse(value: unknown): UsageFairUse {
    const fairUse = exact(value, ['stt', 'assessment']);
    const stt = record(fairUse['stt']);
    // MAX has no monthly speech limit and carries its per-day velocity instead; the two velocity keys come together.
    const velocity = 'velocityPerDay' in stt;
    const keys = [...BUCKET_KEYS, 'usedSeconds', ...(velocity ? ['velocityPerDay', 'usedTodayVelocity'] : [])];
    exact(stt, keys);
    oneOf(stt['unit'], ['MINUTES']);
    integer(stt['usedSeconds']);
    if (velocity) { integer(stt['velocityPerDay']); integer(stt['usedTodayVelocity']); }
    const assessment = exact(fairUse['assessment'], BUCKET_KEYS);
    oneOf(assessment['unit'], ['ANSWERS']);
    return { stt: parseBucket(stt), assessment: parseBucket(assessment) };
}

function parseBucket(bucket: Record<string, unknown>): UsageFairUseBucket {
    return {
        used: integer(bucket['used']), limit: nullable(bucket['limit'], integer), usedToday: integer(bucket['usedToday']),
        limitToday: nullable(bucket['limitToday'], integer), warn: bool(bucket['warn'])
    };
}

function privateResponse(response: HttpResponse<unknown>): void {
    const directives = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!directives.includes('private') || !directives.includes('no-store')) throw protocol('Usage response can be cached.');
}
