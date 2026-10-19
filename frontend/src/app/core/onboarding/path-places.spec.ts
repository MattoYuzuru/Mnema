import { OnboardingTextKey, onboardingText } from './onboarding-texts';
import { PATH_PLACES, PATH_STATIC_HREFS, QUEST_ROUTES, RecentDecks, isQuestRoute } from './path-places';
import { MILESTONE_REGISTRY, MILESTONE_ROUTES } from './path.models';
import { TOUR_ANCHOR_KEYS } from './tour-anchor-keys';

const DECK = '11111111-1111-4111-8111-111111111111';
const OWN = '22222222-2222-4222-8222-222222222222';
const NONE: RecentDecks = { user: null, any: null };
const BOTH: RecentDecks = { user: OWN, any: DECK };

describe('the places of «Путь»', () => {
    const resolve = (route: typeof MILESTONE_ROUTES[number], decks: RecentDecks) => PATH_PLACES[route](decks);

    it('has a resolver for every route key a milestone of the registry uses', () => {
        for (const milestone of MILESTONE_REGISTRY) {
            expect(Object.keys(PATH_PLACES), milestone.key).toContain(milestone.route);
            expect(PATH_PLACES[milestone.route], milestone.key).toBeTypeOf('function');
        }
    });

    it('points every place at a registered anchor with a text that exists', () => {
        for (const route of MILESTONE_ROUTES) {
            for (const decks of [NONE, BOTH]) {
                const place = resolve(route, decks);
                if (place === null) continue;
                expect(TOUR_ANCHOR_KEYS, `${route} anchor`).toContain(place.anchor);
                expect(onboardingText(place.text as OnboardingTextKey).length, `${route} text`).toBeGreaterThan(5);
                expect(place.commands[0], route).toMatch(/^\//u);
            }
        }
    });

    it('takes the goal to the profile, the first deck to the list, the community to its catalogue', () => {
        expect(resolve('PROFILE_GOAL', NONE)).toEqual({ commands: ['/profile'], anchor: 'profile.goal', text: 'hint.PROFILE_GOAL' });
        expect(resolve('DECKS', BOTH)).toEqual({ commands: ['/decks'], anchor: 'nav.newDeck', text: 'hint.DECKS' });
        expect(resolve('COMMUNITY', NONE)).toEqual({ commands: ['/community'], anchor: 'community.search', text: 'hint.COMMUNITY' });
    });

    it('takes the study to the hub of the newest deck of any kind, and the deck list when there is none', () => {
        expect(resolve('STUDY', BOTH)).toEqual({ commands: ['/decks', DECK], anchor: 'hub.start', text: 'hint.STUDY' });
        expect(resolve('STUDY', { user: null, any: DECK })).toEqual({ commands: ['/decks', DECK], anchor: 'hub.start', text: 'hint.STUDY' });
        expect(resolve('STUDY', NONE)).toEqual({ commands: ['/decks'], anchor: 'nav.newDeck', text: 'hint.DECKS' });
    });

    it('takes the authoring controls to the hub of the newest own deck, and the deck list with the deck hint when there is none', () => {
        expect(resolve('MATERIAL_NEW', BOTH)).toEqual({ commands: ['/decks', OWN], anchor: 'hub.addMaterial', text: 'hint.MATERIAL_NEW' });
        expect(resolve('EXERCISE_NEW', BOTH)).toEqual({ commands: ['/decks', OWN], anchor: 'hub.materials', text: 'hint.EXERCISE_NEW' });
        expect(resolve('CAPTURE', BOTH)).toEqual({ commands: ['/decks', OWN], anchor: 'hub.capture', text: 'hint.capture' });
        // Only the sample copy: it is not the learner's own work.
        for (const route of ['MATERIAL_NEW', 'EXERCISE_NEW', 'CAPTURE'] as const) {
            expect(resolve(route, { user: null, any: DECK }), route).toEqual({ commands: ['/decks'], anchor: 'nav.newDeck', text: 'hint.DECKS' });
        }
    });

    it('takes the rhythm milestone to the rhythm block of the profile, with the hint of the place', () => {
        expect(resolve('PROFILE_RHYTHM', BOTH)).toEqual({ commands: ['/profile'], anchor: 'profile.rhythm', text: 'hint.PROFILE_RHYTHM' });
        expect(resolve('PROFILE_RHYTHM', NONE)).toEqual(resolve('PROFILE_RHYTHM', BOTH));
        expect(PATH_STATIC_HREFS.PROFILE_RHYTHM).toBe('/profile');
    });

    it('has no plain place for a side quest: each is a tour of its own, opened from the chooser', () => {
        expect(QUEST_ROUTES).toEqual(['QUEST_MEDIA', 'QUEST_EXPLAIN', 'QUEST_VOICE', 'QUEST_WORKSHOP']);
        expect(MILESTONE_ROUTES.filter(isQuestRoute)).toEqual([...QUEST_ROUTES]);
        for (const route of QUEST_ROUTES) expect(resolve(route, BOTH), route).toBeNull();
        expect(isQuestRoute('STUDY')).toBe(false);
    });

    it('gives each chevron a plain address that works without scripts', () => {
        for (const route of MILESTONE_ROUTES) expect(route in PATH_STATIC_HREFS, route).toBe(true);
        expect(PATH_STATIC_HREFS.PROFILE_GOAL).toBe('/profile');
        expect(PATH_STATIC_HREFS.COMMUNITY).toBe('/community');
        expect(PATH_STATIC_HREFS.STUDY).toBe('/decks');
    });
});
