import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import contract from '../../../../../contracts/onboarding/journey.json';
import { spyObj } from '../../../testing/mocks';
import { AuthService } from '../../auth.service';
import { ToastService } from '../../core/notifications/toast.service';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { ActionOptions, JourneyAction, OnboardingView } from '../../core/onboarding/onboarding.models';
import { TOUR_PERSISTENCE, TourEngine } from '../../core/onboarding/tour-engine.service';
import { TourEvents } from '../../core/onboarding/tour-events';
import { paragraphs } from '../../core/onboarding/tour-rich-text';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { ItemApiService } from '../authoring/item-api.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { DeckHubApiService } from '../own-decks/hub/deck-hub-api.service';
import { UsageApiService } from '../usage/usage-api.service';
import { plusUsage } from '../usage/usage-test-data';
import { UsageSnapshot } from '../usage/usage.models';
import { OnboardingFlow } from './onboarding-flow.service';
import { QuestLauncher } from './quest-launcher.service';

const SAMPLE = contract.views.sampleReady.sampleDeck.deckId as string;
const OWN = '22222222-2222-4222-8222-222222222222';
const OTHER = '44444444-4444-4444-8444-444444444444';
const deck = (deckId: string, title: string): { deckId: string; metadata: { title: string } } => ({ deckId, metadata: { title } });
const allOn: LearningCapabilities = {
    ...CAPABILITIES_UNAVAILABLE, aiAssessment: { available: true, reason: null }, speechToText: { available: true, reason: null }, aiGeneration: { available: true, reason: null }
};
const insights = (captures: number, freeResponse: number): unknown => ({ captures: { open: captures, oldestOpenCreatedAt: null }, exercisesByMechanic: { FREE_RESPONSE: freeResponse } });
const sampleView = (left: number): OnboardingView => ({ ...(contract.views.sampleReady as OnboardingView), sampleDeck: { ...(contract.views.sampleReady as OnboardingView).sampleDeck, freeChecksLeft: left } });

describe('QuestLauncher (the start of a side quest)', () => {
    let launcher: QuestLauncher;
    let engine: TourEngine;
    let calls: [JourneyAction, ActionOptions | undefined][];
    let echo: ReturnType<typeof vi.fn>;
    let load: ReturnType<typeof vi.fn>;
    let read: ReturnType<typeof vi.fn>;
    let usage: ReturnType<typeof vi.fn>;
    let list: ReturnType<typeof vi.fn>;
    let insightsApi: ReturnType<typeof vi.fn>;
    let items: ReturnType<typeof vi.fn>;
    const view = signal<OnboardingView | null>(null);
    const card = signal<string | null>(null);
    const pausedCard = signal<string | null>(null);

    beforeEach(() => {
        calls = [];
        echo = vi.fn();
        load = vi.fn(() => Promise.resolve(null));
        read = vi.fn(() => of(allOn));
        usage = vi.fn(() => of(plusUsage() as unknown as UsageSnapshot));
        list = vi.fn(() => of({ items: [deck(SAMPLE, 'Учебная колода'), deck(OWN, 'Японский'), deck(OTHER, 'Физика')], nextCursor: null }));
        insightsApi = vi.fn(() => of(insights(3, 2)));
        items = vi.fn(() => of({ items: [{ memberKey: 'gone', unavailable: true }, { memberKey: 'first-material', unavailable: false }] }));
        view.set(sampleView(2));
        card.set(null);
        pausedCard.set(null);
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '**', children: [] }]),
                { provide: TOUR_PERSISTENCE, useValue: { act: vi.fn((action: JourneyAction, options?: ActionOptions) => { calls.push([action, options]); return Promise.resolve(null); }) } },
                { provide: ToastService, useValue: spyObj<ToastService>({ echo }) },
                { provide: JourneyStore, useValue: { load, view, error: signal(null), journey: signal(null) } },
                { provide: OnboardingFlow, useValue: { card, pausedCard } },
                { provide: AuthService, useValue: { status: signal('authenticated') } },
                { provide: CapabilitiesApiService, useValue: spyObj<CapabilitiesApiService>({ read }) },
                { provide: UsageApiService, useValue: spyObj<UsageApiService>({ load: usage }) },
                { provide: OwnDecksApiService, useValue: spyObj<OwnDecksApiService>({ list }) },
                { provide: DeckHubApiService, useValue: spyObj<DeckHubApiService>({ insights: insightsApi }) },
                { provide: ItemApiService, useValue: spyObj<ItemApiService>({ list: items }) }]
        });
        launcher = TestBed.inject(QuestLauncher);
        engine = TestBed.inject(TourEngine);
        TestBed.tick();
    });

    describe('before the chooser', () => {
        it('says the main tour comes first, and reads nothing, while the tour or the first entry is on the screen', async () => {
            engine.start({ id: 'sample', branch: 'SAMPLE', steps: [{ key: 'sample.hub', anchor: 'hub.header', title: 'Т', body: paragraphs('Текст.'), gate: 'next' }] });
            await launcher.open('QUEST_MEDIA', 'MEDIA_IN_MATERIAL');
            expect(launcher.phase()).toBe('blocked');
            expect(launcher.notice()?.text).toBe('Сначала завершите знакомство: задание можно начать после него.');
            expect(read).not.toHaveBeenCalled();
            engine.stop();
            card.set('survey.goal');
            await launcher.open('QUEST_MEDIA', 'MEDIA_IN_MATERIAL');
            expect(launcher.phase()).toBe('blocked');
            card.set(null);
            pausedCard.set('sample.hub');
            await launcher.open('QUEST_MEDIA', 'MEDIA_IN_MATERIAL');
            expect(launcher.phase()).toBe('blocked');
            expect(engine.isQuest()).toBe(false);
        });

        it('does not offer a quest whose capability is off (fail closed, also when the read fails), and offers the picture that needs none', async () => {
            for (const route of ['QUEST_WORKSHOP', 'QUEST_VOICE', 'QUEST_EXPLAIN'] as const) {
                read.mockReturnValue(of(CAPABILITIES_UNAVAILABLE));
                await launcher.open(route, 'GOAL_TOLD');
                expect(launcher.phase(), route).toBe('blocked');
                expect(launcher.notice()).toEqual({ text: 'Эта возможность сейчас недоступна, поэтому задание не начать. Загляните позже.', actions: [] });
            }
            read.mockReturnValue(throwError(() => new Error('down')));
            await launcher.open('QUEST_EXPLAIN', 'AI_EXPLANATION');
            expect(launcher.phase()).toBe('blocked');
            await launcher.open('QUEST_MEDIA', 'MEDIA_IN_MATERIAL');
            expect(launcher.phase()).toBe('choose');
        });

        it.each([
            ['QUEST_WORKSHOP', 'WORKSHOP_APPROVED'], ['QUEST_EXPLAIN', 'AI_EXPLANATION']
        ] as const)('says the AI budget is gone before the first step of %s and offers «Тарифы»', async (route, milestone) => {
            const empty = plusUsage() as unknown as UsageSnapshot;
            usage.mockReturnValue(of({ ...empty, credits: { ...empty.credits, used: empty.credits.unlocked } }));
            view.set(sampleView(0));
            await launcher.open(route, milestone);
            expect(launcher.phase()).toBe('blocked');
            expect(launcher.notice()).toEqual({ text: 'Лимит ИИ для этого задания исчерпан. Он обновится по расписанию, а больше ИИ даёт тариф.',
                actions: [{ kind: 'link', label: 'Тарифы', commands: ['/plans'] }] });
        });

        it('offers the sample deck when the budget is gone but its free checks are not (the explanation), and starts the quest there', async () => {
            const empty = plusUsage() as unknown as UsageSnapshot;
            usage.mockReturnValue(of({ ...empty, fairUse: { ...empty.fairUse, assessment: { ...empty.fairUse.assessment, used: 50, limit: 50 } } }));
            await launcher.open('QUEST_EXPLAIN', 'AI_EXPLANATION');
            expect(launcher.phase()).toBe('blocked');
            expect(launcher.notice()?.text).toBe('Лимит ИИ для этого задания исчерпан, но в учебной колоде ещё есть бесплатные проверки: 2. Пройдём задание в ней.');
            const action = launcher.notice()!.actions[0];
            expect(action).toEqual({ kind: 'sample', label: 'Пройти в учебной колоде', deckId: SAMPLE });
            await launcher.act(action);
            expect(engine.definition()?.id).toBe('quest.explain');
            expect(engine.step()?.key).toBe('quest.explain.what');
            expect(launcher.request()).toBeNull();
        });

        it('offers the plans instead when the sample deck has no free check left, and for the voice and the Workshop, which the free checks do not cover', async () => {
            const empty = plusUsage() as unknown as UsageSnapshot;
            usage.mockReturnValue(of({ ...empty, fairUse: { ...empty.fairUse, stt: { ...empty.fairUse.stt, used: 60, limit: 60 }, assessment: { ...empty.fairUse.assessment, used: 50, limit: 50 } } }));
            await launcher.open('QUEST_VOICE', 'VOICE_ANSWER');
            expect(launcher.notice()?.actions).toEqual([{ kind: 'link', label: 'Тарифы', commands: ['/plans'] }]);
            view.set(sampleView(0));
            await launcher.open('QUEST_EXPLAIN', 'AI_EXPLANATION');
            expect(launcher.notice()?.actions).toEqual([{ kind: 'link', label: 'Тарифы', commands: ['/plans'] }]);
        });

        it('goes on when the usage cannot be read (the server refuses later, in place) and fails with «Повторить» when the decks cannot', async () => {
            usage.mockReturnValue(throwError(() => new Error('down')));
            await launcher.open('QUEST_WORKSHOP', 'WORKSHOP_APPROVED');
            expect(launcher.phase()).toBe('choose');
            list.mockReturnValueOnce(throwError(() => new Error('down')));
            await launcher.open('QUEST_WORKSHOP', 'WORKSHOP_APPROVED');
            expect(launcher.phase()).toBe('error');
            launcher.retry();
            await vi.waitFor(() => expect(launcher.phase()).toBe('choose'));
        });

        it('has no deck to choose for a learner with no deck of their own, and points to «Новая колода» (it never makes one)', async () => {
            list.mockReturnValue(of({ items: [deck(SAMPLE, 'Учебная колода')], nextCursor: null }));
            for (const route of ['QUEST_WORKSHOP', 'QUEST_MEDIA'] as const) {
                await launcher.open(route, 'GOAL_TOLD');
                expect(launcher.phase(), route).toBe('blocked');
                expect(launcher.notice()).toEqual({ text: 'У вас пока нет своей колоды. Заведите её, и задание станет доступно.',
                    actions: [{ kind: 'link', label: 'Новая колода', commands: ['/decks', 'new'] }] });
            }
            // With no decks at all and no sample copy either, even the explanation has nothing to run in.
            list.mockReturnValue(of({ items: [], nextCursor: null }));
            view.set(contract.views.offered as OnboardingView);
            await launcher.open('QUEST_EXPLAIN', 'AI_EXPLANATION');
            expect(launcher.phase()).toBe('blocked');
        });
    });

    describe('the choice of the deck', () => {
        it('lists the learner\'s own decks, the sample copy last and stamped, only where the quest may demonstrate on it', async () => {
            await launcher.open('QUEST_EXPLAIN', 'AI_EXPLANATION');
            expect(launcher.phase()).toBe('choose');
            expect(launcher.decks()).toEqual([
                { deckId: OWN, title: 'Японский', sample: false }, { deckId: OTHER, title: 'Физика', sample: false }, { deckId: SAMPLE, title: 'Учебная колода', sample: true }
            ]);
            expect(launcher.selected()).toBe(OWN);
            for (const route of ['QUEST_VOICE', 'QUEST_WORKSHOP', 'QUEST_MEDIA'] as const) {
                await launcher.open(route, 'GOAL_TOLD');
                expect(launcher.decks().map(option => option.deckId), route).toEqual([OWN, OTHER]);
            }
        });

        it('lets the learner choose another, and «Не сейчас» closes it with no quest and no request to the journey', async () => {
            await launcher.open('QUEST_MEDIA', 'MEDIA_IN_MATERIAL');
            launcher.choose(OTHER);
            expect(launcher.selected()).toBe(OTHER);
            launcher.close();
            expect(launcher.request()).toBeNull();
            expect(engine.active()).toBe(false);
            expect(calls).toEqual([]);
        });
    });

    describe('the check of the chosen deck, then the quest', () => {
        it('Workshop: needs a saved note; without one says so and points to the notes', async () => {
            await launcher.open('QUEST_WORKSHOP', 'WORKSHOP_APPROVED');
            insightsApi.mockReturnValue(of(insights(0, 2)));
            await launcher.start();
            expect(launcher.phase()).toBe('blocked');
            expect(launcher.notice()).toEqual({ text: 'В этой колоде нет заметок «На потом». Сохраните заметку, и Мастерская соберёт из неё материал.',
                actions: [{ kind: 'link', label: 'К заметкам', commands: ['/decks', OWN, 'capture'] }] });
            expect(engine.active()).toBe(false);
            insightsApi.mockReturnValue(of(insights(2, 0)));
            await launcher.open('QUEST_WORKSHOP', 'WORKSHOP_APPROVED');
            await launcher.start();
            expect(engine.definition()?.id).toBe('quest.workshop');
            expect(engine.definition()?.steps[0].route).toBe(`/decks/${OWN}/capture`);
        });

        it('explanation: needs a free-response exercise; without one offers the sample deck (free checks left) or says how to add one', async () => {
            insightsApi.mockReturnValue(of(insights(0, 0)));
            await launcher.open('QUEST_EXPLAIN', 'AI_EXPLANATION');
            await launcher.start();
            expect(launcher.phase()).toBe('blocked');
            expect(launcher.notice()?.text).toBe('В этой колоде нет заданий со свободным ответом, а своими словами отвечают только на такие. Добавьте такое задание или возьмите учебную колоду.');
            expect(launcher.notice()?.actions).toEqual([
                { kind: 'sample', label: 'Пройти в учебной колоде', deckId: SAMPLE }, { kind: 'link', label: 'К колоде', commands: ['/decks', OWN] }
            ]);
            view.set(sampleView(0));
            await launcher.open('QUEST_EXPLAIN', 'AI_EXPLANATION');
            await launcher.start();
            expect(launcher.notice()?.actions).toEqual([{ kind: 'link', label: 'К колоде', commands: ['/decks', OWN] }]);
            insightsApi.mockReturnValue(of(insights(0, 4)));
            await launcher.open('QUEST_EXPLAIN', 'AI_EXPLANATION');
            await launcher.start();
            expect(engine.definition()?.id).toBe('quest.explain');
        });

        it('picture: needs a material to open; opens the first one that can be read, and says so when the deck has none', async () => {
            await launcher.open('QUEST_MEDIA', 'MEDIA_IN_MATERIAL');
            await launcher.start();
            expect(engine.definition()?.id).toBe('quest.media');
            expect(engine.definition()?.steps[0].route).toBe(`/decks/${OWN}/materials/first-material/edit`);
            engine.stop();
            items.mockReturnValue(of({ items: [{ memberKey: 'gone', unavailable: true }] }));
            await launcher.open('QUEST_MEDIA', 'MEDIA_IN_MATERIAL');
            await launcher.start();
            expect(launcher.notice()).toEqual({ text: 'В этой колоде пока нет материалов. Добавьте материал, и в него можно будет вставить картинку или звук.',
                actions: [{ kind: 'link', label: 'Добавить материал', commands: ['/decks', OWN, 'materials', 'new'] }] });
        });

        it('fails with «Повторить» when the deck cannot be checked', async () => {
            insightsApi.mockReturnValue(throwError(() => new Error('down')));
            await launcher.open('QUEST_WORKSHOP', 'WORKSHOP_APPROVED');
            await launcher.start();
            expect(launcher.phase()).toBe('error');
        });

        it('does not start a quest over the main tour that came up while the learner chose', async () => {
            await launcher.open('QUEST_MEDIA', 'MEDIA_IN_MATERIAL');
            card.set('welcome');
            await launcher.start();
            expect(launcher.phase()).toBe('blocked');
            expect(engine.active()).toBe(false);
        });
    });

    describe('not the main journey', () => {
        it('records nothing and counts no step of the main tour, from the first step to the leave', async () => {
            await launcher.open('QUEST_VOICE', 'VOICE_ANSWER');
            await launcher.start();
            expect(engine.isQuest()).toBe(true);
            expect(engine.recordsJourney()).toBe(false);
            engine.next();
            engine.pause();
            engine.resume();
            engine.stop();
            expect(calls).toEqual([]);
        });

        it('starts the voice with its own screen about the microphone, and nothing asks for the microphone before the learner goes on', async () => {
            const getUserMedia = vi.fn(() => Promise.reject(new Error('no')));
            Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia }, configurable: true });
            await launcher.open('QUEST_VOICE', 'VOICE_ANSWER');
            await launcher.start();
            expect(engine.step()?.key).toBe('quest.voice.mic');
            expect(engine.step()?.screen).toBe(true);
            TestBed.tick();
            expect(getUserMedia).not.toHaveBeenCalled();
            // «Дальше» only takes the learner to the field; the microphone is asked for by the button the learner presses there.
            engine.next();
            expect(engine.step()?.key).toBe('quest.voice.record');
            expect(getUserMedia).not.toHaveBeenCalled();
        });
    });

    describe('the end', () => {
        it('says it is done and reads «Путь» again when the quest ends with its thing done', async () => {
            await launcher.open('QUEST_MEDIA', 'MEDIA_IN_MATERIAL');
            await launcher.start();
            const events = TestBed.inject(TourEvents);
            events.emit('media.inserted');
            events.emit('material.published');
            TestBed.tick();
            expect(engine.active()).toBe(false);
            expect(echo).toHaveBeenCalledExactlyOnceWith('Задание выполнено. Пункт «Пути» отметится сам.');
            expect(load).toHaveBeenCalledWith({ refresh: true });
            expect(calls).toEqual([]);
        });

        it('says nothing when the learner leaves, and nothing for a tour of the journey', async () => {
            await launcher.open('QUEST_MEDIA', 'MEDIA_IN_MATERIAL');
            await launcher.start();
            engine.stop();
            TestBed.tick();
            expect(echo).not.toHaveBeenCalled();
            engine.start({ id: 'sample', branch: 'SAMPLE', steps: [{ key: 'sample.hub', anchor: 'hub.header', title: 'Т', body: paragraphs('Текст.'), gate: 'next' }] });
            engine.next();
            TestBed.tick();
            expect(echo).not.toHaveBeenCalled();
        });
    });
});

