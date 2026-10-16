import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { TourHints } from '../../core/onboarding/tour-hints';
import { BROWSE_HINT_MS, BROWSE_STUCK_MS, MINIMUM_CATALOGUE, OnboardingFlow } from './onboarding-flow.service';
import { PATH_TARGET } from './onboarding-targets';
import { Setup, setup } from './onboarding-test-data';

describe('OnboardingFlow', () => {
    afterEach(() => vi.useRealTimers());

    describe('when the first entry opens by itself', () => {
        it('is the welcome for an account the server offered it to', () => {
            expect(setup().flow.pendingCard()).toBe('welcome');
        });

        it.each([
            ['the holdout (eligible, not auto-offered)', { autoOffer: false }],
            ['a journey that is not offered', { state: 'NOT_OFFERED', eligible: false, autoOffer: false }],
            ['one that was skipped', { state: 'SKIPPED', autoOffer: false }],
            ['one that is paused', { state: 'PAUSED', stepKey: 'own.material', branch: 'OWN', autoOffer: false }],
            ['one in a branch tour', { state: 'IN_PROGRESS', stepKey: 'own.material', branch: 'OWN', autoOffer: false }]
        ] as const)('is nothing for %s', (_name, journey) => {
            expect(setup({ journey }).flow.pendingCard()).toBeNull();
        });

        it('is nothing while onboarding is off or there is no journey', () => {
            expect(setup({ journey: null }).flow.pendingCard()).toBeNull();
        });

        it.each(['survey.goal', 'survey.interests', 'survey.experience', 'survey.days', 'choose'] as const)('reopens at %s for a journey a reload interrupted there', key => {
            expect(setup({ journey: { state: 'IN_PROGRESS', stepKey: key, autoOffer: false } }).flow.pendingCard()).toBe(key);
        });
    });

    describe('the welcome', () => {
        it('opens at its card and reads what the survey and the choice need', async () => {
            const { flow, goal, interests, capabilities, community } = setup();
            flow.open('welcome');
            await vi.waitFor(() => expect(goal.load).toHaveBeenCalled());
            expect(flow.card()).toBe('welcome');
            expect(interests.ensure).toHaveBeenCalled();
            expect(capabilities.read).toHaveBeenCalled();
            expect(community.facets).toHaveBeenCalled();
        });

        it('opens only once while a card is up', () => {
            const { flow } = setup();
            flow.open('welcome');
            flow.open('survey.days');
            expect(flow.card()).toBe('welcome');
        });

        it('starts the journey at the first card that still has a question', async () => {
            const { flow, store } = setup();
            flow.open('welcome');
            await flow.start();
            expect(store.calls).toEqual([['START', { stepKey: 'survey.goal' }]]);
            expect(flow.card()).toBe('survey.goal');
        });

        it.each([
            ['the goal was answered before', { goalAnswered: true }, 'survey.interests'],
            ['the goal and the interests were', { goalAnswered: true, interestsOpen: false }, 'survey.experience']
        ] as const)('skips what is answered: %s', async (_name, options, first) => {
            const { flow, store } = setup(options);
            flow.open('welcome');
            await flow.start();
            expect(store.calls[0]).toEqual(['START', { stepKey: first }]);
        });

        it('goes straight to the choice when nothing is left to ask', async () => {
            const { flow, store } = setup({ journey: { experience: 'NEW', weeklyTarget: 3 }, goalAnswered: true, interestsOpen: false });
            flow.open('welcome');
            await flow.start();
            expect(store.calls[0]).toEqual(['START', { stepKey: 'choose' }]);
            expect(flow.card()).toBe('choose');
        });

        it('closes the surface when the server does not take the start: nothing blocks the page', async () => {
            const { flow, store } = setup();
            flow.open('welcome');
            store.failNext = true;
            await flow.start();
            expect(flow.card()).toBeNull();
        });

        it('skips with SKIP and a toast whose link goes to the one place «Путь» lives', async () => {
            const { flow, store, toasts } = setup();
            flow.open('welcome');
            await flow.skipAll();
            expect(store.calls).toEqual([['SKIP', undefined]]);
            expect(flow.card()).toBeNull();
            expect(toasts.notify).toHaveBeenCalledWith('onboarding-skipped',
                'Знакомство пропущено. Пройти его можно в профиле, а все возможности Mnema собраны в «Пути».', 'INFO', { label: 'Открыть «Путь»', commands: [PATH_TARGET] });
        });

        it('says nothing when the skip was not recorded', async () => {
            const { flow, store, toasts } = setup();
            flow.open('welcome');
            store.failNext = true;
            await flow.skipAll();
            expect(toasts.notify).not.toHaveBeenCalled();
        });

        it('ignores start and skip when the welcome is not the card', async () => {
            const { flow, store } = setup();
            await flow.start();
            await flow.skipAll();
            expect(store.calls).toEqual([]);
        });
    });

    describe('the survey', () => {
        const atSurvey = async (options: Parameters<typeof setup>[0] = {}): Promise<Setup> => {
            const s = setup(options);
            s.flow.open('welcome');
            await s.flow.start();
            return s;
        };

        it('numbers the questions out of four and leaves a gap for one that was skipped', async () => {
            const { flow } = await atSurvey();
            expect([flow.questionNumber('survey.goal'), flow.questionNumber('survey.interests'), flow.questionNumber('survey.experience'), flow.questionNumber('survey.days')])
                .toEqual([1, 2, 3, 4]);
        });

        it('records the card it moves to with STEP on every change', async () => {
            const { flow, store, goal } = await atSurvey();
            await flow.answerGoal('LANGUAGE');
            expect(goal.answer).toHaveBeenCalledWith('LANGUAGE');
            expect(flow.card()).toBe('survey.interests');
            expect(store.steps()).toEqual(['survey.goal', 'survey.interests']);
        });

        it('stays on the goal card when the goal could not be stored', async () => {
            const { flow, goal } = await atSurvey();
            goal.answer.mockResolvedValueOnce(false);
            await flow.answerGoal('EXAMS');
            expect(flow.card()).toBe('survey.goal');
        });

        it('records a skipped goal the way the old question did', async () => {
            const { flow, goal } = await atSurvey();
            await flow.answerGoal(null);
            expect(goal.answer).toHaveBeenCalledWith(null);
        });

        it('records a skipped interests card as complete, so the Community does not ask again, and goes on', async () => {
            const { flow, interests } = await atSurvey({ goalAnswered: true });
            expect(flow.card()).toBe('survey.interests');
            flow.skipInterests();
            expect(interests.save).toHaveBeenCalledWith([]);
            expect(flow.card()).toBe('survey.experience');
        });

        it('stores the experience and the days through the journey, nothing else', async () => {
            const { flow, store, goal, interests } = await atSurvey({ goalAnswered: true, interestsOpen: false });
            await flow.answerExperience('FAMILIAR');
            expect(flow.card()).toBe('survey.days');
            await flow.answerDays(5);
            expect(store.preferences).toEqual([{ experience: 'FAMILIAR' }, { weeklyTarget: 5 }]);
            expect(flow.card()).toBe('choose');
            expect(goal.answer).not.toHaveBeenCalled();
            expect(interests.save).not.toHaveBeenCalled();
        });

        it('goes on when the preference could not be saved: the survey never blocks', async () => {
            const { flow, store } = await atSurvey({ goalAnswered: true, interestsOpen: false });
            store.failNext = true;
            await flow.answerExperience('NEW');
            expect(flow.card()).toBe('survey.days');
        });

        it('skips a question the journey already has an answer to', async () => {
            const { flow } = await atSurvey({ goalAnswered: true, interestsOpen: false, journey: { experience: 'NEW' } });
            expect(flow.card()).toBe('survey.days');
        });

        it('goes back through the cards it showed, records the card it returns to, and never back to the welcome', async () => {
            const { flow, store } = await atSurvey();
            expect(flow.canGoBack()).toBe(false);
            flow.back();
            expect(flow.card()).toBe('survey.goal');
            flow.advance();
            expect(flow.card()).toBe('survey.interests');
            expect(flow.canGoBack()).toBe(true);
            flow.back();
            expect(flow.card()).toBe('survey.goal');
            expect(store.steps().at(-1)).toBe('survey.goal');
        });

        it('sends nothing to anything that is not the learner\'s own stores and the journey (no AI)', async () => {
            const { flow, store } = await atSurvey({ goalAnswered: true, interestsOpen: false });
            await flow.answerExperience('CONFIDENT');
            await flow.answerDays(7);
            expect(store.calls.every(([action]) => action === 'START' || action === 'STEP')).toBe(true);
        });
    });

    describe('the branch choice', () => {
        it('offers «Своя колода» alone when there is no sample and the catalogue is thin', async () => {
            const { flow } = setup({ listed: MINIMUM_CATALOGUE - 1 });
            flow.open('welcome');
            await vi.waitFor(() => expect(flow.branches()).toEqual(['OWN']));
        });

        it('offers the catalogue from four decks, in its place before «Своя колода»', async () => {
            const { flow } = setup({ listed: MINIMUM_CATALOGUE });
            flow.open('welcome');
            await vi.waitFor(() => expect(flow.branches()).toEqual(['COMMUNITY', 'OWN']));
        });

        it('hides the catalogue when it cannot be read or is not launched, with no apology', async () => {
            const failing = setup({ listed: 'error' });
            failing.flow.open('welcome');
            await vi.waitFor(() => expect(failing.community.facets).toHaveBeenCalled());
            expect(failing.flow.branches()).toEqual(['OWN']);
            TestBed.resetTestingModule();
            const closed = setup({ launched: false });
            closed.flow.open('welcome');
            await vi.waitFor(() => expect(closed.goal.load).toHaveBeenCalled());
            expect(closed.community.facets).not.toHaveBeenCalled();
            expect(closed.flow.branches()).toEqual(['OWN']);
        });

        it('offers the sample only when the server says the sample deck exists', async () => {
            const { flow } = setup({ listed: 10, view: { sampleDeck: { available: true, deckId: 'e1b6bb58-3c0e-4f6a-8f11-4f2d66f6a1aa', updateAvailable: false } } });
            flow.open('welcome');
            await vi.waitFor(() => expect(flow.branches()).toEqual(['SAMPLE', 'COMMUNITY', 'OWN']));
        });

        it('starts «Своя колода» with STEP at its first step and its tour', async () => {
            const { flow, store, engine } = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false } });
            flow.open('choose');
            await flow.chooseBranch('OWN');
            expect(store.calls).toEqual([['STEP', { stepKey: 'own.details', branch: 'OWN' }]]);
            expect(flow.card()).toBeNull();
            expect(engine.definition()?.id).toBe('own-v1');
            expect(engine.step()?.key).toBe('own.details');
        });

        it('starts the community tour at the catalogue', async () => {
            const { flow, store, engine } = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false } });
            flow.open('choose');
            await flow.chooseBranch('COMMUNITY');
            expect(store.calls).toEqual([['STEP', { stepKey: 'community.search', branch: 'COMMUNITY' }]]);
            expect(engine.step()?.key).toBe('community.search');
        });

        it('records the sample choice and starts no tour: the sample tour is OB-203', async () => {
            const { flow, store, engine } = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false } });
            flow.open('choose');
            await flow.chooseBranch('SAMPLE');
            expect(store.calls).toEqual([['STEP', { stepKey: 'sample.hub', branch: 'SAMPLE' }]]);
            expect(engine.active()).toBe(false);
        });

        it('keeps the card when the choice was not recorded', async () => {
            const { flow, store, engine } = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false } });
            flow.open('choose');
            store.failNext = true;
            await flow.chooseBranch('OWN');
            expect(engine.active()).toBe(false);
        });

        it('gives a learner who knows flashcards the short tour', async () => {
            const { flow, engine } = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false, experience: 'FAMILIAR' } });
            flow.open('choose');
            await flow.chooseBranch('COMMUNITY');
            expect(engine.definition()?.steps.filter(step => step.external !== true).map(step => step.key)).toEqual(['community.search', 'community.add', 'community.study']);
        });
    });

    describe('the replay', () => {
        const replayed = async (decks: { deckId: string; title: string }[] = []) => {
            const s = setup({ journey: { state: 'COMPLETED', autoOffer: false, stepKey: 'finale.done', branch: 'OWN', experience: 'NEW', weeklyTarget: 3 }, ai: true });
            s.decks.list.mockReturnValue(of({ items: decks.map(deck => ({ deckId: deck.deckId, metadata: { title: deck.title }, fork: null })), nextCursor: null }));
            await s.flow.replay();
            return s;
        };

        it('opens the branch choice without the survey, with REPLAY at «choose»', async () => {
            const { flow, store } = await replayed();
            expect(store.calls).toEqual([['REPLAY', { stepKey: 'choose' }]]);
            expect(flow.card()).toBe('choose');
            expect(flow.replaying()).toBe(true);
            expect(flow.canGoBack()).toBe(false);
        });

        it('never changes the survey answers and makes no deck by itself', async () => {
            const { flow, store, goal, interests } = await replayed();
            await flow.chooseBranch('COMMUNITY');
            expect(store.preferences).toEqual([]);
            expect(goal.answer).not.toHaveBeenCalled();
            expect(interests.save).not.toHaveBeenCalled();
            expect(store.calls.map(([action]) => action)).toEqual(['REPLAY', 'STEP']);
        });

        it('stops a tour in progress before it starts the replay', async () => {
            const s = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'own.material', branch: 'OWN', autoOffer: false } });
            await s.flow.startTour('OWN', 'own.material');
            expect(s.engine.active()).toBe(true);
            await s.flow.replay();
            expect(s.engine.active()).toBe(false);
        });

        it('pauses a journey that is in progress before it replays it, because the server does not replay from there', async () => {
            const s = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'own.material', branch: 'OWN', autoOffer: false } });
            await s.flow.replay();
            expect(s.store.calls.map(([action]) => action)).toEqual(['PAUSE', 'REPLAY']);
            expect(s.store.rejections).toEqual([]);
            expect(s.store.journey()?.replayCount).toBe(1);
            expect(s.flow.card()).toBe('choose');
        });

        it('starts nothing when the pause that must come first was refused: the tour the learner is in stays', async () => {
            const s = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'own.material', branch: 'OWN', autoOffer: false } });
            await s.flow.startTour('OWN', 'own.material');
            s.store.failNext = true;
            await s.flow.replay();
            expect(s.store.calls.map(([action]) => action)).toEqual(['PAUSE']);
            expect(s.flow.card()).toBeNull();
            expect(s.engine.active()).toBe(true);
        });

        it('starts «Своя колода» right away for an account without decks', async () => {
            const { flow, engine, store } = await replayed();
            await flow.chooseBranch('OWN');
            expect(store.calls.at(-1)).toEqual(['STEP', { stepKey: 'own.details', branch: 'OWN' }]);
            expect(engine.step()?.key).toBe('own.details');
        });

        it('offers «new deck / an existing one» to an account with decks', async () => {
            const { flow, engine } = await replayed([{ deckId: 'deck-1', title: 'Испанский' }]);
            await vi.waitFor(() => expect(flow.replayDecks()).toEqual([{ deckId: 'deck-1', title: 'Испанский' }]));
            await flow.chooseBranch('OWN');
            expect(flow.card()).toBe('replay.deck');
            expect(engine.active()).toBe(false);
            flow.back();
            expect(flow.card()).toBe('choose');
        });

        it('starts at the material of the deck the learner already has, on that deck', async () => {
            const { flow, engine, store } = await replayed([{ deckId: 'deck-1', title: 'Испанский' }]);
            await vi.waitFor(() => expect(flow.replayDecks()).toHaveLength(1));
            await flow.chooseBranch('OWN');
            await flow.chooseReplayDeck('deck-1');
            expect(store.calls.at(-1)).toEqual(['STEP', { stepKey: 'own.material', branch: 'OWN' }]);
            expect(engine.step()?.key).toBe('own.material');
            expect(typeof engine.step()?.route === 'function' ? (engine.step()!.route as () => string | null)() : null).toBe('/decks/deck-1/materials/new');
            expect(flow.card()).toBeNull();
        });

        it('makes a new deck from the same offer', async () => {
            const { flow, engine } = await replayed([{ deckId: 'deck-1', title: 'Испанский' }]);
            await vi.waitFor(() => expect(flow.replayDecks()).toHaveLength(1));
            await flow.chooseBranch('OWN');
            await flow.chooseReplayDeck(null);
            expect(engine.step()?.key).toBe('own.details');
        });

        it('leaves out a copy that is still being prepared and offers at most six decks', async () => {
            const s = setup({ journey: { state: 'COMPLETED', autoOffer: false }, ai: true });
            s.decks.list.mockReturnValue(of({
                items: [{ deckId: 'prep', metadata: { title: 'x' }, fork: { preparing: true } },
                    ...Array.from({ length: 8 }, (_, index) => ({ deckId: `d${index}`, metadata: { title: `Колода ${index}` }, fork: null }))], nextCursor: null
            }));
            await s.flow.replay();
            await vi.waitFor(() => expect(s.flow.replayDecks()).toHaveLength(6));
            expect(s.flow.replayDecks().some(deck => deck.deckId === 'prep')).toBe(false);
        });

        it('does not offer decks when they cannot be read', async () => {
            const s = setup({ journey: { state: 'COMPLETED', autoOffer: false } });
            s.decks.list.mockReturnValue(throwError(() => new Error('down')));
            await s.flow.replay();
            await s.flow.chooseBranch('OWN');
            expect(s.engine.step()?.key).toBe('own.details');
        });

        it('does nothing when the replay was not recorded', async () => {
            const s = setup({ journey: { state: 'COMPLETED', autoOffer: false } });
            s.store.failNext = true;
            await s.flow.replay();
            expect(s.flow.card()).toBeNull();
        });
    });

    describe('the study session and the finale', () => {
        const inStudy = async (branch: 'OWN' | 'COMMUNITY', ai: boolean) => {
            const s = setup({ ai, journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false } });
            s.flow.open('choose');
            await s.flow.chooseBranch(branch);
            return s;
        };

        it('walks the own-deck tour on the deck the learner made, the material they published and back to the hub for the session', async () => {
            const { engine, events } = await inStudy('OWN', true);
            events.emit('deck.created', { deckId: 'deck-9' });
            expect(engine.step()?.key).toBe('own.material');
            const route = (): string | null => { const r = engine.step()!.route; return typeof r === 'function' ? r() : r ?? null; };
            expect(route()).toBe('/decks/deck-9/materials/new');
            events.emit('material.published', { deckId: 'deck-9', memberKey: 'm-1' });
            expect(engine.step()?.key).toBe('own.exercise');
            expect(route()).toBe('/decks/deck-9/materials/m-1/exercises/new');
            events.emit('exercise.published', { deckId: 'deck-9' });
            expect(engine.step()?.key).toBe('own.study');
            expect(route()).toBe('/decks/deck-9');
        });

        it('opens the plain editor when AI is not available', async () => {
            const { engine, events } = await inStudy('OWN', false);
            events.emit('deck.created', { deckId: 'deck-9' });
            expect((engine.step()!.route as () => string | null)()).toBe('/decks/deck-9/materials/new?write=1');
        });

        it('follows the copy of a community deck to its hub', async () => {
            const { engine, events } = await inStudy('COMMUNITY', true);
            events.emit('community.deck-opened');
            expect(engine.step()?.key).toBe('community.deck');
            engine.next();
            events.emit('deck.copied', { deckId: 'copy-3' });
            expect(engine.step()?.key).toBe('community.study');
            expect((engine.step()!.route as () => string | null)()).toBe('/decks/copy-3');
        });

        it('pauses on the study step for the session, recording PAUSE with the branch\'s study step', async () => {
            const { engine, events, store } = await inStudy('OWN', true);
            for (const [event, payload] of [['deck.created', { deckId: 'd' }], ['material.published', { deckId: 'd', memberKey: 'm' }], ['exercise.published', { deckId: 'd' }]] as const) events.emit(event, payload);
            events.emit('study.started');
            expect(engine.paused()).toBe(true);
            expect(store.calls.at(-1)).toEqual(['PAUSE', { stepKey: 'own.study' }]);
            expect(store.journey()?.state).toBe('PAUSED');
        });

        it.each([[true, 'finale.ai'], [false, 'finale.done']] as const)('goes on at the finale when the session is over (AI %s: %s), recording RESUME there', async (ai, finale) => {
            const { engine, events, store } = await inStudy('OWN', ai);
            for (const [event, payload] of [['deck.created', { deckId: 'd' }], ['material.published', { deckId: 'd', memberKey: 'm' }], ['exercise.published', { deckId: 'd' }]] as const) events.emit(event, payload);
            events.emit('study.started');
            events.emit('study.completed');
            expect(engine.paused()).toBe(false);
            expect(engine.step()?.key).toBe(finale);
            expect(store.calls.at(-1)).toEqual(['RESUME', { stepKey: finale }]);
        });

        it('leaves a session left midway to the plaque: resuming goes back to the study step, and a finished session then goes on', async () => {
            const { engine, events } = await inStudy('COMMUNITY', true);
            events.emit('community.deck-opened');
            engine.next();
            events.emit('deck.copied', { deckId: 'c' });
            events.emit('study.started');
            expect(engine.paused()).toBe(true);
            engine.resume();
            expect(engine.step()?.key).toBe('community.study');
            events.emit('study.completed');
            // The learner resumed meanwhile: a session of some other deck does not move the tour.
            expect(engine.step()?.key).toBe('community.study');
            events.emit('study.started');
            events.emit('study.completed');
            expect(engine.step()?.key).toBe('finale.ai');
        });

        it('ignores the end of a session when no tour paused for it', async () => {
            const { engine, events } = setup();
            events.emit('study.completed');
            expect(engine.active()).toBe(false);
        });

        it('completes the journey after the congratulation', async () => {
            const { engine, events, store } = await inStudy('OWN', false);
            for (const [event, payload] of [['deck.created', { deckId: 'd' }], ['material.published', { deckId: 'd', memberKey: 'm' }], ['exercise.published', { deckId: 'd' }]] as const) events.emit(event, payload);
            events.emit('study.started');
            events.emit('study.completed');
            engine.next();
            expect(store.calls.at(-1)).toEqual(['COMPLETE', {}]);
            expect(store.journey()?.state).toBe('COMPLETED');
        });
    });

    describe('picking up a tour on another device', () => {
        it('shows the plaque for a paused journey, without jumping into the tour and without recording anything', async () => {
            const { flow, engine, store } = setup({ journey: { state: 'PAUSED', stepKey: 'own.material', branch: 'OWN', autoOffer: false }, ai: true });
            await flow.resumeIfPaused('a');
            expect(engine.active()).toBe(true);
            expect(engine.paused()).toBe(true);
            expect(engine.step()?.key).toBe('own.material');
            expect(store.calls).toEqual([]);
        });

        it('pauses a journey that was left in progress on a branch step first, so the plaque\'s «Продолжить» is a valid RESUME', async () => {
            const { flow, engine, store } = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'community.add', branch: 'COMMUNITY', autoOffer: false } });
            await flow.resumeIfPaused('a');
            expect(store.calls).toEqual([['PAUSE', { stepKey: 'community.add' }]]);
            expect(engine.paused()).toBe(true);
            engine.resume();
            expect(store.calls.at(-1)).toEqual(['RESUME', { stepKey: 'community.add' }]);
        });

        it('picks the paused finale up at the finale', async () => {
            const { flow, engine } = setup({ journey: { state: 'PAUSED', stepKey: 'finale.ai', branch: 'OWN', autoOffer: false }, ai: true });
            await flow.resumeIfPaused('a');
            expect(engine.step()?.key).toBe('finale.ai');
        });

        it('leaves the learner on the study step when the finale of a journey paused in the AI finale is gone (AI is off here)', async () => {
            const { flow, engine } = setup({ journey: { state: 'PAUSED', stepKey: 'finale.ai', branch: 'OWN', autoOffer: false }, ai: false });
            await flow.resumeIfPaused('a');
            expect(engine.step()?.key).toBe('finale.done');
        });

        it.each([
            ['an offered journey', { state: 'OFFERED' }],
            ['a first-entry card', { state: 'IN_PROGRESS', stepKey: 'survey.days', branch: null }],
            ['a journey with no branch', { state: 'PAUSED', stepKey: 'own.material', branch: null }],
            ['a completed journey', { state: 'COMPLETED', stepKey: 'finale.done', branch: 'OWN' }]
        ] as const)('does nothing for %s', async (_name, journey) => {
            const { flow, engine } = setup({ journey: { autoOffer: false, ...journey } });
            await flow.resumeIfPaused('a');
            expect(engine.active()).toBe(false);
        });

        it('decides once per account, from the journey as the page found it', async () => {
            const { flow, engine, store } = setup({ journey: { state: 'PAUSED', stepKey: 'own.study', branch: 'OWN', autoOffer: false } });
            await flow.resumeIfPaused('a');
            engine.stop();
            await flow.resumeIfPaused('a');
            expect(engine.active()).toBe(false);
            expect(store.act).not.toHaveBeenCalled();
            await flow.resumeIfPaused('b');
            expect(engine.active()).toBe(true);
        });

        it('does not touch a tour that is already running', async () => {
            const { flow, engine } = setup({ journey: { state: 'PAUSED', stepKey: 'own.study', branch: 'OWN', autoOffer: false } });
            await flow.startTour('COMMUNITY');
            await flow.resumeIfPaused('a');
            expect(engine.definition()?.id).toBe('community-v1');
        });
    });

    describe('when the server answers with its own journey instead of taking the command', () => {
        it('closes the welcome and starts nothing when START is refused because the journey moved on elsewhere', async () => {
            const s = setup();
            s.flow.open('welcome');
            await s.store.act('SKIP');
            s.store.calls.length = 0;
            await s.flow.start();
            expect(s.store.rejections.at(-1)).toEqual({ action: 'START', reason: 'transition' });
            expect(s.flow.card()).toBeNull();
            expect(s.engine.active()).toBe(false);
        });

        it('does not say the survey was skipped when SKIP is refused', async () => {
            const s = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'survey.goal', autoOffer: true } });
            s.flow.open('welcome');
            await s.flow.skipAll();
            expect(s.store.rejections.at(-1)).toEqual({ action: 'SKIP', reason: 'transition' });
            expect(s.toasts.notify).not.toHaveBeenCalled();
        });

        it('starts no tour when the choice of a branch is refused (the journey is not in progress any more)', async () => {
            const s = setup({ journey: { state: 'PAUSED', stepKey: 'choose', autoOffer: false } });
            s.flow.open('choose');
            await s.flow.chooseBranch('OWN');
            expect(s.store.rejections.at(-1)).toEqual({ action: 'STEP', reason: 'transition' });
            expect(s.engine.active()).toBe(false);
            // The server's journey is the truth: the surface goes, and the host reopens it at whatever card the journey is on.
            expect(s.flow.card()).toBeNull();
        });

        it('keeps the card for another try when the choice never reached the server', async () => {
            const s = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false } });
            s.flow.open('choose');
            s.store.failNext = true;
            await s.flow.chooseBranch('OWN');
            expect(s.flow.card()).toBe('choose');
            expect(s.engine.active()).toBe(false);
        });

        it('keeps the community tour when the move to «Своя колода» is refused', async () => {
            const s = setup({ journey: { state: 'PAUSED', stepKey: 'community.search', branch: 'COMMUNITY', autoOffer: false } });
            await s.flow.startTour('COMMUNITY');
            await s.flow.switchToOwn();
            expect(s.engine.definition()?.id).toBe('community-v1');
        });

        it('does not start a replay when REPLAY is refused', async () => {
            const s = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'own.material', branch: 'OWN', autoOffer: false } });
            vi.spyOn(s.store, 'act').mockImplementation(() => Promise.resolve({ status: 'refused', view: s.store.view()! }));
            await s.flow.replay();
            expect(s.flow.card()).toBeNull();
        });

        it('moves the survey on from the card an answer was given on only once, whatever happens meanwhile', async () => {
            const s = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'survey.experience', autoOffer: true } });
            s.flow.open('survey.experience');
            const first = s.flow.answerExperience('NEW');
            const second = s.flow.answerExperience('FAMILIAR');
            s.flow.skip();
            await Promise.all([first, second]);
            expect(s.store.preferences).toEqual([{ experience: 'NEW' }]);
            expect(s.flow.card()).toBe('survey.days');
        });
    });

    describe('«Пропустить»', () => {
        it('records the goal as skipped, leaves the interests as complete, and just goes on for any other card', async () => {
            const s = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'survey.goal', autoOffer: true } });
            s.flow.open('survey.goal');
            s.flow.skip();
            await Promise.resolve();
            expect(s.goal.answer).toHaveBeenCalledWith(null);
            await vi.waitFor(() => expect(s.flow.card()).toBe('survey.interests'));
            s.flow.skip();
            expect(s.interests.save).toHaveBeenCalledWith([]);
            expect(s.flow.card()).toBe('survey.experience');
            s.flow.skip();
            expect(s.flow.card()).toBe('survey.days');
        });

        it('does nothing while an answer is being saved, so one press never moves the survey twice', async () => {
            const s = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'survey.experience', autoOffer: true } });
            s.flow.open('survey.experience');
            const answering = s.flow.answerExperience('NEW');
            s.flow.skip();
            await answering;
            expect(s.flow.card()).toBe('survey.days');
        });

        it('waits for a save of the interests that is still on its way instead of skipping them over it', async () => {
            const s = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'survey.interests', autoOffer: true } });
            s.flow.open('survey.interests');
            s.interests.busy.set(true);
            s.flow.skipInterests();
            expect(s.interests.save).not.toHaveBeenCalled();
            expect(s.flow.card()).toBe('survey.interests');
        });
    });

    describe('the community hints', () => {
        it('says the hint after 45 s and the offer after two minutes of browsing without opening a deck', async () => {
            vi.useFakeTimers();
            const { flow, engine } = setup();
            const hints = TestBed.inject(TourHints);
            await flow.startTour('COMMUNITY');
            TestBed.tick();
            expect(engine.step()?.key).toBe('community.search');
            vi.advanceTimersByTime(BROWSE_HINT_MS - 1);
            expect(hints.browse()).toBe(false);
            vi.advanceTimersByTime(1);
            expect(hints.browse()).toBe(true);
            expect(hints.stuck()).toBe(false);
            vi.advanceTimersByTime(BROWSE_STUCK_MS - BROWSE_HINT_MS);
            expect(hints.stuck()).toBe(true);
        });

        it('takes them back once a deck is opened, the tour is paused or it ends', async () => {
            vi.useFakeTimers();
            const { flow, engine, events } = setup();
            const hints = TestBed.inject(TourHints);
            await flow.startTour('COMMUNITY');
            TestBed.tick();
            vi.advanceTimersByTime(BROWSE_STUCK_MS);
            expect(hints.stuck()).toBe(true);
            events.emit('community.deck-opened');
            TestBed.tick();
            expect([hints.browse(), hints.stuck()]).toEqual([false, false]);
            engine.back();
            TestBed.tick();
            engine.pause();
            TestBed.tick();
            vi.advanceTimersByTime(BROWSE_STUCK_MS);
            expect(hints.browse()).toBe(false);
        });

        it('moves the tour to «Своя колода» from the offer: STEP into own.details with the own branch', async () => {
            const { flow, engine, store } = setup({ journey: { state: 'IN_PROGRESS', stepKey: 'community.search', branch: 'COMMUNITY', autoOffer: false } });
            await flow.startTour('COMMUNITY');
            await flow.switchToOwn();
            expect(store.calls).toEqual([['STEP', { stepKey: 'own.details', branch: 'OWN' }]]);
            expect(engine.definition()?.id).toBe('own-v1');
            expect(engine.step()?.key).toBe('own.details');
            expect(TestBed.inject(TourHints).stuck()).toBe(false);
        });
    });

    it('forgets the account on sign-out', async () => {
        const { flow, engine, store } = setup();
        flow.open('welcome');
        await flow.startTour('OWN');
        flow.reset();
        expect(flow.card()).toBeNull();
        expect(engine.active()).toBe(false);
        expect(store.reset).toHaveBeenCalled();
    });

    it('closes the surface without touching the journey', () => {
        const { flow, store } = setup();
        flow.open('welcome');
        flow.close();
        expect(flow.card()).toBeNull();
        expect(store.calls).toEqual([]);
    });
});
