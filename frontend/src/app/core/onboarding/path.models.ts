/**
 * The closed registry of «Путь Mnema» milestones (`contracts/onboarding/milestones.json`, OB-E3): keys in display order, their chapter,
 * target, the condition that makes one visible and the client route key. `onboarding-contract.spec.ts` executes the fixture against
 * these lists, so a change in the contract fails the frontend spec first. The titles, benefits and event phrases are texts
 * (`onboarding-texts.ts`, keyed by the milestone key); nothing here is prose.
 */

export const MILESTONE_CHAPTERS = ['start', 'materials', 'exercises', 'rhythm'] as const;
export type MilestoneChapter = typeof MILESTONE_CHAPTERS[number];

export const MILESTONE_STATES = ['DONE', 'PARTIAL', 'TODO'] as const;
export type MilestoneState = typeof MILESTONE_STATES[number];

/** What makes a milestone visible; only `ALWAYS` is decided by the client (the server hides the rest and names them in `path.hidden`). */
export const MILESTONE_VISIBILITIES = ['ALWAYS', 'AI_ASSESSMENT', 'SPEECH_TO_TEXT', 'AI_GENERATION', 'COMMUNITY_CATALOG', 'RHYTHM'] as const;
export type MilestoneVisibility = typeof MILESTONE_VISIBILITIES[number];

/** Where a milestone leads: resolved to a URL and an anchor on the client (`path-places.ts`); the server never sends a URL. */
export const MILESTONE_ROUTES = ['PROFILE_GOAL', 'DECKS', 'STUDY', 'MATERIAL_NEW', 'QUEST_MEDIA', 'CAPTURE', 'EXERCISE_NEW', 'QUEST_EXPLAIN', 'QUEST_VOICE',
    'QUEST_WORKSHOP', 'PROFILE_RHYTHM', 'COMMUNITY'] as const;
export type MilestoneRoute = typeof MILESTONE_ROUTES[number];

export interface MilestoneDefinition {
    readonly key: string;
    readonly chapter: MilestoneChapter;
    readonly target: number;
    readonly visibility: MilestoneVisibility;
    readonly route: MilestoneRoute;
}

/** The 13 milestones in display order. */
export const MILESTONE_REGISTRY = [
    { key: 'GOAL_TOLD', chapter: 'start', target: 1, visibility: 'ALWAYS', route: 'PROFILE_GOAL' },
    { key: 'FIRST_DECK', chapter: 'start', target: 1, visibility: 'ALWAYS', route: 'DECKS' },
    { key: 'FIRST_SESSION', chapter: 'start', target: 1, visibility: 'ALWAYS', route: 'STUDY' },
    { key: 'OWN_MATERIAL', chapter: 'materials', target: 1, visibility: 'ALWAYS', route: 'MATERIAL_NEW' },
    { key: 'MEDIA_IN_MATERIAL', chapter: 'materials', target: 1, visibility: 'ALWAYS', route: 'QUEST_MEDIA' },
    { key: 'CAPTURE_NOTE', chapter: 'materials', target: 2, visibility: 'ALWAYS', route: 'CAPTURE' },
    { key: 'OWN_EXERCISES', chapter: 'exercises', target: 3, visibility: 'ALWAYS', route: 'EXERCISE_NEW' },
    { key: 'AI_EXPLANATION', chapter: 'exercises', target: 1, visibility: 'AI_ASSESSMENT', route: 'QUEST_EXPLAIN' },
    { key: 'VOICE_ANSWER', chapter: 'exercises', target: 1, visibility: 'SPEECH_TO_TEXT', route: 'QUEST_VOICE' },
    { key: 'WORKSHOP_APPROVED', chapter: 'exercises', target: 1, visibility: 'AI_GENERATION', route: 'QUEST_WORKSHOP' },
    { key: 'RHYTHM_WEEK', chapter: 'rhythm', target: 1, visibility: 'RHYTHM', route: 'PROFILE_RHYTHM' },
    { key: 'TWO_DAYS', chapter: 'rhythm', target: 2, visibility: 'ALWAYS', route: 'STUDY' },
    { key: 'COMMUNITY_COPY', chapter: 'rhythm', target: 1, visibility: 'COMMUNITY_CATALOG', route: 'COMMUNITY' }
] as const satisfies readonly MilestoneDefinition[];

export type MilestoneKey = typeof MILESTONE_REGISTRY[number]['key'];
export const MILESTONE_KEYS = MILESTONE_REGISTRY.map(milestone => milestone.key) as readonly MilestoneKey[];

const DEFINITIONS: ReadonlyMap<string, MilestoneDefinition> = new Map(MILESTONE_REGISTRY.map(milestone => [milestone.key, milestone]));

export function isMilestoneKey(value: unknown): value is MilestoneKey {
    return typeof value === 'string' && DEFINITIONS.has(value);
}

export function milestoneDefinition(key: MilestoneKey): MilestoneDefinition {
    return DEFINITIONS.get(key) as MilestoneDefinition;
}

/** One visible milestone as the server sends it (`milestones` of the onboarding view). */
export interface Milestone {
    readonly key: MilestoneKey;
    readonly state: MilestoneState;
    readonly progress: number;
    readonly target: number;
    readonly achievedAt: string | null;
}

/** `path` of the view: the visible milestones done and in all, and the keys a switched-off feature hides. */
export interface PathSummary {
    readonly done: number;
    readonly total: number;
    readonly hidden: readonly MilestoneKey[];
}

/** The chapters that have visible milestones, in registry order; `current` is the first one with a milestone still to do. */
export interface PathChapter {
    readonly chapter: MilestoneChapter;
    readonly milestones: readonly Milestone[];
    readonly done: number;
    readonly current: boolean;
}

export function pathChapters(milestones: readonly Milestone[]): readonly PathChapter[] {
    const chapters = MILESTONE_CHAPTERS.map(chapter => {
        const own = milestones.filter(milestone => milestoneDefinition(milestone.key).chapter === chapter);
        return { chapter, milestones: own, done: own.filter(milestone => milestone.state === 'DONE').length, current: false };
    }).filter(chapter => chapter.milestones.length > 0);
    const current = chapters.findIndex(chapter => chapter.done < chapter.milestones.length);
    return chapters.map((chapter, index) => ({ ...chapter, current: index === current }));
}
