import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import contract from '../../../../../contracts/onboarding/journey.json';
import { spyObj } from '../../../testing/mocks';
import { ToastService } from '../notifications/toast.service';
import { ActionOptions, JourneyAction, JourneyResult, OnboardingView } from './onboarding.models';
import { ANCHOR_WAIT_MS, TOUR_PERSISTENCE, TourEngine, TourPersistence } from './tour-engine.service';
import { TourAnchors } from './tour-anchors';
import { TourEvents } from './tour-events';
import { paragraphs } from './tour-rich-text';
import { TourDefinition, TourStep } from './tour.models';

const step = (overrides: Partial<TourStep> & Pick<TourStep, 'key' | 'anchor'>): TourStep =>
    ({ title: `Шаг ${overrides.key}`, body: paragraphs('Текст.'), gate: 'next', ...overrides });

const TOUR: TourDefinition = {
    id: 'sample',
    branch: 'SAMPLE',
    steps: [
        step({ key: 'sample.hub', anchor: 'hub.header' }),
        step({ key: 'sample.material', anchor: 'hub.material' }),
        step({ key: 'sample.exercises', anchor: 'hub.exercises', route: '/decks/demo' }),
        step({ key: 'sample.study', anchor: 'hub.start', gate: { action: 'study.started', label: 'Начать занятие' } })
    ]
};

describe('TourEngine', () => {
    let calls: [JourneyAction, ActionOptions | undefined][];
    let persistence: TourPersistence & { act: ReturnType<typeof vi.fn> };
    let toasts: { echo: ReturnType<typeof vi.fn> };
    let engine: TourEngine;
    let anchors: TourAnchors;
    let events: TourEvents;
    let router: Router;
    const elements = new Map<string, HTMLElement>();

    const mount = (key: string): (() => void) => {
        const element = document.createElement('div');
        elements.set(key, element);
        return anchors.register(key, element);
    };
    const settle = (): void => TestBed.tick();

    beforeEach(() => {
        vi.useFakeTimers();
        calls = [];
        persistence = { act: vi.fn((action: JourneyAction, options?: ActionOptions) => { calls.push([action, options]); return Promise.resolve(null); }) };
        toasts = { echo: vi.fn() };
        TestBed.configureTestingModule({
            providers: [provideRouter([]), { provide: TOUR_PERSISTENCE, useValue: persistence }, { provide: ToastService, useValue: spyObj<ToastService>(toasts) }]
        });
        engine = TestBed.inject(TourEngine);
        anchors = TestBed.inject(TourAnchors);
        events = TestBed.inject(TourEvents);
        router = TestBed.inject(Router);
    });
    afterEach(() => vi.useRealTimers());

    describe('start', () => {
        it('shows the first step and records nothing: the journey was started by the caller', () => {
            engine.start(TOUR);
            expect(engine.active()).toBe(true);
            expect(engine.paused()).toBe(false);
            expect(engine.index()).toBe(0);
            expect(engine.total()).toBe(4);
            expect(engine.step()?.key).toBe('sample.hub');
            expect(calls).toEqual([]);
        });

        it('starts at a given step key, and at the first step for an unknown one', () => {
            engine.start(TOUR, 'sample.exercises');
            expect(engine.index()).toBe(2);
            engine.start(TOUR, 'own.material');
            expect(engine.index()).toBe(0);
        });

        it('replaces a run in progress without recording anything', () => {
            engine.start(TOUR);
            engine.next();
            calls.length = 0;
            engine.start({ ...TOUR, id: 'other' });
            expect(engine.index()).toBe(0);
            expect(engine.definition()?.id).toBe('other');
            expect(calls).toEqual([]);
        });

        it('ignores a definition without steps', () => {
            engine.start({ id: 'empty', branch: null, steps: [] });
            expect(engine.active()).toBe(false);
        });

        it('is inactive and empty before and after a run', () => {
            expect(engine.active()).toBe(false);
            expect(engine.step()).toBeNull();
            expect(engine.total()).toBe(0);
            engine.start(TOUR);
            engine.stop();
            expect(engine.active()).toBe(false);
            expect(engine.step()).toBeNull();
        });

        it('uses the stand-in persistence of the options instead of the default', () => {
            const other = { act: vi.fn(() => Promise.resolve(null)) };
            engine.start(TOUR, undefined, { persistence: other });
            engine.next();
            expect(other.act).toHaveBeenCalledWith('STEP', { stepKey: 'sample.material', branch: 'SAMPLE' });
            expect(persistence.act).not.toHaveBeenCalled();
        });
    });

    describe('moving', () => {
        it('records STEP with the step key and the branch of the tour on every change, forward and back', () => {
            engine.start(TOUR);
            engine.next();
            engine.next();
            engine.back();
            expect(engine.index()).toBe(1);
            expect(calls).toEqual([
                ['STEP', { stepKey: 'sample.material', branch: 'SAMPLE' }],
                ['STEP', { stepKey: 'sample.exercises', branch: 'SAMPLE' }],
                ['STEP', { stepKey: 'sample.material', branch: 'SAMPLE' }]
            ]);
        });

        it('sends no branch for a shared step or a tour without a branch', () => {
            engine.start({ id: 'shared', branch: 'OWN', steps: [step({ key: 'own.details', anchor: 'own.name' }), step({ key: 'finale.ai', anchor: 'own.start' })] });
            engine.next();
            expect(calls).toEqual([['STEP', { stepKey: 'finale.ai' }]]);
            calls.length = 0;
            engine.start({ id: 'free', branch: null, steps: [step({ key: 'finale.ai', anchor: 'own.start' }), step({ key: 'finale.done', anchor: 'own.start' })] });
            engine.next();
            expect(calls).toEqual([['STEP', { stepKey: 'finale.done' }]]);
        });

        it('does not go back from the first step', () => {
            engine.start(TOUR);
            engine.back();
            expect(engine.index()).toBe(0);
            expect(calls).toEqual([]);
        });

        it('completes the tour when «Дальше» is pressed on the last step, without a toast', () => {
            engine.start(TOUR, 'sample.study');
            engine.next();
            settle();
            expect(engine.active()).toBe(false);
            expect(engine.lastEnd()).toEqual({ definitionId: 'sample', reason: 'completed' });
            expect(calls).toEqual([['COMPLETE', {}]]);
            expect(toasts.echo).not.toHaveBeenCalled();
        });

        it('asks for focus on the card heading with every new step', () => {
            engine.start(TOUR);
            const first = engine.focusRequest();
            engine.next();
            expect(engine.focusRequest()).toBeGreaterThan(first);
        });

        it('navigates to the route of a step before it looks for the anchor, and not when it is already there', async () => {
            const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
            engine.start(TOUR);
            engine.next();
            expect(navigate).not.toHaveBeenCalled();
            engine.next();
            expect(navigate).toHaveBeenCalledWith('/decks/demo');
            navigate.mockRejectedValue(new Error('guard'));
            engine.back();
            engine.next();
            await vi.advanceTimersByTimeAsync(0);
            expect(engine.index()).toBe(2);
        });
    });

    describe('pause and resume', () => {
        it('records PAUSE with the step and RESUME to the same step', () => {
            engine.start(TOUR);
            engine.next();
            calls.length = 0;
            engine.pause();
            expect(engine.paused()).toBe(true);
            expect(engine.active()).toBe(true);
            expect(engine.step()?.key).toBe('sample.material');
            engine.resume();
            expect(engine.paused()).toBe(false);
            expect(engine.step()?.key).toBe('sample.material');
            expect(calls).toEqual([['PAUSE', { stepKey: 'sample.material' }], ['RESUME', { stepKey: 'sample.material' }]]);
        });

        it('pauses once and resumes only a paused tour', () => {
            engine.start(TOUR);
            engine.resume();
            engine.pause();
            engine.pause();
            expect(calls).toEqual([['PAUSE', { stepKey: 'sample.hub' }]]);
        });

        it('does not move while paused, and asks for focus again on resume', () => {
            engine.start(TOUR);
            engine.pause();
            engine.next();
            engine.back();
            expect(engine.index()).toBe(0);
            const before = engine.focusRequest();
            engine.resume();
            expect(engine.focusRequest()).toBeGreaterThan(before);
        });

        it('ignores pause, resume and finish for a tour that is not running', () => {
            engine.pause();
            engine.resume();
            engine.finish();
            engine.next();
            expect(calls).toEqual([]);
            expect(engine.lastEnd()).toBeNull();
        });
    });

    describe('finish and stop', () => {
        it('records COMPLETE, ends the run and says so in a toast once the card is gone', () => {
            engine.start(TOUR);
            engine.next();
            calls.length = 0;
            engine.finish();
            expect(calls).toEqual([['COMPLETE', {}]]);
            expect(engine.active()).toBe(false);
            expect(engine.lastEnd()).toEqual({ definitionId: 'sample', reason: 'finished' });
            expect(toasts.echo).not.toHaveBeenCalled();
            settle();
            expect(toasts.echo).toHaveBeenCalledWith('Знакомство завершено. Пройти его снова можно в профиле.');
        });

        it('can finish from the pause plaque', () => {
            engine.start(TOUR);
            engine.pause();
            calls.length = 0;
            engine.finish();
            expect(calls).toEqual([['COMPLETE', {}]]);
            expect(engine.paused()).toBe(false);
        });

        it('stops without recording anything and without a toast', () => {
            engine.start(TOUR);
            engine.stop();
            settle();
            expect(calls).toEqual([]);
            expect(toasts.echo).not.toHaveBeenCalled();
            expect(engine.lastEnd()).toEqual({ definitionId: 'sample', reason: 'stopped' });
            engine.stop();
        });

        it('forgets the previous end when a new run starts', () => {
            engine.start(TOUR);
            engine.stop();
            engine.start(TOUR);
            expect(engine.lastEnd()).toBeNull();
        });

        it('never throws for a persistence that fails', async () => {
            persistence.act.mockRejectedValue(new Error('offline'));
            engine.start(TOUR);
            engine.next();
            engine.pause();
            engine.finish();
            await vi.advanceTimersByTimeAsync(0);
            expect(engine.active()).toBe(false);
        });
    });

    describe('action gate', () => {
        it('moves on when the target component emits the step\'s event, not before and not for another event', () => {
            engine.start(TOUR, 'sample.exercises');
            engine.next();
            expect(engine.step()?.key).toBe('sample.study');
            events.emit('deck.copied');
            expect(engine.step()?.key).toBe('sample.study');
            events.emit('study.started');
            expect(engine.active()).toBe(false);
            expect(engine.lastEnd()?.reason).toBe('completed');
        });

        it('advances an action gate in the middle of a tour', () => {
            const tour: TourDefinition = { ...TOUR, steps: [step({ key: 'community.add', anchor: 'community.add', gate: { action: 'deck.copied', label: 'Добавить к себе' } }),
                step({ key: 'community.study', anchor: 'hub.start' })], branch: 'COMMUNITY' };
            engine.start(tour);
            events.emit('deck.copied');
            expect(engine.step()?.key).toBe('community.study');
            expect(calls).toEqual([['STEP', { stepKey: 'community.study', branch: 'COMMUNITY' }]]);
        });

        it('does not listen on an information step, and stops listening after the step or the tour ends', () => {
            engine.start(TOUR);
            events.emit('study.started');
            expect(engine.index()).toBe(0);
            engine.start(TOUR, 'sample.study');
            engine.back();
            events.emit('study.started');
            expect(engine.index()).toBe(2);
            engine.next();
            engine.stop();
            events.emit('study.started');
            expect(engine.active()).toBe(false);
            expect(calls.filter(([action]) => action === 'COMPLETE')).toEqual([]);
        });

        it('remembers an event that arrived during the pause and moves on when the tour resumes', () => {
            engine.start(TOUR, 'sample.exercises');
            engine.next();
            engine.pause();
            events.emit('study.started');
            expect(engine.active()).toBe(true);
            expect(engine.paused()).toBe(true);
            engine.resume();
            expect(engine.active()).toBe(false);
            expect(calls.map(([action]) => action)).toEqual(['STEP', 'PAUSE', 'RESUME', 'COMPLETE']);
        });
    });

    describe('looking for the anchor', () => {
        it('is ready as soon as the anchor is registered', () => {
            engine.start(TOUR);
            expect(engine.phase()).toBe('locating');
            mount('hub.header');
            settle();
            expect(engine.phase()).toBe('ready');
            expect(engine.target()).toBe(elements.get('hub.header'));
        });

        it('is ready at once when the anchor was already there', () => {
            mount('hub.header');
            engine.start(TOUR);
            settle();
            expect(engine.phase()).toBe('ready');
        });

        it('shows the text variant after four seconds without the anchor, and lets the learner go on', () => {
            engine.start(TOUR);
            settle();
            vi.advanceTimersByTime(ANCHOR_WAIT_MS - 1);
            expect(engine.phase()).toBe('locating');
            vi.advanceTimersByTime(1);
            expect(engine.phase()).toBe('missing');
            engine.next();
            expect(engine.index()).toBe(1);
            expect(engine.phase()).toBe('locating');
        });

        it('waits four seconds again for every step', () => {
            engine.start(TOUR);
            settle();
            vi.advanceTimersByTime(3_000);
            engine.next();
            settle();
            vi.advanceTimersByTime(3_000);
            expect(engine.phase()).toBe('locating');
            vi.advanceTimersByTime(1_000);
            expect(engine.phase()).toBe('missing');
        });

        it('leaves the text variant when the anchor turns up late', () => {
            engine.start(TOUR);
            settle();
            vi.advanceTimersByTime(ANCHOR_WAIT_MS);
            expect(engine.phase()).toBe('missing');
            mount('hub.header');
            settle();
            expect(engine.phase()).toBe('ready');
        });

        it('looks for it again, for four seconds, when the target disappears under a step', () => {
            const unmount = mount('hub.header');
            engine.start(TOUR);
            settle();
            unmount();
            settle();
            expect(engine.phase()).toBe('locating');
            vi.advanceTimersByTime(ANCHOR_WAIT_MS);
            expect(engine.phase()).toBe('missing');
        });

        it('does not run the timer while paused or while the page is being prepared', () => {
            engine.start(TOUR);
            settle();
            engine.pause();
            settle();
            vi.advanceTimersByTime(10_000);
            expect(engine.phase()).toBe('locating');
            engine.resume();
            settle();
            engine.setPreparing(true);
            settle();
            vi.advanceTimersByTime(10_000);
            expect(engine.preparing()).toBe(true);
            expect(engine.phase()).toBe('locating');
            engine.setPreparing(false);
            settle();
            vi.advanceTimersByTime(ANCHOR_WAIT_MS);
            expect(engine.phase()).toBe('missing');
        });

        it('stops the timer when the tour ends', () => {
            engine.start(TOUR);
            settle();
            engine.stop();
            vi.advanceTimersByTime(10_000);
            expect(engine.phase()).toBe('locating');
            expect(vi.getTimerCount()).toBe(0);
        });
    });

    describe('what the onboarding flow asks of it', () => {
        const FINALE: TourStep[] = [
            { key: 'finale.ai', anchor: '', title: '', body: paragraphs(''), gate: 'next', external: true },
            { key: 'finale.done', anchor: '', title: '', body: paragraphs(''), gate: 'next', external: true }
        ];
        const STUDY: TourStep = step({ key: 'sample.study', anchor: 'hub.start', gate: { action: 'study.started', label: 'Начать занятие', pauses: true } });
        const WITH_FINALE: TourDefinition = { id: 'sample', branch: 'SAMPLE', steps: [TOUR.steps[0], STUDY, ...FINALE] };

        it('counts only the steps the learner counts: «Шаг N из M» leaves the external finale out', () => {
            engine.start(WITH_FINALE);
            expect(engine.total()).toBe(2);
        });

        it('asks a route function when the step is entered and stays where it is for null', () => {
            const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
            let deck: string | null = null;
            engine.start({ id: 'r', branch: null, steps: [step({ key: 'finale.ai', anchor: 'hub.start' }),
                step({ key: 'finale.done', anchor: 'hub.start', route: () => deck === null ? null : `/decks/${deck}` })] });
            deck = 'abc';
            engine.next();
            expect(navigate).toHaveBeenCalledWith('/decks/abc');
            navigate.mockClear();
            deck = null;
            engine.back();
            engine.next();
            expect(navigate).not.toHaveBeenCalled();
        });

        it('pauses, instead of moving on, when the event of a pausing gate arrives, and records the pause on that step', () => {
            engine.start(WITH_FINALE, 'sample.study');
            events.emit('study.started');
            expect(engine.paused()).toBe(true);
            expect(engine.step()?.key).toBe('sample.study');
            expect(calls).toEqual([['PAUSE', { stepKey: 'sample.study' }]]);
        });

        it('continues a paused tour at another step and records RESUME there, while the plaque still returns to the paused step', () => {
            engine.start(WITH_FINALE, 'sample.study');
            events.emit('study.started');
            engine.resume();
            expect(engine.step()?.key).toBe('sample.study');
            events.emit('study.started');
            calls.length = 0;
            engine.resumeAt('finale.ai');
            expect(engine.paused()).toBe(false);
            expect(engine.step()?.key).toBe('finale.ai');
            expect(calls).toEqual([['RESUME', { stepKey: 'finale.ai' }]]);
            engine.next();
            expect(engine.step()?.key).toBe('finale.done');
            expect(calls.at(-1)).toEqual(['STEP', { stepKey: 'finale.done' }]);
            engine.next();
            expect(engine.lastEnd()?.reason).toBe('completed');
        });

        it('resumeAt does nothing for a tour that is not paused or a step that is not in it', () => {
            engine.start(WITH_FINALE);
            engine.resumeAt('finale.ai');
            expect(engine.step()?.key).toBe('sample.hub');
            engine.pause();
            engine.resumeAt('own.details');
            expect(engine.paused()).toBe(true);
        });

        it('starts folded into the plaque, recording nothing, when another device paused the journey', () => {
            engine.start(WITH_FINALE, 'sample.study', { paused: true });
            expect(engine.active()).toBe(true);
            expect(engine.paused()).toBe(true);
            expect(calls).toEqual([]);
            engine.resume();
            expect(calls).toEqual([['RESUME', { stepKey: 'sample.study' }]]);
        });

        it('hands the payload of an event to every listener, and the engine ignores it', () => {
            const seen: unknown[] = [];
            const stop = events.listen((event, payload) => { seen.push([event, payload]); });
            events.emit('deck.created', { deckId: 'd1' });
            stop();
            events.emit('deck.created', { deckId: 'd2' });
            expect(seen).toEqual([['deck.created', { deckId: 'd1' }]]);
        });
    });

    describe('a tour that starts paused, and a tour that continues', () => {
        const FIXED: TourDefinition = { id: 'fixed', branch: 'COMMUNITY', steps: [
            step({ key: 'community.search', anchor: 'community.search', route: '/community', gate: { action: 'deck.opened', label: null } }),
            step({ key: 'community.deck', anchor: 'community.deck' }),
            step({ key: 'own.details', anchor: 'own.details', route: '/decks/new', gate: { action: 'deck.created', label: 'Создать колоду' } })
        ] };

        it('does not take the learner anywhere when it starts paused, even from a step with a fixed route', async () => {
            const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
            engine.start(FIXED, 'community.search', { paused: true });
            expect(engine.paused()).toBe(true);
            expect(navigate).not.toHaveBeenCalled();
            engine.start(FIXED, 'own.details', { paused: true });
            expect(navigate).not.toHaveBeenCalled();
            expect(calls).toEqual([]);
        });

        it('takes the learner to the place of the step when the plaque continues it', () => {
            const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
            engine.start(FIXED, 'own.details', { paused: true });
            engine.resume();
            expect(navigate).toHaveBeenCalledTimes(1);
            expect(navigate).toHaveBeenCalledWith('/decks/new');
            expect(engine.paused()).toBe(false);
        });

        it('takes the learner to the place of the step a paused tour is continued at', () => {
            const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
            engine.start(FIXED, 'community.search', { paused: true });
            engine.resumeAt('own.details');
            expect(navigate).toHaveBeenCalledWith('/decks/new');
            expect(engine.step()?.key).toBe('own.details');
            expect(calls).toEqual([['RESUME', { stepKey: 'own.details' }]]);
        });

        it('counts a request to pause only when the learner folded the tour, not when it started paused', () => {
            engine.start(FIXED, 'community.search', { paused: true });
            expect(engine.pauseRequest()).toBe(0);
            engine.resume();
            engine.pause();
            expect(engine.pauseRequest()).toBe(1);
        });
    });

    describe('events and the way back', () => {
        it('tells the page whether a gate took the event, so exactly one side navigates', () => {
            const GATED: TourDefinition = { id: 'g2', branch: 'OWN', steps: [
                step({ key: 'own.details', anchor: 'a', gate: { action: 'deck.created', label: 'Создать колоду' } }),
                step({ key: 'own.material', anchor: 'b', route: '/decks/d/materials/new', gate: 'next' })
            ] };
            expect(events.emit('deck.created')).toBe(false);
            engine.start(GATED);
            expect(events.emit('study.started')).toBe(false);
            expect(events.emit('deck.created')).toBe(true);
            expect(engine.step()?.key).toBe('own.material');
        });

        it('takes the event only when the tour is about to go to the next place itself', () => {
            const WITH_PLACES: TourDefinition = { id: 'p', branch: 'OWN', steps: [
                step({ key: 'own.details', anchor: 'a', route: '/decks/new', gate: { action: 'deck.created', label: 'Создать колоду' } }),
                step({ key: 'own.material', anchor: 'b', route: '/decks/d/materials/new', gate: { action: 'material.published', label: null } }),
                step({ key: 'own.exercise', anchor: 'c', gate: { action: 'exercise.published', label: null } }),
                step({ key: 'own.study', anchor: 'd', gate: { action: 'study.started', label: 'Учить', pauses: true } })
            ] };
            const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
            engine.start(WITH_PLACES);
            navigate.mockClear();
            // Paused: the tour goes nowhere until it is continued, so the page keeps its own way.
            engine.pause();
            expect(events.emit('deck.created', { deckId: 'd' })).toBe(false);
            expect(navigate).not.toHaveBeenCalled();
            // Continued: it moves on and goes to the place of the step the gate was waiting to open.
            engine.resume();
            expect(engine.step()?.key).toBe('own.material');
            expect(navigate).toHaveBeenCalledWith('/decks/d/materials/new');
            // The next step has no place of its own: the page goes where it always goes.
            expect(events.emit('material.published')).toBe(false);
            expect(engine.step()?.key).toBe('own.exercise');
            // A gate that pauses the tour (the study session) leaves the page alone too.
            expect(events.emit('exercise.published')).toBe(false);
            expect(events.emit('study.started')).toBe(false);
            expect(engine.paused()).toBe(true);
        });

        it('offers no way back to a step before a gate that was passed by an action: it would be done a second time', () => {
            const GATED: TourDefinition = { id: 'g', branch: 'OWN', steps: [
                step({ key: 'own.details', anchor: 'a', gate: { action: 'deck.created', label: 'Создать колоду' } }),
                step({ key: 'own.material', anchor: 'b', gate: { action: 'material.published', label: null } }),
                step({ key: 'own.exercise', anchor: 'c' })
            ] };
            engine.start(GATED);
            expect(engine.canGoBack()).toBe(false);
            events.emit('deck.created', { deckId: 'd' });
            expect(engine.step()?.key).toBe('own.material');
            expect(engine.canGoBack()).toBe(false);
            calls.length = 0;
            engine.back();
            expect(engine.step()?.key).toBe('own.material');
            expect(calls).toEqual([]);
            events.emit('material.published');
            expect(engine.step()?.key).toBe('own.exercise');
            expect(engine.canGoBack()).toBe(false);
            engine.start(TOUR, 'sample.material');
            expect(engine.canGoBack()).toBe(true);
        });
    });

    describe('when the server refuses a command', () => {
        const refusal = (journey: Partial<OnboardingView['journey'] & object>, enabled = true): JourneyResult =>
            ({ status: 'refused', view: { ...(contract.views.offered as OnboardingView), enabled, journey: { ...(contract.views.paused.journey as object), ...journey } as never } });
        const flush = async (): Promise<void> => { await Promise.resolve(); await Promise.resolve(); TestBed.tick(); };

        it('follows the step the re-read journey is on instead of carrying on as if its own step was taken', async () => {
            persistence.act.mockResolvedValueOnce(refusal({ state: 'IN_PROGRESS', stepKey: 'sample.study' }));
            engine.start(TOUR);
            engine.next();
            await flush();
            expect(engine.step()?.key).toBe('sample.study');
            expect(engine.paused()).toBe(false);
        });

        it('folds into the plaque when the journey was paused somewhere else', async () => {
            persistence.act.mockResolvedValueOnce(refusal({ state: 'PAUSED', stepKey: 'sample.material' }));
            engine.start(TOUR);
            engine.next();
            await flush();
            expect(engine.step()?.key).toBe('sample.material');
            expect(engine.paused()).toBe(true);
        });

        it.each([['COMPLETED', true], ['SKIPPED', true], ['IN_PROGRESS', false]] as const)('stops for a journey that is %s (or a feature that went off)', async (state, enabled) => {
            persistence.act.mockResolvedValueOnce(refusal({ state, stepKey: null }, enabled));
            engine.start(TOUR);
            engine.next();
            await flush();
            expect(engine.active()).toBe(false);
        });

        it('goes nowhere when it folds into the plaque at a step with a place of its own', async () => {
            const FIXED2: TourDefinition = { id: 'f2', branch: 'OWN', steps: [
                step({ key: 'own.details', anchor: 'a', route: '/decks/new' }),
                step({ key: 'own.material', anchor: 'b', route: '/decks/d/materials/new' })
            ] };
            const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
            persistence.act.mockResolvedValueOnce(refusal({ state: 'PAUSED', stepKey: 'own.material' }));
            engine.start(FIXED2);
            navigate.mockClear();
            // Another device paused the journey further on: the answer to our PAUSE says so.
            engine.pause();
            await flush();
            expect(engine.step()?.key).toBe('own.material');
            expect(engine.paused()).toBe(true);
            expect(navigate).not.toHaveBeenCalled();
            engine.resume();
            expect(navigate).toHaveBeenCalledWith('/decks/d/materials/new');
        });

        it('does not follow an answer that arrives after the tour was replaced', async () => {
            persistence.act.mockResolvedValueOnce(refusal({ state: 'IN_PROGRESS', stepKey: 'sample.study' }));
            engine.start(TOUR);
            engine.next();
            engine.start({ ...TOUR, id: 'other' });
            await flush();
            expect(engine.step()?.key).toBe('sample.hub');
        });
    });
});
