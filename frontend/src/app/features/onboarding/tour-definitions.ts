import { Experience, StepKey } from '../../core/onboarding/onboarding.models';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { TOUR_ANCHORS } from '../../core/onboarding/tour-anchor-keys';
import { paragraphs } from '../../core/onboarding/tour-rich-text';
import { TourDefinition, TourStep } from '../../core/onboarding/tour.models';
import { CREATE_DECK_LABEL } from '../own-decks/own-deck.models';

/** The version of the tours below; the server stores the version a journey ran (`tourVersion`). */
export const TOUR_VERSION = 1;

/** The events the pages publish through `TourEvents` for the gates of the tours. */
export const TOUR_EVENTS = {
    deckOpened: 'community.deck-opened',
    deckCopied: 'deck.copied',
    deckCreated: 'deck.created',
    materialPublished: 'material.published',
    exercisePublished: 'exercise.published',
    studyStarted: 'study.started',
    studyCompleted: 'study.completed'
} as const;

/** What a definition needs to know about the learner and the run. Read when a step is entered, not when the tour is built. */
export interface TourContext {
    readonly experience: Experience | null;
    /** The AI composer and the AI finale exist in this environment (the capabilities answer). */
    readonly aiAvailable: boolean;
    /** The deck the tour works with: the one just made, the one just copied, or the one chosen for a replay. */
    readonly deckId: () => string | null;
    /** The material just published, for the exercise page. */
    readonly memberKey: () => string | null;
}

/**
 * TODO(OB-203): the tour of the sample deck («На примере»). Its owner is OB-203: the sample deck does not exist until OB-E2, and the
 * branch choice hides «Посмотреть на примере» while `sampleDeck.available` is false, so nothing starts this tour yet.
 */
export const SAMPLE_TOUR: TourDefinition | null = null;

const FINALE: readonly TourStep[] = [
    { key: 'finale.ai', anchor: '', title: '', body: { kind: 'paragraphs', paragraphs: [] }, gate: 'next', external: true },
    { key: 'finale.done', anchor: '', title: '', body: { kind: 'paragraphs', paragraphs: [] }, gate: 'next', external: true }
];

/** The gate line quotes the hub's real button («Учить», own-deck-detail-page), not the approved `sample.4.action` wording, so the learner finds it. */
export const HUB_STUDY_LABEL = 'Учить';
const STUDY_GATE = { action: TOUR_EVENTS.studyStarted, label: HUB_STUDY_LABEL, pauses: true } as const;

const hub = (context: TourContext): string | null => {
    const deckId = context.deckId();
    return deckId === null ? null : `/decks/${deckId}`;
};

function communitySteps(context: TourContext): readonly TourStep[] {
    return [
        {
            key: 'community.search', anchor: TOUR_ANCHORS.communitySearch, route: '/community', essential: true,
            title: onboardingText('community.1.title'), body: paragraphs(onboardingText('community.1.body')),
            // Opening a deck is the learner's own choice of a deck: there is no control to name.
            gate: { action: TOUR_EVENTS.deckOpened, label: null }
        },
        {
            key: 'community.deck', anchor: TOUR_ANCHORS.communityDeck,
            title: onboardingText('community.2.title'), body: paragraphs(onboardingText('community.2.body')), gate: 'next'
        },
        {
            key: 'community.add', anchor: TOUR_ANCHORS.communityAdd, essential: true,
            title: onboardingText('community.3.title'), body: paragraphs(onboardingText('community.3.body')),
            gate: { action: TOUR_EVENTS.deckCopied, label: onboardingText('community.3.action') }
        },
        {
            key: 'community.study', anchor: TOUR_ANCHORS.hubStart, route: () => hub(context), essential: true, placement: 'bottom',
            title: onboardingText('community.4.title'), body: paragraphs(onboardingText('community.4.body')), gate: STUDY_GATE
        }
    ];
}

function ownSteps(context: TourContext): readonly TourStep[] {
    return [
        {
            key: 'own.details', anchor: TOUR_ANCHORS.ownDetails, route: '/decks/new', essential: true,
            title: onboardingText('own.1.title'), body: paragraphs(onboardingText('own.1.body')),
            // The label is the real submit button of the form.
            gate: { action: TOUR_EVENTS.deckCreated, label: CREATE_DECK_LABEL }
        },
        {
            key: 'own.material', anchor: TOUR_ANCHORS.ownMaterial, essential: true,
            // The composer where AI composing is available, the plain editor otherwise.
            route: () => { const id = context.deckId(); return id === null ? null : `/decks/${id}/materials/new${context.aiAvailable ? '' : '?write=1'}`; },
            title: onboardingText('own.2.title'), body: paragraphs(onboardingText(context.aiAvailable ? 'own.2.body' : 'own.2.body.noai')),
            gate: { action: TOUR_EVENTS.materialPublished, label: null }
        },
        {
            key: 'own.exercise', anchor: TOUR_ANCHORS.ownExercise, essential: true,
            route: () => {
                const id = context.deckId();
                const member = context.memberKey();
                return id === null ? null : member === null ? `/decks/${id}` : `/decks/${id}/materials/${member}/exercises/new`;
            },
            title: onboardingText('own.3.title'), body: paragraphs(onboardingText('own.3.body')),
            gate: { action: TOUR_EVENTS.exercisePublished, label: null }
        },
        {
            key: 'own.study', anchor: TOUR_ANCHORS.hubStart, route: () => hub(context), essential: true, placement: 'bottom',
            title: onboardingText('own.4.title'), body: paragraphs(onboardingText('own.4.body')), gate: STUDY_GATE
        }
    ];
}

/**
 * The tour of a branch, version 1, or `null` where there is none (the sample deck: OB-203). A learner who has used flashcards before
 * (`FAMILIAR`, `CONFIDENT`) gets the essential steps only. After the study step come the finale screens, drawn by the onboarding host:
 * «ИИ в Mnema» only where AI exists, then the congratulation.
 */
export function tourFor(branch: 'COMMUNITY' | 'OWN' | 'SAMPLE', context: TourContext): TourDefinition | null {
    if (branch === 'SAMPLE') return SAMPLE_TOUR;
    const all = branch === 'COMMUNITY' ? communitySteps(context) : ownSteps(context);
    const brief = context.experience === 'FAMILIAR' || context.experience === 'CONFIDENT';
    const steps = brief ? all.filter(step => step.essential === true) : all;
    return { id: `${branch.toLowerCase()}-v${TOUR_VERSION}`, branch, steps: [...steps, ...FINALE.filter(step => step.key !== 'finale.ai' || context.aiAvailable)] };
}

/** The step the tour continues at when the study session is over. */
export function finaleKey(aiAvailable: boolean): StepKey {
    return aiAvailable ? 'finale.ai' : 'finale.done';
}
