import { CapabilityReason, LearningCapabilities } from '../../features/authoring/capabilities-api.service';
import { UsageFairUseBucket, UsageSnapshot } from '../../features/usage/usage.models';
import { QuestRoute } from './path-places';

/** The capability a quest needs; `null` when it needs none (a picture or a sound is no AI). */
export const QUEST_CAPABILITY: Readonly<Record<QuestRoute, keyof LearningCapabilities | null>> = {
    QUEST_WORKSHOP: 'aiGeneration', QUEST_VOICE: 'speechToText', QUEST_EXPLAIN: 'aiAssessment', QUEST_MEDIA: null
};

/**
 * The quests that may run in the sample deck as a demonstration, on the free checks of the account: only the explanation (an AI check
 * of an answer). The voice goes through the microphone of a note field, which the free checks do not cover.
 */
export const SAMPLE_QUESTS: readonly QuestRoute[] = ['QUEST_EXPLAIN'];

/** The quests that need a deck of the learner's own work (they write into it). */
export function needsOwnDeck(route: QuestRoute): boolean {
    return !SAMPLE_QUESTS.includes(route);
}

/** Whether the server has the capability on. A capability the answer does not say is off: fail closed. */
export function capabilityOn(route: QuestRoute, capabilities: LearningCapabilities | null): boolean {
    const needed = QUEST_CAPABILITY[route];
    return needed === null || (capabilities !== null && capabilities[needed].available);
}

export function capabilityReason(route: QuestRoute, capabilities: LearningCapabilities | null): CapabilityReason | null {
    const needed = QUEST_CAPABILITY[route];
    const capability = needed === null || capabilities === null ? null : capabilities[needed];
    return capability === null || capability.available ? null : capability.reason;
}

function bucketFull(bucket: UsageFairUseBucket): boolean {
    return (bucket.limit !== null && bucket.used >= bucket.limit) || (bucket.limitToday !== null && bucket.usedToday >= bucket.limitToday);
}

/** What may be spent now (`unlocked`: the whole bar on paid plans, the portions open so far on Free), less what is used and reserved. */
export function spendableCredits(usage: UsageSnapshot): number {
    return usage.credits.unlocked - usage.credits.used - usage.credits.reserved;
}

/**
 * Whether the account's own AI budget is gone for what the quest does: the credits for the Workshop, the credits and the daily or monthly
 * checks for the explanation, the speech counter for the voice; the picture needs none. Said before the first step, not by a refusal in the
 * middle of a quest.
 */
export function budgetExhausted(route: QuestRoute, usage: UsageSnapshot | null): boolean {
    if (usage === null) return false;
    switch (route) {
        case 'QUEST_WORKSHOP': return spendableCredits(usage) <= 0;
        case 'QUEST_EXPLAIN': return spendableCredits(usage) <= 0 || bucketFull(usage.fairUse.assessment);
        case 'QUEST_VOICE': return bucketFull(usage.fairUse.stt);
        case 'QUEST_MEDIA': return false;
    }
}
