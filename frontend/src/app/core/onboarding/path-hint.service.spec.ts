import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { spyObj } from '../../../testing/mocks';
import { AuthService } from '../../auth.service';
import { QuietZone } from '../notifications/quiet-zone';
import { AttentionCoordinator } from './attention-coordinator';
import { JourneyStore } from './journey.store';
import { HINT_ANCHOR_WAIT_MS, PathHint } from './path-hint.service';
import { TourAnchors } from './tour-anchors';
import { TourEngine } from './tour-engine.service';

const REQUEST = { title: 'Заведите первую колоду', text: 'Нажмите «Новая колода», чтобы завести свою.', anchor: 'nav.newDeck' };

describe('PathHint (the in-place hint of «Путь»)', () => {
    let hint: PathHint;
    let anchors: TourAnchors;
    let router: Router;
    const tourActive = signal(false);

    const settle = async (ms = 0): Promise<void> => { TestBed.tick(); await vi.advanceTimersByTimeAsync(ms); TestBed.tick(); };

    beforeEach(async () => {
        vi.useFakeTimers();
        tourActive.set(false);
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '**', children: [] }]),
                { provide: TourEngine, useValue: spyObj<TourEngine>({ active: tourActive, paused: signal(false) }) },
                { provide: JourneyStore, useValue: { journey: signal(null), view: signal(null), error: signal(null) } },
                { provide: AuthService, useValue: { status: signal('authenticated') } }]
        });
        hint = TestBed.inject(PathHint);
        anchors = TestBed.inject(TourAnchors);
        router = TestBed.inject(Router);
        await router.navigateByUrl('/decks');
        TestBed.tick();
    });
    afterEach(() => vi.useRealTimers());

    it('draws nothing before the navigation ended, even when the anchor is already there (the masthead link is)', async () => {
        anchors.register('nav.newDeck', document.createElement('a'));
        expect(hint.begin(REQUEST)).toBe(true);
        await settle();
        expect(hint.phase()).toBe('locating');
        expect(hint.visible()).toBe(false);
        expect(hint.active()).toBe(true);
        expect(hint.awaitingFocus()).toBe(true);
    });

    it('stands next to the anchor once the page is the hint\'s and the anchor is on it, and tells the coordinator', async () => {
        const link = document.createElement('a');
        anchors.register('nav.newDeck', link);
        hint.begin(REQUEST);
        hint.arm();
        await settle();
        expect(hint.phase()).toBe('ready');
        expect(hint.target()).toBe(link);
        expect(hint.visible()).toBe(true);
        expect(TestBed.inject(AttentionCoordinator).visible()).toEqual(['hint']);
        hint.focused();
        expect(hint.awaitingFocus()).toBe(false);
    });

    it('waits for an anchor that appears within four seconds', async () => {
        hint.begin(REQUEST);
        hint.arm();
        await settle(HINT_ANCHOR_WAIT_MS - 1);
        expect(hint.phase()).toBe('locating');
        anchors.register('nav.newDeck', document.createElement('a'));
        await settle();
        expect(hint.phase()).toBe('ready');
        await settle(HINT_ANCHOR_WAIT_MS);
        expect(hint.phase()).toBe('ready');
    });

    it('says its sentence at the top of the page when the anchor does not appear in four seconds (no dead end)', async () => {
        hint.begin(REQUEST);
        hint.arm();
        await settle(HINT_ANCHOR_WAIT_MS);
        expect(hint.phase()).toBe('missing');
        expect(hint.visible()).toBe(true);
        expect(hint.request()).toEqual(REQUEST);
        // An anchor that comes later is still taken.
        anchors.register('nav.newDeck', document.createElement('a'));
        await settle();
        expect(hint.phase()).toBe('ready');
    });

    it.each([
        ['a study answer is open', () => TestBed.inject(QuietZone).set(true)],
        ['the tour is on the screen', () => tourActive.set(true)],
        ['the first entry is open', () => TestBed.inject(AttentionCoordinator).report('first-entry', true)]
    ])('does not start while %s', (_name, hold) => {
        hold();
        expect(hint.begin(REQUEST)).toBe(false);
        expect(hint.active()).toBe(false);
        expect(hint.awaitingFocus()).toBe(false);
        hint.arm();
        expect(hint.phase()).toBeNull();
    });

    it('ends with dismiss, and a new request replaces the old one', async () => {
        anchors.register('nav.newDeck', document.createElement('a'));
        hint.begin(REQUEST);
        const first = hint.id();
        hint.begin({ ...REQUEST, title: 'Другое' });
        expect(hint.id()).toBeGreaterThan(first);
        expect(hint.request()?.title).toBe('Другое');
        hint.arm();
        await settle();
        expect(hint.visible()).toBe(true);
        hint.dismiss();
        await settle();
        expect(hint.active()).toBe(false);
        expect(hint.request()).toBeNull();
        expect(hint.awaitingFocus()).toBe(false);
        expect(TestBed.inject(AttentionCoordinator).visible()).toEqual([]);
        // A timer of the old hint does not touch the new state.
        await settle(HINT_ANCHOR_WAIT_MS * 2);
        expect(hint.phase()).toBeNull();
    });

    it('is over when the learner goes to another page', async () => {
        anchors.register('nav.newDeck', document.createElement('a'));
        hint.begin(REQUEST);
        hint.arm();
        await settle();
        expect(hint.visible()).toBe(true);
        await router.navigateByUrl('/community');
        await settle();
        expect(hint.active()).toBe(false);
    });

    it('is over when the anchor leaves the page after it was there', async () => {
        const unregister = anchors.register('nav.newDeck', document.createElement('a'));
        hint.begin(REQUEST);
        hint.arm();
        await settle();
        expect(hint.phase()).toBe('ready');
        unregister();
        await settle();
        expect(hint.active()).toBe(false);
    });

    it('arms once: a second arm does not restart the wait', async () => {
        hint.begin(REQUEST);
        hint.arm();
        await settle(HINT_ANCHOR_WAIT_MS - 100);
        hint.arm();
        await settle(100);
        expect(hint.phase()).toBe('missing');
    });
});
