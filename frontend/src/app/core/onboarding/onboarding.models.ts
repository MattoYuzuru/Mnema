import { Milestone, PathSummary } from './path.models';

/**
 * Wire types of the first-entry journey (`contracts/onboarding/journey.json`, `GET /api/onboarding`,
 * `PUT /api/onboarding/journey`, `PUT /api/onboarding/preferences`). The constants below are the closed lists of the contract;
 * `onboarding-contract.spec.ts` executes the fixture against them, so a change in the contract fails the frontend spec first.
 */

export const JOURNEY_STATES = ['NOT_OFFERED', 'OFFERED', 'IN_PROGRESS', 'PAUSED', 'SKIPPED', 'COMPLETED'] as const;
export type JourneyState = typeof JOURNEY_STATES[number];

export const JOURNEY_ACTIONS = ['START', 'STEP', 'PAUSE', 'RESUME', 'SKIP', 'COMPLETE', 'UNDO_FINISH', 'REPLAY'] as const;
export type JourneyAction = typeof JOURNEY_ACTIONS[number];

export const BRANCHES = ['SAMPLE', 'COMMUNITY', 'OWN'] as const;
export type Branch = typeof BRANCHES[number];

export const EXPERIENCES = ['NEW', 'FAMILIAR', 'CONFIDENT'] as const;
export type Experience = typeof EXPERIENCES[number];

/** The weekly target in days: the range, the options of the survey, and what the rhythm uses while the learner has not chosen (`constants.weeklyTarget`). */
export const WEEKLY_TARGET = { min: 1, max: 7, default: 3, surveyOptions: [2, 3, 5, 7] } as const;

/** The closed registry of step keys and the branch each belongs to (`null`: shared by every branch). */
export const STEP_BRANCH = {
    'welcome': null,
    'survey.goal': null,
    'survey.interests': null,
    'survey.experience': null,
    'survey.days': null,
    'choose': null,
    'sample.hub': 'SAMPLE',
    'sample.material': 'SAMPLE',
    'sample.exercises': 'SAMPLE',
    'sample.study': 'SAMPLE',
    'community.search': 'COMMUNITY',
    'community.deck': 'COMMUNITY',
    'community.add': 'COMMUNITY',
    'community.study': 'COMMUNITY',
    'own.details': 'OWN',
    'own.material': 'OWN',
    'own.exercise': 'OWN',
    'own.study': 'OWN',
    'finale.ai': null,
    'finale.done': null
} as const satisfies Readonly<Record<string, Branch | null>>;
export type StepKey = keyof typeof STEP_BRANCH;
export const STEP_KEYS = Object.keys(STEP_BRANCH) as readonly StepKey[];

/** What each action may carry (`transitions` in the contract): `stepKey` required, optional or forbidden; `branch` accepted or not. */
export const STEP_KEY_RULE: Readonly<Record<JourneyAction, 'required' | 'optional' | 'forbidden'>> = {
    START: 'required', STEP: 'required', PAUSE: 'optional', RESUME: 'optional', SKIP: 'forbidden', COMPLETE: 'forbidden',
    UNDO_FINISH: 'forbidden', REPLAY: 'required'
};
export const BRANCH_ACCEPTED: Readonly<Record<JourneyAction, boolean>> = {
    START: false, STEP: true, PAUSE: false, RESUME: true, SKIP: false, COMPLETE: false, UNDO_FINISH: false, REPLAY: false
};

export interface Journey {
    readonly state: JourneyState;
    readonly eligible: boolean;
    readonly autoOffer: boolean;
    readonly tourVersion: number;
    readonly branch: Branch | null;
    readonly stepKey: StepKey | null;
    readonly experience: Experience | null;
    readonly weeklyTarget: number | null;
    readonly offeredAt: string | null;
    readonly finishedAt: string | null;
    readonly replayCount: number;
    /** A decimal string; `"0"` while there is no row. Sent back with every command. */
    readonly rowVersion: string;
}

/** The AI checks of an account's sample deck that cost it nothing (`constants.sampleDeck.freeAiChecks`): per account, not per copy. */
export const SAMPLE_FREE_AI_CHECKS = 3;

export interface SampleDeck {
    readonly available: boolean;
    readonly deckId: string | null;
    readonly updateAvailable: boolean;
    /** Free AI checks still left in the sample deck, `0..SAMPLE_FREE_AI_CHECKS`. */
    readonly freeChecksLeft: number;
}

/**
 * The weekly rhythm of the account (`rhythm` of the view, OB-401, `contracts/onboarding/journey.json` `views.rhythmWeek`). `weekStart` is
 * the Monday of the ISO week of `today`, the learner's local date; `days` are the distinct local dates of the week with a completed
 * SCHEDULED or PRACTICE session, ascending; `target` is 1–7. The week is met when `days.length` reaches `target` (the client derives it, there
 * is no flag). `weeksMet` is shown in the profile only.
 */
export interface Rhythm {
    readonly weekStart: string;
    readonly today: string;
    readonly target: number;
    readonly days: readonly string[];
    readonly weeksMet: number;
}

export const REWARD_STATES = ['ACTIVE', 'REDEEMED', 'EXPIRED'] as const;
export type RewardState = typeof REWARD_STATES[number];
export const REWARD_OFFER_TYPES = ['DISCOUNT_PERCENT', 'TIER_DAYS'] as const;
export type RewardOfferType = typeof REWARD_OFFER_TYPES[number];
export const REWARD_PLANS = ['PLUS', 'PRO'] as const;
export type RewardPlan = typeof REWARD_PLANS[number];
/** The bounds of the offer a campaign can make (`contracts/usage/promo.json` `createPromoCampaign`): a discount of 1–90 %, a tier for 1–366 days. */
export const REWARD_BOUNDS = { percent: { min: 1, max: 90 }, days: { min: 1, max: 366 } } as const;

/** What the reward gives: a percent off (optionally for one plan) or a plan for some days. The members that do not belong to the type are `null`. */
export type RewardOffer =
    | { readonly type: 'DISCOUNT_PERCENT'; readonly percent: number; readonly plan: RewardPlan | null; readonly days: null }
    | { readonly type: 'TIER_DAYS'; readonly percent: null; readonly plan: RewardPlan; readonly days: number };

/**
 * The reward of a finished «Путь» (OB-402, `reward` of the view, `contracts/onboarding/journey.json`): a personal promo code the server issued
 * once. `code` (display form, with dashes) is present only while `ACTIVE`; `expiresAt` is the end of its validity on the server, shown as a date
 * and a time, never as a countdown.
 */
export interface PathReward {
    readonly state: RewardState;
    readonly code: string | null;
    readonly offer: RewardOffer;
    readonly issuedAt: string;
    readonly expiresAt: string;
}

/**
 * The view of the account's onboarding. While `enabled` is false `journey` is `null`, `milestones` is `[]` and `path` is `null`, and the
 * client shows neither «Начало пути», the tour nor «Путь». `milestones` lists the visible milestones in registry order and `path` their
 * count (`contracts/onboarding/milestones.json`). `rhythm` is the weekly rhythm, `null` while `learning.onboarding.rhythm.enabled` is off. `reward` is the
 * reward of the finished path, `null` while `learning.onboarding.reward.enabled` is off or the account has none.
 */
export interface OnboardingView {
    readonly enabled: boolean;
    readonly journey: Journey | null;
    readonly milestones: readonly Milestone[];
    readonly path: PathSummary | null;
    readonly rhythm: Rhythm | null;
    readonly reward: PathReward | null;
    readonly sampleDeck: SampleDeck;
}

/** `PUT /api/onboarding/journey`. */
export interface JourneyCommand {
    readonly commandId: string;
    readonly rowVersion: string;
    readonly action: JourneyAction;
    readonly branch?: Branch;
    readonly stepKey?: StepKey;
}

/** `PUT /api/onboarding/preferences`: at least one field; `null` clears it. */
export interface PreferencesCommand {
    readonly commandId: string;
    readonly rowVersion: string;
    readonly experience?: Experience | null;
    readonly weeklyTarget?: number | null;
}

/** `POST /api/onboarding/sample-deck`: the one live sample copy of the account (made, or the existing one). Needs no row version. */
export interface SampleDeckCommand {
    readonly commandId: string;
}

/** What a caller adds to an action; the store supplies the command id and the row version. */
export interface ActionOptions {
    readonly stepKey?: StepKey;
    readonly branch?: Branch;
}

export interface PreferenceChanges {
    readonly experience?: Experience | null;
    readonly weeklyTarget?: number | null;
}

/** The view with the validator it came with. */
export interface OnboardingRead {
    readonly view: OnboardingView;
    readonly etag: string | null;
}

export type OnboardingReadResult = ({ readonly kind: 'view' } & OnboardingRead) | { readonly kind: 'not-modified' };

/**
 * What became of a command. `applied`: the server did it, `view` is the state after it. `refused`: the server answered 409, 412 or 428
 * (a transition the table does not allow, a stale version, the feature off) and `view` is the state it holds now, so the caller
 * follows the server and does not act as if it had worked. `failed`: no answer that could be used (network, a defect, no journey).
 */
export type JourneyResult =
    | { readonly status: 'applied'; readonly view: OnboardingView }
    | { readonly status: 'refused'; readonly view: OnboardingView }
    | { readonly status: 'failed' };

export function isApplied(result: unknown): result is Extract<JourneyResult, { status: 'applied' }> {
    return typeof result === 'object' && result !== null && (result as { status?: unknown }).status === 'applied';
}

export function isRefused(result: unknown): result is Extract<JourneyResult, { status: 'refused' }> {
    return typeof result === 'object' && result !== null && (result as { status?: unknown }).status === 'refused';
}

export class OnboardingProtocolError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'OnboardingProtocolError';
    }
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;

export function isCommandId(value: unknown): value is string {
    return typeof value === 'string' && UUID.test(value);
}

/** A fresh id for one logical command; a retry of the same command sends the same id. */
export function newCommandId(): string {
    const commandId = crypto.randomUUID();
    if (!isCommandId(commandId)) throw new OnboardingProtocolError('Browser did not produce a command ID.');
    return commandId;
}
