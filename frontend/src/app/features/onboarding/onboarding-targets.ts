import { Branch, STEP_BRANCH, StepKey } from '../../core/onboarding/onboarding.models';

/** Where «Путь» lives: the page of the path (OB-E3); the skip toast and the congratulation link to it. */
export const PATH_TARGET = '/profile/path';

/** The cards of the first entry, in the order they come. Their keys are step keys of the contract. */
export const FIRST_ENTRY_KEYS = ['welcome', 'survey.goal', 'survey.interests', 'survey.experience', 'survey.days', 'choose'] as const;
export type FirstEntryKey = typeof FIRST_ENTRY_KEYS[number];

export function isFirstEntryKey(key: StepKey | null): key is FirstEntryKey {
    return key !== null && (FIRST_ENTRY_KEYS as readonly string[]).includes(key);
}

/** The first step of the tour of each branch: where «Начать» on the branch choice records the first step. */
export const FIRST_STEP: Readonly<Record<Branch, StepKey>> = { SAMPLE: 'sample.hub', COMMUNITY: 'community.search', OWN: 'own.details' };

/** The study step of each branch: the tour pauses on it for the session. */
export const STUDY_STEP: Readonly<Record<Branch, StepKey>> = { SAMPLE: 'sample.study', COMMUNITY: 'community.study', OWN: 'own.study' };

export function branchOf(key: StepKey): Branch | null {
    return STEP_BRANCH[key];
}
