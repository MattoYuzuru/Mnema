import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import journey from '../../../../../contracts/onboarding/journey.json';
import { AuthService } from '../../auth.service';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { OnboardingView } from '../../core/onboarding/onboarding.models';
import { PathPreferences } from '../../core/onboarding/path-preferences';
import { PathSummaryComponent } from './path-summary.component';
import { FakeJourneyStore, REWARD_ACTIVE, REWARD_EXPIRED, REWARD_REDEEMED } from './onboarding-test-data';

const midway = journey.views.pathMidway as OnboardingView;

describe('PathSummaryComponent (the «Путь Mnema» block of the profile)', () => {
    const render = (view: OnboardingView | null) => {
        localStorage.clear();
        const store = new FakeJourneyStore({}, {});
        store.view.set(view);
        TestBed.configureTestingModule({
            providers: [provideRouter([]), { provide: JourneyStore, useValue: store },
                { provide: AuthService, useValue: { user: signal({ accountId: 'acc-1' }) } }]
        });
        const fixture = TestBed.createComponent(PathSummaryComponent);
        fixture.detectChanges();
        return { fixture, root: fixture.nativeElement as HTMLElement };
    };
    afterEach(() => localStorage.clear());

    it('says how far the learner is and links to the page', () => {
        const { root } = render(midway);
        expect(root.querySelector('h2')?.textContent).toBe('Путь Mnema');
        expect(root.querySelector('.total')?.textContent).toBe('5 из 12');
        expect(root.querySelector('[role="progressbar"]')?.getAttribute('aria-valuetext')).toBe('5 из 12');
        const link = root.querySelector('a')!;
        expect(link.textContent).toBe('Открыть «Путь»');
        expect(link.getAttribute('href')).toBe('/profile/path');
        expect(root.querySelector('section')?.getAttribute('aria-labelledby')).toBe(root.querySelector('h2')?.id);
    });

    it('shrinks to the link for a learner who hid «Путь»', () => {
        const { fixture, root } = render(midway);
        TestBed.inject(PathPreferences).setHidden(true);
        fixture.detectChanges();
        expect(root.querySelector('.total')).toBeNull();
        expect(root.querySelector('[role="progressbar"]')).toBeNull();
        expect(root.querySelector('a')?.getAttribute('href')).toBe('/profile/path');
    });

    describe('the reward of the finished path', () => {
        const done = journey.views.rewardReady as OnboardingView;
        const plain = (text: string | null): string => (text ?? '').replace(/\u00a0/gu, ' ').trim();

        it('shows an active reward in the block as an h3 under the block\'s h2, with the gift, the code and «Выбрать тариф», before the link to the page', () => {
            const { root } = render(done);
            const card = root.querySelector('app-reward-card')!;
            expect(card.querySelector('h3')?.textContent).toBe('Вы освоили Mnema полностью');
            expect(plain(card.querySelector('.gift')!.textContent)).toBe('Ваш подарок: −10 % на подписку до 29 октября, 21:41 МСК.');
            expect(card.querySelector('a.button.primary')!.getAttribute('href')).toBe('/plans?promo=K7PMW2QXH9RT');
            expect(card.compareDocumentPosition(root.querySelector('a[href="/profile/path"]')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        });

        it('shows it to a learner who hid «Путь» too: the block shrinks to the link and the gift', () => {
            const { fixture, root } = render(done);
            TestBed.inject(PathPreferences).setHidden(true);
            fixture.detectChanges();
            expect(root.querySelector('.total')).toBeNull();
            expect(root.querySelector('app-reward-card app-reward-code')).not.toBeNull();
        });

        it('says one quiet line once redeemed, and nothing once expired or when there is none', () => {
            expect(plain(render({ ...done, reward: REWARD_REDEEMED }).root.querySelector('app-reward-card')!.textContent)).toBe('Подарок за «Путь» использован.');
            for (const reward of [REWARD_EXPIRED, null]) {
                TestBed.resetTestingModule();
                const { root } = render({ ...done, reward });
                expect(root.querySelector('app-reward-card')).toBeNull();
                expect(root.textContent).not.toMatch(/подар/iu);
            }
            expect(REWARD_ACTIVE.state).toBe('ACTIVE');
        });
    });

    it('is not on the profile while onboarding is off, or before the first answer', () => {
        expect(render({ ...midway, enabled: false, journey: null, milestones: [], path: null }).root.querySelector('section')).toBeNull();
        TestBed.resetTestingModule();
        expect(render(null).root.querySelector('section')).toBeNull();
    });
});
