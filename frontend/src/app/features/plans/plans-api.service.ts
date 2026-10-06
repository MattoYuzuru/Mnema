import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { LEARNING_GOALS } from '../goal/goal.models';
import { PLAN_IDS, PendingDiscount, PlanAllowances, PlanEntry, PlansCatalog, PlansCurrent } from './plans.models';
import { bool, exact, instant, integer, nullable, oneOf, privateOk, protocol } from './wire';

const ENTRY_KEYS = ['plan', 'availability', 'priceRub', 'perDayRub', 'yearDiscountPercent', 'highlights', 'allowances', 'recommendedFor'];
const ALLOWANCE_KEYS = ['materialsPerMonth', 'voiceMinutesPerMonth', 'voiceMinutesPerDay', 'answerChecksPerMonth', 'answerChecksPerDay',
    'podcastsPerMonth', 'qualityImagesPerMonth', 'factChecksPerMonth', 'smartPlans'];

/**
 * HTTP boundary of the paywall catalogue (`GET /api/plans`). It only reads: nothing a page or a URL carries can change
 * an entitlement, and this service has no write.
 */
@Injectable({ providedIn: 'root' })
export class PlansApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/u, '');

    load(): Observable<PlansCatalog> {
        return this.http.get<unknown>(`${this.baseUrl}/plans`, { observe: 'response' }).pipe(map(response => {
            privateOk(response);
            return parsePlans(response.body);
        }));
    }
}

export function parsePlans(value: unknown): PlansCatalog {
    const body = exact(value, ['current', 'plans', 'experiments', 'pendingDiscount']);
    const plans = body['plans'];
    if (!Array.isArray(plans) || plans.length < 3 || plans.length > PLAN_IDS.length) throw protocol('Invalid plans.');
    const entries = plans.map(parseEntry);
    if (entries.some((entry, position) => entry.plan !== PLAN_IDS[position]
        || entry.availability !== (entry.plan === 'MAX' ? 'TEASER' : 'AVAILABLE'))) throw protocol('Invalid plan order or availability.');
    return {
        current: parseCurrent(body['current']), plans: entries, experiments: parseExperiments(body['experiments']),
        pendingDiscount: nullable(body['pendingDiscount'], parseDiscount)
    };
}

/** `{key: variant}`: at most a handful of short identifiers. */
function parseExperiments(value: unknown): Readonly<Record<string, string>> {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw protocol('Invalid experiments.');
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length > 20) throw protocol('Invalid experiments.');
    const result: Record<string, string> = {};
    for (const [key, variant] of entries) {
        if (!/^[a-z][a-z0-9_]{1,39}$/u.test(key) || typeof variant !== 'string' || !/^[a-z][a-z0-9_]{0,31}$/u.test(variant)) throw protocol('Invalid experiment.');
        result[key] = variant;
    }
    return result;
}

function parseDiscount(value: unknown): PendingDiscount {
    const discount = exact(value, ['percent', 'plan', 'validUntil']);
    const percent = integer(discount['percent'], 90);
    if (percent < 1) throw protocol('Invalid discount.');
    return { percent, plan: nullable(discount['plan'], plan => oneOf(plan, ['PLUS', 'PRO'] as const)), validUntil: instant(discount['validUntil']) };
}

function parseCurrent(value: unknown): PlansCurrent {
    const current = exact(value, ['plan', 'period', 'validUntil', 'autoRenew', 'source']);
    return {
        plan: oneOf(current['plan'], PLAN_IDS), period: oneOf(current['period'], ['MONTH', 'YEAR']),
        validUntil: instant(current['validUntil']), autoRenew: bool(current['autoRenew']),
        source: oneOf(current['source'], ['CONFIG', 'BILLING', 'PROMO'])
    };
}

function parseEntry(value: unknown): PlanEntry {
    const entry = exact(value, ENTRY_KEYS);
    const price = exact(entry['priceRub'], ['month', 'year']);
    const highlights = entry['highlights'];
    if (!Array.isArray(highlights) || highlights.length === 0 || highlights.length > 5
        || highlights.some(line => typeof line !== 'string' || line.length === 0 || line.length > 200)) throw protocol('Invalid highlights.');
    const goals = entry['recommendedFor'];
    if (!Array.isArray(goals)) throw protocol('Invalid recommendations.');
    return {
        plan: oneOf(entry['plan'], PLAN_IDS), availability: oneOf(entry['availability'], ['AVAILABLE', 'TEASER']),
        priceRub: { month: integer(price['month'], 1_000_000), year: integer(price['year'], 12_000_000) },
        perDayRub: integer(entry['perDayRub'], 100_000), yearDiscountPercent: integer(entry['yearDiscountPercent'], 100),
        highlights: highlights as string[], allowances: parseAllowances(entry['allowances']),
        recommendedFor: goals.map(goal => oneOf(goal, LEARNING_GOALS))
    };
}

function parseAllowances(value: unknown): PlanAllowances {
    const table = exact(value, ALLOWANCE_KEYS);
    const range = exact(table['materialsPerMonth'], ['min', 'max']);
    const smart = exact(table['smartPlans'], ['limit', 'window']);
    const min = integer(range['min']);
    const max = integer(range['max']);
    if (max < min) throw protocol('Invalid range.');
    return {
        materialsPerMonth: { min, max },
        voiceMinutesPerMonth: table['voiceMinutesPerMonth'] === null ? null : integer(table['voiceMinutesPerMonth']),
        voiceMinutesPerDay: integer(table['voiceMinutesPerDay']), answerChecksPerMonth: integer(table['answerChecksPerMonth']),
        answerChecksPerDay: integer(table['answerChecksPerDay']), podcastsPerMonth: integer(table['podcastsPerMonth']),
        qualityImagesPerMonth: integer(table['qualityImagesPerMonth']), factChecksPerMonth: integer(table['factChecksPerMonth']),
        smartPlans: { limit: integer(smart['limit']), window: oneOf(smart['window'], ['WEEK', 'MONTH']) }
    };
}
