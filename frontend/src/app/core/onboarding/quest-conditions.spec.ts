import { CAPABILITIES_UNAVAILABLE, LearningCapabilities } from '../../features/authoring/capabilities-api.service';
import { plusUsage, freeUsage } from '../../features/usage/usage-test-data';
import { UsageSnapshot } from '../../features/usage/usage.models';
import { QUEST_ROUTES } from './path-places';
import { QUEST_CAPABILITY, SAMPLE_QUESTS, budgetExhausted, capabilityOn, capabilityReason, needsOwnDeck, spendableCredits } from './quest-conditions';

const on: LearningCapabilities = {
    ...CAPABILITIES_UNAVAILABLE, aiAssessment: { available: true, reason: null }, speechToText: { available: true, reason: null }, aiGeneration: { available: true, reason: null }
};
const plus = (): UsageSnapshot => plusUsage() as unknown as UsageSnapshot;
const free = (): UsageSnapshot => freeUsage() as unknown as UsageSnapshot;
const spent = (usage: UsageSnapshot, change: Partial<UsageSnapshot['credits']>): UsageSnapshot => ({ ...usage, credits: { ...usage.credits, ...change } });
const bucket = (usage: UsageSnapshot, name: 'stt' | 'assessment', change: object): UsageSnapshot =>
    ({ ...usage, fairUse: { ...usage.fairUse, [name]: { ...usage.fairUse[name], ...change } } });

describe('conditions of the side quests', () => {
    it('needs the capability of its feature and none for the picture; unknown or off is off (fail closed)', () => {
        expect(QUEST_CAPABILITY).toEqual({ QUEST_WORKSHOP: 'aiGeneration', QUEST_VOICE: 'speechToText', QUEST_EXPLAIN: 'aiAssessment', QUEST_MEDIA: null });
        for (const route of QUEST_ROUTES) expect(capabilityOn(route, on), route).toBe(true);
        expect(capabilityOn('QUEST_MEDIA', null)).toBe(true);
        for (const route of ['QUEST_WORKSHOP', 'QUEST_VOICE', 'QUEST_EXPLAIN'] as const) {
            expect(capabilityOn(route, null), route).toBe(false);
            expect(capabilityOn(route, CAPABILITIES_UNAVAILABLE), route).toBe(false);
        }
        expect(capabilityReason('QUEST_VOICE', { ...on, speechToText: { available: false, reason: 'TEMPORARILY_UNAVAILABLE' } })).toBe('TEMPORARILY_UNAVAILABLE');
        expect(capabilityReason('QUEST_VOICE', on)).toBeNull();
        expect(capabilityReason('QUEST_MEDIA', CAPABILITIES_UNAVAILABLE)).toBeNull();
    });

    it('lets only the explanation and the voice demonstrate on the sample deck; the others write into a deck of the learner\'s own', () => {
        expect(SAMPLE_QUESTS).toEqual(['QUEST_EXPLAIN', 'QUEST_VOICE']);
        expect(QUEST_ROUTES.filter(needsOwnDeck)).toEqual(['QUEST_MEDIA', 'QUEST_WORKSHOP']);
    });

    it('counts the credits that may be spent now: the open part on Free, less what is used and reserved', () => {
        expect(spendableCredits(free())).toBe(5);
        expect(spendableCredits(spent(free(), { used: 13 }))).toBe(0);
        expect(spendableCredits(spent(free(), { reserved: 2 }))).toBe(3);
    });

    it('is not exhausted while there is budget, and never for the picture', () => {
        for (const route of QUEST_ROUTES) expect(budgetExhausted(route, plus()), route).toBe(false);
        expect(budgetExhausted('QUEST_WORKSHOP', null)).toBe(false);
        expect(budgetExhausted('QUEST_MEDIA', spent(plus(), { unlocked: 0 }))).toBe(false);
    });

    it('is exhausted for the Workshop and the explanation when no credit may be spent', () => {
        const empty = spent(plus(), { used: plus().credits.unlocked });
        expect(budgetExhausted('QUEST_WORKSHOP', empty)).toBe(true);
        expect(budgetExhausted('QUEST_EXPLAIN', empty)).toBe(true);
        // The voice has its own counter: credits do not stop it.
        expect(budgetExhausted('QUEST_VOICE', empty)).toBe(false);
    });

    it('is exhausted for the explanation when the monthly or the daily checks are used up', () => {
        expect(budgetExhausted('QUEST_EXPLAIN', bucket(plus(), 'assessment', { used: 50, limit: 50 }))).toBe(true);
        expect(budgetExhausted('QUEST_EXPLAIN', bucket(plus(), 'assessment', { usedToday: 5, limitToday: 5 }))).toBe(true);
        expect(budgetExhausted('QUEST_EXPLAIN', bucket(plus(), 'assessment', { used: 49, limit: 50, usedToday: 4, limitToday: 5 }))).toBe(false);
    });

    it('is exhausted for the voice when the speech minutes are used up, and not when the plan has no limit', () => {
        expect(budgetExhausted('QUEST_VOICE', bucket(plus(), 'stt', { used: 60, limit: 60 }))).toBe(true);
        expect(budgetExhausted('QUEST_VOICE', bucket(plus(), 'stt', { usedToday: 10, limitToday: 10 }))).toBe(true);
        expect(budgetExhausted('QUEST_VOICE', bucket(plus(), 'stt', { used: 999, limit: null, usedToday: 999, limitToday: null }))).toBe(false);
    });
});
