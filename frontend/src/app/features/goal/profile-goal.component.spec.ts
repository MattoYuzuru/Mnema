import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { AuthService } from '../../auth.service';
import { AttentionCoordinator } from '../../core/onboarding/attention-coordinator';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { PathHint } from '../../core/onboarding/path-hint.service';
import { PATH_PLACES } from '../../core/onboarding/path-places';
import { TourAnchors } from '../../core/onboarding/tour-anchors';
import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { FakeJourneyStore, OFFERED_VIEW } from '../onboarding/onboarding-test-data';
import { GoalOnboardingComponent, surveyAsksTheGoal } from './goal-onboarding.component';
import { LearningGoalStore } from './learning-goal.store';
import { LearningProfileApiService } from './learning-profile-api.service';
import { ProfileGoalComponent } from './profile-goal.component';

describe('ProfileGoalComponent (the «Цель» block of the profile)', () => {
    let api: SpyObj<LearningProfileApiService>;
    let journey: FakeJourneyStore;

    beforeEach(() => {
        api = spyObj<LearningProfileApiService>({ load: vi.fn(), answer: vi.fn() });
        api.load.mockReturnValue(of({ goal: null, skipped: false, answeredAt: null }));
        journey = new FakeJourneyStore({});
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '**', children: [] }]), { provide: LearningProfileApiService, useValue: api }, { provide: JourneyStore, useValue: journey },
                { provide: TourEngine, useValue: spyObj<TourEngine>({ active: signal(false), paused: signal(false) }) },
                { provide: AuthService, useValue: { status: () => 'authenticated', status$: of('authenticated'), user: signal({ accountId: 'a' }) } }]
        });
    });

    async function render(): Promise<{ root: HTMLElement; detect: () => Promise<void> }> {
        const fixture = TestBed.createComponent(ProfileGoalComponent);
        const detect = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
        await detect();
        return { root: fixture.nativeElement as HTMLElement, detect };
    }

    it('asks the question with a skip while the goal is unanswered, as a named radio group under the heading «Цель»', async () => {
        const { root } = await render();
        expect(root.querySelector('h2')?.textContent).toBe('Цель');
        expect(root.querySelector('section')?.getAttribute('aria-labelledby')).toBe(root.querySelector('h2')?.id);
        expect(root.querySelector('legend')?.textContent).toBe('Для чего вам Mnema?');
        expect([...root.querySelectorAll('label.segment')].map(label => label.textContent?.trim())).toEqual(['Экзамены и сессия', 'Собеседование', 'Язык', 'Работа', 'Для себя']);
        expect(root.querySelector<HTMLButtonElement>('button[type=submit]')?.disabled).toBe(true);
        expect([...root.querySelectorAll('button')].map(button => button.textContent?.trim())).toEqual(['Сохранить', 'Пропустить']);
    });

    it('records the goal chosen here (PUT /api/learning-profile) and then says it, with «Изменить»', async () => {
        api.answer.mockReturnValue(of({ goal: 'LANGUAGE', skipped: false, answeredAt: '2026-10-05T09:00:00Z' }));
        const { root, detect } = await render();
        root.querySelectorAll<HTMLInputElement>('input[type=radio]')[2].click();
        await detect();
        root.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
        await detect();
        expect(api.answer).toHaveBeenCalledWith('LANGUAGE');
        expect(TestBed.inject(LearningGoalStore).goal()).toBe('LANGUAGE');
        expect(root.querySelector('form')).toBeNull();
        expect(root.querySelector('p')?.textContent).toBe('Ваша цель: Язык.');
        expect(root.querySelector('button')?.textContent).toBe('Изменить');
    });

    it('records a skip as a skip, and says there is no goal with «Выбрать»', async () => {
        api.answer.mockReturnValue(of({ goal: null, skipped: true, answeredAt: '2026-10-05T09:00:00Z' }));
        const { root, detect } = await render();
        [...root.querySelectorAll('button')].find(button => button.textContent?.trim() === 'Пропустить')!.click();
        await detect();
        expect(api.answer).toHaveBeenCalledWith(null);
        expect(root.querySelector('p')?.textContent).toBe('Цель не выбрана. Её можно выбрать в любой момент.');
        expect(root.querySelector('button')?.textContent).toBe('Выбрать');
    });

    it('shows the goal that is answered and changes it: the choice starts at the current goal, and «Отмена» keeps it', async () => {
        api.load.mockReturnValue(of({ goal: 'EXAMS', skipped: false, answeredAt: '2026-10-05T09:00:00Z' }));
        const { root, detect } = await render();
        expect(root.querySelector('p')?.textContent).toBe('Ваша цель: Экзамены и сессия.');
        expect(root.querySelector('form')).toBeNull();
        root.querySelector<HTMLButtonElement>('button')!.click();
        await detect();
        expect(root.querySelector<HTMLInputElement>('input[type=radio]:checked')?.value).toBe('EXAMS');
        expect([...root.querySelectorAll('button')].map(button => button.textContent?.trim())).toEqual(['Сохранить', 'Отмена']);
        [...root.querySelectorAll('button')].find(button => button.textContent?.trim() === 'Отмена')!.click();
        await detect();
        expect(root.querySelector('form')).toBeNull();
        expect(api.answer).not.toHaveBeenCalled();
        // Another goal is recorded in place of the old one.
        api.answer.mockReturnValue(of({ goal: 'WORK', skipped: false, answeredAt: '2026-10-06T09:00:00Z' }));
        root.querySelector<HTMLButtonElement>('button')!.click();
        await detect();
        root.querySelectorAll<HTMLInputElement>('input[type=radio]')[3].click();
        await detect();
        root.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
        await detect();
        expect(api.answer).toHaveBeenCalledWith('WORK');
        await vi.waitFor(async () => { await detect(); expect(root.querySelector('p')?.textContent).toBe('Ваша цель: Работа.'); });
    });

    it('keeps the form and says so when the goal cannot be saved', async () => {
        api.answer.mockReturnValue(throwError(() => new Error('down')));
        const { root, detect } = await render();
        root.querySelectorAll<HTMLInputElement>('input[type=radio]')[0].click();
        await detect();
        root.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
        await detect();
        expect(root.querySelector('[role="alert"]')?.textContent).toBe('Не удалось сохранить цель. Попробуйте ещё раз.');
        expect(root.querySelector('form')).not.toBeNull();
    });

    it('says it is loading, and a failed read with «Повторить»', async () => {
        api.load.mockReturnValue(throwError(() => new Error('down')));
        const { root, detect } = await render();
        expect(root.querySelector('[role="alert"]')?.textContent).toBe('Не удалось загрузить цель.');
        api.load.mockReturnValue(of({ goal: null, skipped: false, answeredAt: null }));
        root.querySelector<HTMLButtonElement>('button')!.click();
        await detect();
        expect(root.querySelector('form')).not.toBeNull();
    });

    describe('the milestone «Расскажите о цели»', () => {
        it('has its anchor here even when the survey is the one that asks the goal (the welcome was skipped: the question of the shell is hidden)', async () => {
            // A journey skipped from the welcome: eligible, offered automatically, never answered the goal.
            journey.view.set({ ...OFFERED_VIEW, journey: { ...OFFERED_VIEW.journey!, state: 'SKIPPED', eligible: true, autoOffer: true } });
            expect(surveyAsksTheGoal(journey.enabled(), journey.journey())).toBe(true);
            const shell = TestBed.createComponent(GoalOnboardingComponent);
            shell.detectChanges();
            await shell.whenStable();
            shell.detectChanges();
            expect(shell.nativeElement.querySelector('form')).toBeNull();
            const { root } = await render();
            expect(root.querySelector('form')).not.toBeNull();
            // The chevron of the milestone: the place of the route key, then the hint finds its anchor on the profile.
            const place = PATH_PLACES.PROFILE_GOAL({ user: null, any: null })!;
            expect(place).toMatchObject({ commands: ['/profile'], anchor: 'profile.goal' });
            expect(TestBed.inject(TourAnchors).lookup('profile.goal')).toBe(root.querySelector('section'));
            const hint = TestBed.inject(PathHint);
            expect(hint.begin({ title: 'Расскажите о цели', text: 'Текст.', anchor: place.anchor })).toBe(true);
            hint.arm();
            TestBed.tick();
            expect(hint.phase()).toBe('ready');
            expect(hint.target()).toBe(root.querySelector('section'));
            expect(TestBed.inject(AttentionCoordinator).visible()).toEqual(['hint']);
        });
    });
});
