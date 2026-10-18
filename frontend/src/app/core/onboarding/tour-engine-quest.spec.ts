import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { spyObj } from '../../../testing/mocks';
import { ToastService } from '../notifications/toast.service';
import { ActionOptions, JourneyAction } from './onboarding.models';
import { ANCHOR_WAIT_MS, TOUR_PERSISTENCE, TourEngine } from './tour-engine.service';
import { TourAnchors } from './tour-anchors';
import { TourEvents } from './tour-events';
import { paragraphs } from './tour-rich-text';
import { TourDefinition, TourStep } from './tour.models';

const step = (overrides: Partial<TourStep> & Pick<TourStep, 'key' | 'anchor'>): TourStep =>
    ({ title: `Шаг ${overrides.key}`, body: paragraphs('Текст.'), gate: 'next', ...overrides });

const QUEST: TourDefinition = {
    id: 'quest.demo', branch: null, quest: true,
    steps: [
        step({ key: 'quest.demo.screen', anchor: '', screen: true }),
        step({ key: 'quest.demo.act', anchor: 'capture.mic', gate: { action: 'voice.transcribed', label: null } }),
        step({ key: 'quest.demo.study', anchor: 'hub.start', completes: 'study.ai-checked', gate: { action: 'study.started', label: 'Учить', pauses: true } })
    ]
};

/** A side quest runs on the engine of the main tour but is client-only: nothing of it reaches the journey (OB-304). */
describe('TourEngine: side quests', () => {
    let calls: [JourneyAction, ActionOptions | undefined][];
    let engine: TourEngine;
    let anchors: TourAnchors;
    let events: TourEvents;

    beforeEach(() => {
        vi.useFakeTimers();
        calls = [];
        const persistence = { act: vi.fn((action: JourneyAction, options?: ActionOptions) => { calls.push([action, options]); return Promise.resolve(null); }) };
        TestBed.configureTestingModule({
            providers: [provideRouter([]), { provide: TOUR_PERSISTENCE, useValue: persistence }, { provide: ToastService, useValue: spyObj<ToastService>({ echo: vi.fn() }) }]
        });
        engine = TestBed.inject(TourEngine);
        anchors = TestBed.inject(TourAnchors);
        events = TestBed.inject(TourEvents);
    });
    afterEach(() => vi.useRealTimers());

    it('records nothing for any move: step, pause, resume, leave, completion', () => {
        engine.start(QUEST);
        expect(engine.isQuest()).toBe(true);
        engine.next();
        engine.pause();
        engine.resume();
        engine.next();
        engine.pause();
        engine.resume();
        engine.stop();
        engine.start(QUEST, 'quest.demo.act');
        engine.next();
        engine.next();
        expect(calls).toEqual([]);
    });

    it('records the same moves for a tour of the journey, so the silence above is the quest\'s own', () => {
        engine.start({ id: 'sample', branch: 'SAMPLE', steps: [step({ key: 'sample.hub', anchor: 'hub.header' }), step({ key: 'sample.material', anchor: 'hub.material' })] });
        engine.next();
        engine.pause();
        engine.resume();
        engine.next();
        expect(calls.map(([action]) => action)).toEqual(['STEP', 'PAUSE', 'RESUME', 'COMPLETE']);
        expect(engine.isQuest()).toBe(false);
    });

    it('is not the journey\'s tour: it does not count as recording, so a switched-off feature stops it by the quest check', () => {
        engine.start(QUEST);
        expect(engine.recordsJourney()).toBe(false);
    });

    it('moves on when the gate event arrives, and folds at a pausing gate, recording nothing', () => {
        engine.start(QUEST, 'quest.demo.act');
        events.emit('voice.transcribed');
        expect(engine.step()?.key).toBe('quest.demo.study');
        events.emit('study.started');
        expect(engine.paused()).toBe(true);
        expect(calls).toEqual([]);
    });

    it('has a screen of its own ready at once, with no anchor to wait for', () => {
        engine.start(QUEST);
        TestBed.tick();
        expect(engine.target()).toBeNull();
        expect(engine.phase()).toBe('ready');
        vi.advanceTimersByTime(ANCHOR_WAIT_MS * 2);
        TestBed.tick();
        expect(engine.phase()).toBe('ready');
    });

    it('waits for the anchor of an ordinary quest step and shows its text variant when it does not come', () => {
        engine.start(QUEST, 'quest.demo.act');
        TestBed.tick();
        expect(engine.phase()).toBe('locating');
        vi.advanceTimersByTime(ANCHOR_WAIT_MS);
        TestBed.tick();
        expect(engine.phase()).toBe('missing');
        anchors.register('capture.mic', document.createElement('div'));
        TestBed.tick();
        expect(engine.phase()).toBe('ready');
    });

    it('ends when the thing was done where no card is drawn, even while it is folded (the study session)', () => {
        engine.start(QUEST, 'quest.demo.study');
        events.emit('study.started');
        expect(engine.paused()).toBe(true);
        events.emit('study.ai-checked');
        expect(engine.active()).toBe(false);
        expect(engine.lastEnd()).toEqual({ definitionId: 'quest.demo', reason: 'completed' });
        expect(calls).toEqual([]);
    });

    it('listens for the completing event only while its step is current', () => {
        engine.start(QUEST, 'quest.demo.act');
        events.emit('study.ai-checked');
        expect(engine.active()).toBe(true);
        engine.next();
        events.emit('study.ai-checked');
        expect(engine.active()).toBe(false);
    });

    it('leaves through stop with no record, and a later run does not hear the old quest', () => {
        engine.start(QUEST, 'quest.demo.study');
        engine.stop();
        expect(engine.lastEnd()).toEqual({ definitionId: 'quest.demo', reason: 'stopped' });
        events.emit('study.ai-checked');
        expect(engine.lastEnd()?.reason).toBe('stopped');
        expect(calls).toEqual([]);
    });
});
