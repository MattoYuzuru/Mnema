import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import onboardingFixture from '../../../../../contracts/onboarding/journey.json';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { OnboardingView } from '../../core/onboarding/onboarding.models';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { PromoPopupService } from '../promo/promo-popup.service';
import { SpeechInputService } from '../speech/speech-input.service';
import { UsageApiService } from '../usage/usage-api.service';
import { MEDIA_PLAYBACK_RESOLVER } from './media-playback-resolver';
import { StudyApiService } from './study-api.service';
import { StudyRecoveryService } from './study-recovery.service';
import { StudySessionPageComponent } from './study-session-page.component';
import { AttemptCommand, AttemptOutcome, ReadyStudySession, StudyPresentation } from './study.models';
import { assessment, clone, fakePlayback, ids, mechanics } from './study-test-data';
import { spyObj, type SpyObj } from '../../../testing/mocks';

/**
 * The free AI checks of the sample deck (OB-203): before an attempt an AI will check, the sample deck says how many free checks are left, or
 * that they are spent; no other deck says anything; and after such an attempt the count is read again.
 */
describe('StudySessionPageComponent, the sample deck\'s free AI checks', () => {
    const deck = metadataFixture.detail as unknown as OwnDeck;
    const fixtures = mechanics['presentations'];
    const sampleView = (deckId: string | null, freeChecksLeft: number): OnboardingView => ({
        ...(onboardingFixture.views.sampleReady as OnboardingView), sampleDeck: { available: true, deckId, updateAvailable: false, freeChecksLeft }
    });
    let api: SpyObj<StudyApiService>;
    let view: ReturnType<typeof signal<OnboardingView | null>>;
    let load: ReturnType<typeof vi.fn>;
    let capabilities: LearningCapabilities;
    let fixture: ComponentFixture<StudySessionPageComponent>;

    const explanation = (): StudyPresentation => { const value = clone(assessment['presentationInFlight']); delete value.assessment; return value; };
    const spoken = (): StudyPresentation => { const value = clone(fixtures['freeResponse']); value.content.responseInput = 'TEXT_OR_SPEECH'; return value; };
    const hint = (): string | null => (fixture.nativeElement as HTMLElement).querySelector('[data-sample-check]')?.textContent ?? null;

    beforeEach(() => {
        capabilities = CAPABILITIES_UNAVAILABLE;
        view = signal<OnboardingView | null>(sampleView(deck.deckId, 2));
        load = vi.fn(() => Promise.resolve(view()));
        const decks = { detail: vi.fn().mockReturnValue(of(deck)) };
        api = spyObj<StudyApiService>({
            start: vi.fn().mockName('start'), read: vi.fn().mockName('read'), refill: vi.fn().mockName('refill'), submit: vi.fn().mockName('submit'),
            progress: vi.fn().mockName('progress'), replaySources: vi.fn().mockName('replaySources'), restart: vi.fn().mockName('restart'),
            revealTranscript: vi.fn().mockName('revealTranscript'), checkPair: vi.fn().mockName('checkPair'), hint: vi.fn().mockName('hint')
        });
        api.progress.mockReturnValue(of({ asOf: '2026-10-01T10:00:00Z', items: [], nextCursor: null }));
        api.replaySources.mockReturnValue(of({ asOf: '2026-10-01T10:00:00Z', localStudyDate: '2026-10-01', items: [] }));
        const recovery = spyObj<StudyRecoveryService>({ restore: vi.fn(), save: vi.fn(), clear: vi.fn(), now: vi.fn() });
        recovery.restore.mockReturnValue(null);
        recovery.now.mockReturnValue(1000);
        const router = { navigate: vi.fn().mockResolvedValue(true) };
        TestBed.configureTestingModule({ providers: [
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: deck.deckId }) } } },
            { provide: Router, useValue: router }, { provide: OwnDecksApiService, useValue: decks },
            { provide: StudyApiService, useValue: api }, { provide: StudyRecoveryService, useValue: recovery },
            { provide: MEDIA_PLAYBACK_RESOLVER, useValue: { resolve: fakePlayback } },
            { provide: CapabilitiesApiService, useValue: { read: () => of(capabilities) } },
            { provide: UsageApiService, useValue: { load: () => throwError(() => new Error('no usage')) } },
            { provide: SpeechInputService, useValue: { checkConsent: vi.fn().mockResolvedValue({ ok: true }) } },
            { provide: PromoPopupService, useValue: { request: vi.fn().mockResolvedValue(undefined) } },
            { provide: JourneyStore, useValue: { view: view.asReadonly(), load } }
        ] });
    });

    function open(...presentations: StudyPresentation[]): void {
        const session = { sessionId: ids.sessionId, deckId: deck.deckId, mode: 'SCHEDULED', status: 'ACTIVE', timezone: 'Europe/Moscow',
            localStudyDate: '2026-10-01', deckRevisionId: '33333333-3333-4333-8333-333333333333',
            exerciseGenerationId: '99999999-9999-4999-8999-999999999993', selectionPolicyVersion: 'deck-due-new-v2',
            budget: { maxPresentations: 20, maxNewObjectives: 5 }, issuedCount: presentations.length,
            reducer: { id: 'mnema-baseline', version: '1', configId: ids.reducerConfigId, configHash: `sha256:${'a'.repeat(64)}` },
            seed: '42', nextCursor: null, expiresAt: '2026-10-02T10:00:00Z', presentations } as ReadyStudySession;
        api.start.mockReturnValue(of({ value: session, replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent);
        fixture.detectChanges();
        fixture.componentInstance.startScheduled('STANDARD');
        fixture.detectChanges();
    }

    it.each([[3, 'В учебной колоде 3 проверки ИИ бесплатны. Осталось: 3.'], [1, 'В учебной колоде 3 проверки ИИ бесплатны. Осталось: 1.'],
        [0, 'Бесплатные проверки в учебной колоде закончились. Дальше проверка расходует ваш ИИ-бюджет.']])(
        'says before an explanation the AI will grade how many free checks are left (%i)', (left, expected) => {
            view.set(sampleView(deck.deckId, left));
            open(explanation());
            expect(hint()).toBe(expected);
        });

    it('puts the hint between the heading of the card and the exercise, before the learner can answer', () => {
        open(explanation());
        const card = (fixture.nativeElement as HTMLElement).querySelector('.study-card')!;
        expect([...card.children].map(child => child.tagName.toLowerCase())).toEqual(['p', 'h2', 'p', 'app-learner-exercise']);
    });

    it('says it before a spoken answer when the microphone is offered', () => {
        capabilities = { ...CAPABILITIES_UNAVAILABLE, speechToText: { available: true, reason: null } };
        open(spoken());
        expect(hint()).toContain('Осталось: 2.');
    });

    it('says nothing before a spoken answer when there is no microphone to use', () => {
        open(spoken());
        expect(hint()).toBeNull();
    });

    it.each([['a typed answer checked without AI', 'freeResponse'], ['a self-check', 'selfCheck'], ['a cloze', 'cloze'], ['a choice', 'choice']])(
        'says nothing before %s', (_name, mechanic) => {
            open(clone(fixtures[mechanic]));
            expect(hint()).toBeNull();
        });

    it('says nothing in any other deck, however few checks are left', () => {
        view.set(sampleView('99999999-9999-4999-8999-999999999999', 0));
        open(explanation());
        expect(hint()).toBeNull();
    });

    it('says nothing before the onboarding view has arrived', () => {
        view.set(null);
        open(explanation());
        expect(hint()).toBeNull();
    });

    it('reads the count again after an attempt at an exercise of the sample deck, and not for any other deck', async () => {
        open(clone(fixtures['freeResponse']));
        api.submit.mockImplementation((_deck: string, _session: string, command: AttemptCommand) => of({
            value: { attemptId: command.attemptId, presentationId: fixtures['freeResponse'].presentationId, mode: 'SCHEDULED', status: 'ASSESSED',
                feedback: clone(mechanics['feedback']['freeResponse']), canonicalEffects: true, transition: { beforeLevel: 0, afterLevel: 1, nextDue: '2026-10-02T10:00:00Z' },
                disputed: false } as AttemptOutcome, replayed: false }));
        const field = (fixture.nativeElement as HTMLElement).querySelector<HTMLTextAreaElement>('textarea')!;
        field.value = 'ответ';
        field.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[data-submit]')!.click();
        fixture.detectChanges();
        expect(load).toHaveBeenCalledWith({ refresh: true });
    });

    it('does not read the onboarding again after an attempt in an ordinary deck', () => {
        view.set(sampleView('99999999-9999-4999-8999-999999999999', 2));
        open(clone(fixtures['freeResponse']));
        api.submit.mockImplementation((_deck: string, _session: string, command: AttemptCommand) => of({
            value: { attemptId: command.attemptId, presentationId: fixtures['freeResponse'].presentationId, mode: 'SCHEDULED', status: 'ASSESSED',
                feedback: clone(mechanics['feedback']['freeResponse']), canonicalEffects: true, transition: { beforeLevel: 0, afterLevel: 1, nextDue: '2026-10-02T10:00:00Z' },
                disputed: false } as AttemptOutcome, replayed: false }));
        const field = (fixture.nativeElement as HTMLElement).querySelector<HTMLTextAreaElement>('textarea')!;
        field.value = 'ответ';
        field.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[data-submit]')!.click();
        fixture.detectChanges();
        expect(load).not.toHaveBeenCalled();
    });
});
