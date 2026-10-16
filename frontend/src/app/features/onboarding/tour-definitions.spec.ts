import contract from '../../../../../contracts/onboarding/journey.json';
import { STEP_BRANCH } from '../../core/onboarding/onboarding.models';
import { ONBOARDING_TEXTS } from '../../core/onboarding/onboarding-texts';
import { TOUR_ANCHOR_KEYS } from '../../core/onboarding/tour-anchor-keys';
import { tourDefinitionProblems } from '../../core/onboarding/tour-definition';
import { plainText } from '../../core/onboarding/tour-rich-text';
import { TourDefinition, TourStep } from '../../core/onboarding/tour.models';
import { HUB_STUDY_LABEL, SAMPLE_TOUR, TOUR_EVENTS, TOUR_VERSION, TourContext, finaleKey, tourFor } from './tour-definitions';

const context = (overrides: Partial<{ experience: TourContext['experience']; aiAvailable: boolean; deck: string | null; member: string | null }> = {}): TourContext => {
    const { experience = 'NEW', aiAvailable = true, deck = 'deck-1', member = 'member-1' } = overrides;
    return { experience, aiAvailable, deckId: () => deck, memberKey: () => member };
};
const keys = (definition: TourDefinition | null): string[] => definition?.steps.map(step => step.key) ?? [];
const branchSteps = (definition: TourDefinition | null): TourStep[] => definition?.steps.filter(step => step.external !== true) ?? [];
const route = (step: TourStep): string | null | undefined => typeof step.route === 'function' ? step.route() : step.route;

describe('tour definitions, version 1', () => {
    it('is version 1', () => {
        expect(TOUR_VERSION).toBe(1);
        expect(tourFor('COMMUNITY', context())?.id).toBe('community-v1');
        expect(tourFor('OWN', context())?.id).toBe('own-v1');
    });

    it('has no definition of the sample tour yet: that is OB-203, and nothing starts it', () => {
        expect(SAMPLE_TOUR).toBeNull();
        expect(tourFor('SAMPLE', context())).toBeNull();
    });

    describe.each([['COMMUNITY'], ['OWN']] as const)('the %s branch', branch => {
        for (const experience of ['NEW', 'FAMILIAR', 'CONFIDENT', null] as const) {
            for (const aiAvailable of [true, false]) {
                it(`is sound for ${experience ?? 'an unanswered experience'} with AI ${aiAvailable ? 'on' : 'off'}: registry keys, branch, anchors in the central list`, () => {
                    const definition = tourFor(branch, context({ experience, aiAvailable }))!;
                    expect(tourDefinitionProblems(definition)).toEqual([]);
                    const registry = new Map(contract.steps.map(step => [step.key, step.branch]));
                    for (const step of definition.steps) {
                        expect(registry.has(step.key), step.key).toBe(true);
                        expect([branch, null], step.key).toContain(registry.get(step.key));
                        expect(STEP_BRANCH[step.key]).toBe(registry.get(step.key));
                        if (step.external !== true) expect(TOUR_ANCHOR_KEYS, step.key).toContain(step.anchor);
                    }
                });
            }
        }

        it('keeps to four steps and ends with its study step before the finale', () => {
            const definition = tourFor(branch, context())!;
            expect(branchSteps(definition).length).toBeLessThanOrEqual(4);
            expect(keys(definition).at(-3)).toBe(branch === 'COMMUNITY' ? 'community.study' : 'own.study');
        });

        it('draws the finale as external screens: «ИИ в Mnema» only where AI exists, then the congratulation', () => {
            expect(keys(tourFor(branch, context({ aiAvailable: true }))).slice(-2)).toEqual(['finale.ai', 'finale.done']);
            expect(keys(tourFor(branch, context({ aiAvailable: false }))).slice(-1)).toEqual(['finale.done']);
            expect(keys(tourFor(branch, context({ aiAvailable: false })))).not.toContain('finale.ai');
            for (const step of tourFor(branch, context())!.steps.filter(step => step.key.startsWith('finale'))) expect(step.external).toBe(true);
            expect(finaleKey(true)).toBe('finale.ai');
            expect(finaleKey(false)).toBe('finale.done');
        });

        it('ends its study step by pausing the tour for the session, and names the control to press in the approved words', () => {
            const study = tourFor(branch, context())!.steps.find(step => step.key.endsWith('.study'))!;
            expect(study.gate).toEqual({ action: TOUR_EVENTS.studyStarted, label: HUB_STUDY_LABEL, pauses: true });
            expect(study.anchor).toBe('hub.start');
            expect(route(study)).toBe('/decks/deck-1');
        });
    });

    describe('COMMUNITY', () => {
        it('goes search, deck, add, study for a newcomer, with the approved texts', () => {
            const definition = tourFor('COMMUNITY', context())!;
            expect(keys(definition).slice(0, 4)).toEqual(['community.search', 'community.deck', 'community.add', 'community.study']);
            const [search, deck, add, study] = definition.steps;
            expect([search.title, deck.title, add.title, study.title]).toEqual([1, 2, 3, 4].map(n => ONBOARDING_TEXTS[`community.${n}.title` as 'community.1.title']));
            expect(plainText(search.body.kind === 'paragraphs' ? search.body.paragraphs[0] : [])).toBe('Ищите по названию или теме. На полке «Для вас» — колоды по темам, которые вы выбрали.');
        });

        it('waits for a deck to be opened, with no control to name, and starts on the catalogue', () => {
            const search = tourFor('COMMUNITY', context())!.steps[0];
            expect(search.gate).toEqual({ action: 'community.deck-opened', label: null });
            expect(search.route).toBe('/community');
            expect(search.anchor).toBe('community.search');
        });

        it('waits for the copy that worked, and names «Добавить к себе»', () => {
            const add = tourFor('COMMUNITY', context())!.steps[2];
            expect(add.gate).toEqual({ action: 'deck.copied', label: 'Добавить к себе' });
            expect(add.anchor).toBe('community.add');
        });

        it('follows the learner to the copy of the deck: the route is read when the step is entered', () => {
            let deck: string | null = null;
            const study = tourFor('COMMUNITY', { experience: 'NEW', aiAvailable: false, deckId: () => deck, memberKey: () => null })!.steps[3];
            expect(route(study)).toBeNull();
            deck = 'copy-7';
            expect(route(study)).toBe('/decks/copy-7');
        });

        it('keeps only search, add and study for a learner who knows flashcards', () => {
            for (const experience of ['FAMILIAR', 'CONFIDENT'] as const) {
                expect(keys(tourFor('COMMUNITY', context({ experience }))).slice(0, 3)).toEqual(['community.search', 'community.add', 'community.study']);
                expect(branchSteps(tourFor('COMMUNITY', context({ experience }))!)).toHaveLength(3);
            }
            expect(branchSteps(tourFor('COMMUNITY', context({ experience: 'NEW' }))!)).toHaveLength(4);
        });
    });

    describe('OWN', () => {
        it('goes details, material, exercise, study, with the approved texts', () => {
            const definition = tourFor('OWN', context())!;
            expect(keys(definition).slice(0, 4)).toEqual(['own.details', 'own.material', 'own.exercise', 'own.study']);
            expect(definition.steps.slice(0, 4).map(step => step.title)).toEqual([1, 2, 3, 4].map(n => ONBOARDING_TEXTS[`own.${n}.title` as 'own.1.title']));
        });

        it('names the real submit button of the form for the first step', () => {
            const details = tourFor('OWN', context())!.steps[0];
            expect(details.gate).toEqual({ action: 'deck.created', label: 'Создать колоду' });
            expect(details.route).toBe('/decks/new');
        });

        it('opens the composer where AI composing is available and the plain editor where it is not, with the matching text', () => {
            const withAi = tourFor('OWN', context({ aiAvailable: true }))!.steps[1];
            const without = tourFor('OWN', context({ aiAvailable: false }))!.steps[1];
            expect(route(withAi)).toBe('/decks/deck-1/materials/new');
            expect(route(without)).toBe('/decks/deck-1/materials/new?write=1');
            expect(plainText(withAi.body.kind === 'paragraphs' ? withAi.body.paragraphs[0] : [])).toContain('ИИ');
            expect(plainText(without.body.kind === 'paragraphs' ? without.body.paragraphs[0] : [])).toBe('Впишите текст: определение, правило или слово с переводом.');
        });

        it('waits for a material and an exercise that were really published, with no control to name', () => {
            const [, material, exercise] = tourFor('OWN', context())!.steps;
            expect(material.gate).toEqual({ action: 'material.published', label: null });
            expect(exercise.gate).toEqual({ action: 'exercise.published', label: null });
            expect([material.anchor, exercise.anchor]).toEqual(['own.material', 'own.exercise']);
        });

        it('points the exercise step at the material that was published, else at the hub, and nowhere without a deck', () => {
            const exercise = (deck: string | null, member: string | null): TourStep =>
                tourFor('OWN', context({ deck, member }))!.steps[2];
            expect(route(exercise('d', 'm'))).toBe('/decks/d/materials/m/exercises/new');
            expect(route(exercise('d', null))).toBe('/decks/d');
            expect(route(exercise(null, null))).toBeNull();
            expect(route(tourFor('OWN', context({ deck: null }))!.steps[1])).toBeNull();
        });

        it('is the same four steps for a learner who knows flashcards: every one is essential', () => {
            for (const experience of ['FAMILIAR', 'CONFIDENT'] as const) {
                expect(keys(tourFor('OWN', context({ experience }))).slice(0, 4)).toEqual(['own.details', 'own.material', 'own.exercise', 'own.study']);
            }
        });
    });
});
