import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { AuthService, AuthStatus } from '../../auth.service';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { Journey } from '../../core/onboarding/onboarding.models';
import { FakeJourneyStore, OFFERED_VIEW } from '../onboarding/onboarding-test-data';
import { GoalOnboardingComponent, onboardingSuppressed, surveyAsksTheGoal } from './goal-onboarding.component';
import { LearningGoalStore } from './learning-goal.store';
import { LearningProfileApiService } from './learning-profile-api.service';

describe('GoalOnboardingComponent', () => {
    let api: SpyObj<LearningProfileApiService>;
    let status: BehaviorSubject<AuthStatus>;

    beforeEach(() => {
        api = spyObj<LearningProfileApiService>({ load: vi.fn().mockName('load'), answer: vi.fn().mockName('answer') });
        api.load.mockReturnValue(of({ goal: null, skipped: false, answeredAt: null }));
        status = new BehaviorSubject<AuthStatus>('authenticated');
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '**', children: [] }]), { provide: LearningProfileApiService, useValue: api },
                { provide: AuthService, useValue: { status: () => status.value, status$: status.asObservable() } }]
        });
    });

    async function render(url = '/decks'): Promise<{ root: HTMLElement; detect: () => Promise<void> }> {
        await TestBed.inject(Router).navigateByUrl(url);
        const fixture = TestBed.createComponent(GoalOnboardingComponent);
        const detect = async () => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
        await detect();
        return { root: fixture.nativeElement as HTMLElement, detect };
    }

    it('asks once after sign-in when the goal is unanswered, as a named radio group', async () => {
        const { root } = await render();
        expect(root.querySelector('legend')?.textContent).toBe('Для чего вам Mnema?');
        const labels = [...root.querySelectorAll('label.segment')].map(label => label.textContent?.trim());
        expect(labels).toEqual(['Экзамены и сессия', 'Собеседование', 'Язык', 'Работа', 'Для себя']);
        expect(root.querySelectorAll('input[type=radio]')).toHaveLength(5);
        expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
        expect(root.textContent).toContain('Пропустить');
    });

    it('stores the chosen goal and goes away', async () => {
        api.answer.mockReturnValue(of({ goal: 'EXAMS', skipped: false, answeredAt: '2026-10-05T09:00:00Z' }));
        const { root, detect } = await render();
        root.querySelectorAll<HTMLInputElement>('input[type=radio]')[0].click();
        await detect();
        root.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
        await detect();
        expect(api.answer).toHaveBeenCalledWith('EXAMS');
        expect(TestBed.inject(LearningGoalStore).goal()).toBe('EXAMS');
        expect(root.querySelector('form')).toBeNull();
    });

    it('records a skip and hides at once', async () => {
        api.answer.mockReturnValue(of({ goal: null, skipped: true, answeredAt: '2026-10-05T09:00:00Z' }));
        const { root, detect } = await render();
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Пропустить')!.click();
        await detect();
        expect(api.answer).toHaveBeenCalledWith(null);
        expect(root.querySelector('form')).toBeNull();
    });

    it('does not ask again once answered or skipped', async () => {
        api.load.mockReturnValue(of({ goal: null, skipped: true, answeredAt: '2026-10-05T09:00:00Z' }));
        expect((await render()).root.querySelector('form')).toBeNull();
    });

    it('asks only on the deck list, the profile and the plans page', async () => {
        for (const url of ['/decks', '/decks/?x=1', '/profile', '/plans#plus']) {
            TestBed.inject(LearningGoalStore).reset();
            expect((await render(url)).root.querySelector('form'), url).not.toBeNull();
        }
    });

    it('stays out of the way everywhere else: authoring, capture, the workshop, study, legal pages, home and sign-in', async () => {
        const deck = 'd1000000-0000-4000-8000-000000000001';
        const quiet = ['/', '/ai', '/privacy', '/terms', '/login', '/register', '/auth/callback', '/styleguide', '/decks/new',
            `/decks/${deck}`, `/decks/${deck}/study`, `/decks/${deck}/capture`, `/decks/${deck}/materials/new`,
            `/decks/${deck}/exercises/generate`, `/decks/${deck}/materials/m1/edit`, `/decks/${deck}/exercises/e1/edit`,
            `/decks/${deck}/workshop/s1`];
        for (const url of quiet) {
            TestBed.inject(LearningGoalStore).reset();
            expect((await render(url)).root.querySelector('form'), url).toBeNull();
            expect(onboardingSuppressed(url), url).toBe(true);
        }
        expect(onboardingSuppressed('/decks')).toBe(false);
    });

    it('asks nothing of an anonymous visitor and does not call the API', async () => {
        status.next('anonymous');
        const { root } = await render('/');
        expect(root.querySelector('form')).toBeNull();
        expect(api.load).not.toHaveBeenCalled();
    });

    it('explains a failed save and keeps the question', async () => {
        const { throwError } = await import('rxjs');
        api.answer.mockReturnValue(throwError(() => new Error('down')));
        const { root, detect } = await render();
        root.querySelectorAll<HTMLInputElement>('input[type=radio]')[1].click();
        await detect();
        root.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
        await detect();
        expect(root.querySelector('[role=alert]')?.textContent).toContain('Не удалось сохранить ответ');
        expect(root.querySelector('form')).not.toBeNull();
    });
});

/**
 * The first-entry survey asks the goal question to the accounts the journey is for; everyone else keeps the inline question as it was.
 * The allowlist is tested as a table, and then on the component with the journey of each kind of account.
 */
describe('the goal question and the first-entry survey', () => {
    const journey = (overrides: Partial<Journey>): Journey => ({ ...(OFFERED_VIEW.journey as Journey), ...overrides });

    describe('surveyAsksTheGoal', () => {
        it.each([
            ['offered by itself to a new account', true, { state: 'OFFERED', eligible: true, autoOffer: true }, true],
            ['in progress', true, { state: 'IN_PROGRESS', eligible: true, autoOffer: false }, true],
            ['paused', true, { state: 'PAUSED', eligible: true, autoOffer: false }, true],
            ['skipped', true, { state: 'SKIPPED', eligible: true, autoOffer: false }, true],
            ['completed', true, { state: 'COMPLETED', eligible: true, autoOffer: false }, true],
            ['the holdout: eligible, offered, not auto-offered', true, { state: 'OFFERED', eligible: true, autoOffer: false }, false],
            ['the holdout after a replay: the replay has no survey, so the inline question stays', true, { state: 'IN_PROGRESS', eligible: true, autoOffer: false, replayCount: 1 }, false],
            ['the holdout whose replay ended', true, { state: 'COMPLETED', eligible: true, autoOffer: false, replayCount: 2 }, false],
            ['an account before the release (not eligible, no row)', true, { state: 'NOT_OFFERED', eligible: false, autoOffer: false }, false],
            ['an account that started the tour from the profile (not eligible, in progress)', true, { state: 'IN_PROGRESS', eligible: false, autoOffer: false }, false],
            ['onboarding switched off, whatever the journey', false, { state: 'IN_PROGRESS', eligible: true, autoOffer: false }, false]
        ] as const)('for %s', (_name, enabled, overrides, asks) => {
            expect(surveyAsksTheGoal(enabled, journey(overrides))).toBe(asks);
        });

        it('does not ask for the survey while there is no journey', () => {
            expect(surveyAsksTheGoal(true, null)).toBe(false);
        });
    });

    describe('on the page', () => {
        const render = async (store: FakeJourneyStore | null): Promise<HTMLElement> => {
            const api = spyObj<LearningProfileApiService>({ load: vi.fn().mockName('load'), answer: vi.fn().mockName('answer') });
            api.load.mockReturnValue(of({ goal: null, skipped: false, answeredAt: null }));
            TestBed.configureTestingModule({
                providers: [provideRouter([{ path: '**', children: [] }]), { provide: LearningProfileApiService, useValue: api },
                    { provide: AuthService, useValue: { status: () => 'authenticated', status$: new BehaviorSubject<AuthStatus>('authenticated').asObservable() } },
                    ...(store === null ? [] : [{ provide: JourneyStore, useValue: store }])]
            });
            await TestBed.inject(Router).navigateByUrl('/decks');
            const fixture = TestBed.createComponent(GoalOnboardingComponent);
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.detectChanges();
            return fixture.nativeElement as HTMLElement;
        };

        it('yields to the survey for an account that was offered the tour by itself', async () => {
            const root = await render(new FakeJourneyStore({ state: 'OFFERED', eligible: true, autoOffer: true }));
            expect(root.querySelector('form')).toBeNull();
        });

        it('yields to the survey for an account past the offer', async () => {
            const root = await render(new FakeJourneyStore({ state: 'SKIPPED', eligible: true, autoOffer: false }));
            expect(root.querySelector('form')).toBeNull();
        });

        it('keeps asking the holdout, as before', async () => {
            const root = await render(new FakeJourneyStore({ state: 'OFFERED', eligible: true, autoOffer: false }));
            expect(root.querySelector('legend')?.textContent).toBe('Для чего вам Mnema?');
        });

        it('keeps asking an old account that is not in the cohort', async () => {
            const root = await render(new FakeJourneyStore({ state: 'NOT_OFFERED', eligible: false, autoOffer: false }));
            expect(root.querySelector('legend')?.textContent).toBe('Для чего вам Mnema?');
        });

        it('keeps asking while onboarding is off', async () => {
            const root = await render(new FakeJourneyStore(null));
            expect(root.querySelector('legend')?.textContent).toBe('Для чего вам Mnema?');
        });

        it('waits for the answer about the journey before it asks, so the question does not flash and go', async () => {
            const store = new FakeJourneyStore({ state: 'OFFERED', eligible: true, autoOffer: true });
            store.loading.set(true);
            const root = await render(store);
            expect(root.querySelector('form')).toBeNull();
        });

        it('asks when the journey cannot be read: no tour, the old question as it was', async () => {
            const store = new FakeJourneyStore(null);
            store.view.set(null);
            const root = await render(store);
            expect(root.querySelector('legend')?.textContent).toBe('Для чего вам Mnema?');
        });
    });
});
