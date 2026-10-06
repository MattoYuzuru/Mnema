import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { AuthService, AuthStatus } from '../../auth.service';
import { GoalOnboardingComponent, onboardingSuppressed } from './goal-onboarding.component';
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
