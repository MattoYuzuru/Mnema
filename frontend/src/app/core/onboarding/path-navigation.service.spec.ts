import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { spyObj } from '../../../testing/mocks';
import { AuthService } from '../../auth.service';
import { QuestLauncher } from '../../features/onboarding/quest-launcher.service';
import { OwnDecksApiService } from '../../features/own-decks/own-decks-api.service';
import { AttentionCoordinator } from './attention-coordinator';
import { JourneyStore } from './journey.store';
import { OnboardingView } from './onboarding.models';
import { PathHint } from './path-hint.service';
import { isQuestRoute } from './path-places';
import { PathNavigation } from './path-navigation.service';
import { MILESTONE_KEYS, milestoneDefinition } from './path.models';
import { TourAnchors } from './tour-anchors';
import { TourEngine } from './tour-engine.service';
import journey from '../../../../../contracts/onboarding/journey.json';

const SAMPLE = journey.views.sampleReady.sampleDeck.deckId as string;
const NEW_OWN = '22222222-2222-4222-8222-222222222222';
const OLD_OWN = '33333333-3333-4333-8333-333333333333';
const deck = (deckId: string): { deckId: string } => ({ deckId });

describe('PathNavigation (a chevron of «Путь»)', () => {
    let navigation: PathNavigation;
    let hint: PathHint;
    let navigate: ReturnType<typeof vi.fn>;
    let list: ReturnType<typeof vi.fn>;
    let open: ReturnType<typeof vi.fn>;
    const view = signal<OnboardingView | null>(null);
    const tourActive = signal(false);

    beforeEach(() => {
        tourActive.set(false);
        view.set(journey.views.sampleReady as OnboardingView);
        navigate = vi.fn(() => Promise.resolve(true));
        open = vi.fn(() => Promise.resolve());
        list = vi.fn(() => of({ items: [deck(SAMPLE), deck(NEW_OWN), deck(OLD_OWN)], nextCursor: null }));
        TestBed.configureTestingModule({
            providers: [provideRouter([]),
                { provide: OwnDecksApiService, useValue: spyObj<OwnDecksApiService>({ list }) }, { provide: QuestLauncher, useValue: { open } },
                { provide: TourEngine, useValue: spyObj<TourEngine>({ active: tourActive, paused: signal(false) }) },
                { provide: JourneyStore, useValue: { view, journey: signal(null), error: signal(null) } },
                { provide: AuthService, useValue: { status: signal('authenticated') } }]
        });
        navigation = TestBed.inject(PathNavigation);
        hint = TestBed.inject(PathHint);
        vi.spyOn(TestBed.inject(Router), 'navigate').mockImplementation(navigate as never);
    });

    it('opens the place of a milestone and begins the hint there once the navigation ended', async () => {
        await navigation.go('GOAL_TOLD');
        expect(navigate).toHaveBeenCalledExactlyOnceWith(['/profile'], {});
        expect(list).not.toHaveBeenCalled();
        expect(hint.request()).toEqual({ title: 'Расскажите о цели', text: 'Расскажите, для чего вам Mnema, — подсказки подстроятся под цель.', anchor: 'profile.goal' });
        expect(hint.phase()).toBe('locating');
        TestBed.inject(TourAnchors).register('profile.goal', document.createElement('form'));
        TestBed.tick();
        expect(hint.phase()).toBe('ready');
    });

    it('takes the study to the newest deck of any kind (the sample copy counts)', async () => {
        await navigation.go('FIRST_SESSION');
        expect(navigate).toHaveBeenCalledExactlyOnceWith(['/decks', SAMPLE], {});
        expect(hint.request()?.anchor).toBe('hub.start');
    });

    it('takes the authoring milestones to the newest deck of the learner\'s own work, not the sample copy', async () => {
        for (const [key, anchor] of [['OWN_MATERIAL', 'hub.addMaterial'], ['OWN_EXERCISES', 'hub.materials'], ['CAPTURE_NOTE', 'hub.capture']] as const) {
            navigate.mockClear();
            await navigation.go(key);
            expect(navigate, key).toHaveBeenCalledExactlyOnceWith(['/decks', NEW_OWN], {});
            expect(hint.request()?.anchor, key).toBe(anchor);
        }
    });

    it('sends a learner with no deck of their own to the list with the deck hint', async () => {
        list.mockReturnValue(of({ items: [deck(SAMPLE)], nextCursor: null }));
        await navigation.go('OWN_MATERIAL');
        expect(navigate).toHaveBeenLastCalledWith(['/decks'], {});
        expect(hint.request()).toMatchObject({ title: 'Напишите свой материал', text: 'Нажмите «Новая колода», чтобы завести свою.', anchor: 'nav.newDeck' });
        list.mockReturnValue(of({ items: [], nextCursor: null }));
        await navigation.go('FIRST_SESSION');
        expect(navigate).toHaveBeenLastCalledWith(['/decks'], {});
    });

    it('goes to the list all the same when the decks cannot be read', async () => {
        list.mockReturnValue(throwError(() => new Error('down')));
        await navigation.go('FIRST_SESSION');
        expect(navigate).toHaveBeenCalledExactlyOnceWith(['/decks'], {});
        expect(hint.request()?.anchor).toBe('nav.newDeck');
    });

    it('does not look the decks up for a place that does not depend on one', async () => {
        for (const key of ['GOAL_TOLD', 'FIRST_DECK', 'COMMUNITY_COPY']) await navigation.go(key as typeof MILESTONE_KEYS[number]);
        expect(list).not.toHaveBeenCalled();
        expect(navigate.mock.calls.map(call => call[0])).toEqual([['/profile'], ['/decks'], ['/community']]);
    });

    it('opens the chooser of the quest for a side quest, and neither navigates nor looks the decks up itself', async () => {
        for (const [key, route] of [['MEDIA_IN_MATERIAL', 'QUEST_MEDIA'], ['AI_EXPLANATION', 'QUEST_EXPLAIN'], ['VOICE_ANSWER', 'QUEST_VOICE'], ['WORKSHOP_APPROVED', 'QUEST_WORKSHOP']] as const) {
            await navigation.go(key);
            expect(open, key).toHaveBeenLastCalledWith(route, key);
        }
        expect(open).toHaveBeenCalledTimes(4);
        expect(navigate).not.toHaveBeenCalled();
        expect(list).not.toHaveBeenCalled();
        expect(hint.active()).toBe(false);
    });

    it('does nothing for a milestone without a place (the rhythm, hidden in this version)', async () => {
        await navigation.go('RHYTHM_WEEK');
        expect(navigate).not.toHaveBeenCalled();
        expect(hint.active()).toBe(false);
    });

    it('has a place or an explicit nothing for every milestone of the registry', async () => {
        for (const key of MILESTONE_KEYS) {
            navigate.mockClear();
            await navigation.go(key);
            const route = milestoneDefinition(key).route;
            // A side quest opens its chooser instead of navigating; the rhythm has no place yet.
            expect(navigate.mock.calls.length, `${key} → ${route}`).toBe(route === 'PROFILE_RHYTHM' || isQuestRoute(route) ? 0 : 1);
        }
    });

    it('goes to the place without the hint while a louder surface is on the screen', async () => {
        TestBed.inject(AttentionCoordinator).report('first-entry', true);
        await navigation.go('FIRST_DECK');
        expect(navigate).toHaveBeenCalledExactlyOnceWith(['/decks'], {});
        expect(hint.active()).toBe(false);
    });

    it('drops the hint when the navigation did not happen (a guard said no)', async () => {
        navigate.mockResolvedValueOnce(false);
        await navigation.go('FIRST_DECK');
        expect(hint.active()).toBe(false);
    });

    it('takes one chevron at a time', async () => {
        let release: (value: boolean) => void = () => undefined;
        navigate.mockReturnValueOnce(new Promise<boolean>(resolve => { release = resolve; }));
        const first = navigation.go('FIRST_DECK');
        expect(navigation.busy()).toBe(true);
        await navigation.go('GOAL_TOLD');
        expect(navigate).toHaveBeenCalledTimes(1);
        release(true);
        await first;
        expect(navigation.busy()).toBe(false);
    });
});
