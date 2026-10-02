import usageFixture from '../../../../../contracts/usage/usage.json';

/** Canonical wire fixtures shared with the backend (contracts/usage/usage.json). Specs read them; production code never imports this file. */
const usage = usageFixture as unknown as Record<string, any>;

export function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T; }

export const privateHeaders = { 'Cache-Control': 'private, no-store' };

/** A paid plan with the full bar open (contract example, PLUS). */
export const plusUsage = (): Record<string, any> => clone(usage['usageResponse']);

/** Free: 13 of 50 credits unlocked, one portion, the STT counter above 80 %. */
export const freeUsage = (): Record<string, any> => clone(usage['usageResponseFree']);

/** MAX: no monthly speech-to-text limit, a per-day velocity instead. */
export function maxUsage(): Record<string, any> {
    const body = plusUsage();
    body['plan'] = 'MAX';
    body['credits'] = { total: 1780, unlocked: 1780, used: 0, reserved: 0, remaining: 1780, percentUsed: 0 };
    body['fairUse'] = { stt: clone(usage['usageResponseMaxFairUse']['stt']), assessment: body['fairUse']['assessment'] };
    return body;
}

export const usageContract = usage;
