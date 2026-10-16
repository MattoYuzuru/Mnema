import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { setup, Setup } from './onboarding-test-data';

const DECK = '11111111-1111-4111-8111-111111111111';
const MATERIAL = '44444444-4444-4444-8444-444444444444';

/**
 * Who navigates after a step of the own-deck branch. The pages emit the gate event and, when no tour took it, go to their own place;
 * a tour that took it goes on to the next step's place by itself. The final URL must be the next step's place, never the page's own
 * (the two navigations used to cancel each other, leaving whichever was last).
 */
describe('the own-deck tour and the address after each step', () => {
    let s: Setup;
    let router: Router;
    const settle = async (): Promise<void> => { await new Promise(resolve => setTimeout(resolve, 0)); TestBed.tick(); await new Promise(resolve => setTimeout(resolve, 0)); };

    /** What a page does when its work is done: say so, and navigate to its own place only when nobody took the event. */
    const finish = async (event: string, payload: Record<string, string>, ownPlace: string): Promise<string> => {
        const navigations: string[] = [];
        const original = router.navigateByUrl.bind(router);
        vi.spyOn(router, 'navigateByUrl').mockImplementation((url, extras) => { navigations.push(String(url)); return original(url, extras); });
        const taken = s.events.emit(event, payload);
        if (!taken) await router.navigateByUrl(ownPlace);
        await settle();
        vi.restoreAllMocks();
        expect(navigations, `${event}: one navigation`).toHaveLength(1);
        return router.url;
    };

    const begin = async (ai: boolean): Promise<void> => {
        s = setup({ ai, journey: { state: 'IN_PROGRESS', branch: 'OWN', stepKey: 'own.details', autoOffer: false, experience: 'NEW' } });
        router = TestBed.inject(Router);
        router.resetConfig([{ path: '**', children: [] }]);
        await s.flow.startTour('OWN');
        await settle();
    };

    it('goes from the form to the composer (or the plain editor), not to the hub the form would open', async () => {
        await begin(true);
        expect(router.url).toBe('/decks/new');
        expect(await finish('deck.created', { deckId: DECK }, `/decks/${DECK}`)).toBe(`/decks/${DECK}/materials/new`);
        expect(s.engine.step()?.key).toBe('own.material');
    });

    it('opens the plain editor for an account without AI', async () => {
        await begin(false);
        expect(await finish('deck.created', { deckId: DECK }, `/decks/${DECK}`)).toBe(`/decks/${DECK}/materials/new?write=1`);
    });

    it('goes from the published material to the exercise composer of that very material, not to the material\'s page', async () => {
        await begin(true);
        await finish('deck.created', { deckId: DECK }, `/decks/${DECK}`);
        expect(await finish('material.published', { deckId: DECK, memberKey: MATERIAL }, `/decks/${DECK}/materials/${MATERIAL}`))
            .toBe(`/decks/${DECK}/materials/${MATERIAL}/exercises/new`);
        expect(s.engine.step()?.key).toBe('own.exercise');
    });

    it('goes from the saved exercise to the hub, not to the page the editor would open', async () => {
        await begin(true);
        await finish('deck.created', { deckId: DECK }, `/decks/${DECK}`);
        await finish('material.published', { deckId: DECK, memberKey: MATERIAL }, `/decks/${DECK}/materials/${MATERIAL}`);
        expect(await finish('exercise.published', { deckId: DECK }, `/decks/${DECK}/exercises/x/edit`)).toBe(`/decks/${DECK}`);
        expect(s.engine.step()?.key).toBe('own.study');
    });

    it('keeps the Workshop from the AI composer on the material step: the gate is still waiting, and the tour is not ended', async () => {
        await begin(true);
        await finish('deck.created', { deckId: DECK }, `/decks/${DECK}`);
        // The composer opens the Workshop; the step stays (the Workshop carries the same anchor) and the next event is the approval.
        await router.navigateByUrl(`/decks/${DECK}/workshop/s1`);
        await settle();
        expect(s.engine.active()).toBe(true);
        expect(s.engine.step()?.key).toBe('own.material');
        expect(await finish('material.published', { deckId: DECK, memberKey: MATERIAL }, `/decks/${DECK}/workshop/s1`))
            .toBe(`/decks/${DECK}/materials/${MATERIAL}/exercises/new`);
    });

    it('lets the page go to its own place, exactly once, when the tour is paused, and the plaque takes the learner on afterwards', async () => {
        await begin(true);
        s.engine.pause();
        await settle();
        expect(await finish('deck.created', { deckId: DECK }, `/decks/${DECK}`)).toBe(`/decks/${DECK}`);
        s.engine.resume();
        await settle();
        expect(router.url).toBe(`/decks/${DECK}/materials/new`);
        expect(s.engine.step()?.key).toBe('own.material');
    });

    it('lets the page go to its own place when the next step has no place of its own', async () => {
        await begin(true);
        await finish('deck.created', { deckId: DECK }, `/decks/${DECK}`);
        await finish('material.published', { deckId: DECK, memberKey: MATERIAL }, `/decks/${DECK}/materials/${MATERIAL}`);
        await finish('exercise.published', { deckId: DECK }, `/decks/${DECK}/exercises/x/edit`);
        // The study step pauses the tour for the session: the event does not belong to the tour's navigation.
        expect(s.events.emit('study.started')).toBe(false);
    });

    it('lets the page go to its own place when no tour is running', async () => {
        s = setup({ journey: { state: 'COMPLETED', autoOffer: true } });
        router = TestBed.inject(Router);
        router.resetConfig([{ path: '**', children: [] }]);
        expect(await finish('deck.created', { deckId: DECK }, `/decks/${DECK}`)).toBe(`/decks/${DECK}`);
    });
});
