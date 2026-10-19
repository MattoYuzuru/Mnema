import { OnboardingTextKey } from './onboarding-texts';
import { MilestoneRoute } from './path.models';
import { TOUR_ANCHORS } from './tour-anchor-keys';

/** The decks of the account a milestone may lead into, newest first as the deck list answers: ids only. */
export interface RecentDecks {
    /** The newest deck that is the learner's own work: the deck list without the sample copy. */
    readonly user: string | null;
    /** The newest deck of any kind: the sample copy counts. */
    readonly any: string | null;
}

/**
 * Where a milestone's chevron takes the learner: a router address, the anchor key the in-place hint stands at, and the text of the hint
 * (a text key: the approved `hint.*` sentence of the place, or for a side quest the benefit line of the milestone until OB-304 gives it
 * a tour of its own).
 */
export interface PathPlace {
    readonly commands: readonly string[];
    readonly queryParams?: Readonly<Record<string, string>>;
    readonly anchor: string;
    readonly text: OnboardingTextKey;
}

type Resolver = (decks: RecentDecks) => PathPlace | null;

const DECK_LIST: PathPlace = { commands: ['/decks'], anchor: TOUR_ANCHORS.navNewDeck, text: 'hint.DECKS' };

/** A place in the deck hub of `deckId` (the `rest` is a path below `/decks/:deckId`); no deck sends the learner to the list with the deck hint. */
function inDeck(deckId: string | null, rest: readonly string[], anchor: string, text: OnboardingTextKey, queryParams?: Readonly<Record<string, string>>): PathPlace {
    return deckId === null ? DECK_LIST : { commands: ['/decks', deckId, ...rest], anchor, text, ...(queryParams === undefined ? {} : { queryParams }) };
}

/**
 * One resolver per route key of `contracts/onboarding/milestones.json` (a spec keeps the two lists equal): the address and the anchor of
 * the place, from the account's recent decks. `null`: the milestone has no plain place to point at (a side quest opens a tour of its own),
 * so no chevron is drawn.
 */
export const PATH_PLACES: Readonly<Record<MilestoneRoute, Resolver>> = {
    PROFILE_GOAL: () => ({ commands: ['/profile'], anchor: TOUR_ANCHORS.profileGoal, text: 'hint.PROFILE_GOAL' }),
    DECKS: () => DECK_LIST,
    STUDY: decks => inDeck(decks.any, [], TOUR_ANCHORS.hubStart, 'hint.STUDY'),
    MATERIAL_NEW: decks => inDeck(decks.user, [], TOUR_ANCHORS.hubAddMaterial, 'hint.MATERIAL_NEW'),
    EXERCISE_NEW: decks => inDeck(decks.user, [], TOUR_ANCHORS.hubMaterials, 'hint.EXERCISE_NEW'),
    CAPTURE: decks => inDeck(decks.user, [], TOUR_ANCHORS.hubCapture, 'hint.capture'),
    COMMUNITY: () => ({ commands: ['/community'], anchor: TOUR_ANCHORS.communitySearch, text: 'hint.COMMUNITY' }),
    PROFILE_RHYTHM: () => ({ commands: ['/profile'], anchor: TOUR_ANCHORS.profileRhythm, text: 'hint.PROFILE_RHYTHM' }),
    // Side quests (OB-304) are tours of their own: the chevron opens the chooser of `QuestLauncher`, it has no plain place.
    QUEST_MEDIA: () => null,
    QUEST_EXPLAIN: () => null,
    QUEST_VOICE: () => null,
    QUEST_WORKSHOP: () => null
};

/** The route keys that open a side quest instead of a plain place (`startQuest`, OB-304). */
export const QUEST_ROUTES = ['QUEST_MEDIA', 'QUEST_EXPLAIN', 'QUEST_VOICE', 'QUEST_WORKSHOP'] as const satisfies readonly MilestoneRoute[];
export type QuestRoute = typeof QUEST_ROUTES[number];

export function isQuestRoute(route: MilestoneRoute): route is QuestRoute {
    return (QUEST_ROUTES as readonly string[]).includes(route);
}

/**
 * The address a chevron links to before anything is looked up (a plain link works without scripts, and opens in a new tab): the place
 * itself when it does not depend on a deck, else the deck list. `null`: no chevron.
 */
export const PATH_STATIC_HREFS: Readonly<Record<MilestoneRoute, string | null>> = {
    PROFILE_GOAL: '/profile', DECKS: '/decks', STUDY: '/decks', MATERIAL_NEW: '/decks', QUEST_MEDIA: '/decks', CAPTURE: '/decks', EXERCISE_NEW: '/decks',
    QUEST_EXPLAIN: '/decks', QUEST_VOICE: '/decks', QUEST_WORKSHOP: '/decks', PROFILE_RHYTHM: '/profile', COMMUNITY: '/community'
};
