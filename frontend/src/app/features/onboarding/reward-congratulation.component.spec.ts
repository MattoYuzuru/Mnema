import { WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { spyObj } from '../../../testing/mocks';
import { AuthService } from '../../auth.service';
import { QuietZone } from '../../core/notifications/quiet-zone';
import { AttentionCoordinator } from '../../core/onboarding/attention-coordinator';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { PathReward } from '../../core/onboarding/onboarding.models';
import { PATH_REWARD_STORAGE_PREFIX, PathPreferences } from '../../core/onboarding/path-preferences';
import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { FakeJourneyStore, REWARD_ACTIVE, REWARD_EXPIRED, REWARD_REDEEMED, REWARD_VIEW, viewOf } from './onboarding-test-data';
import { RewardCongratulationComponent } from './reward-congratulation.component';

const plain = (text: string | null): string => (text ?? '').replace(/\u00a0/gu, ' ').trim();

describe('RewardCongratulationComponent (the congratulation for a finished «Путь»)', () => {
    let fixture: ComponentFixture<RewardCongratulationComponent>;
    let root: HTMLElement;
    let store: FakeJourneyStore;
    let attention: AttentionCoordinator;
    let active: WritableSignal<boolean>;
    let paused: WritableSignal<boolean>;
    let status: WritableSignal<string>;

    const settle = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); TestBed.tick(); fixture.detectChanges(); };
    const withReward = (reward: PathReward | null): void => store.view.set({ ...REWARD_VIEW, reward });
    const dialog = (): HTMLDialogElement | null => root.querySelector('dialog');
    const render = async (reward: PathReward | null = REWARD_ACTIVE): Promise<void> => {
        active = signal(false);
        paused = signal(false);
        status = signal('authenticated');
        store = new FakeJourneyStore({}, {});
        store.view.set(viewOf({}, { reward }));
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '**', children: [] }]),
                { provide: JourneyStore, useValue: store }, { provide: TourEngine, useValue: spyObj<TourEngine>({ active, paused }) },
                { provide: AuthService, useValue: { status, user: signal({ accountId: 'acc-1' }) } }]
        });
        attention = TestBed.inject(AttentionCoordinator);
        fixture = TestBed.createComponent(RewardCongratulationComponent);
        root = fixture.nativeElement;
        await settle();
    };

    beforeEach(() => localStorage.clear());
    afterEach(() => { localStorage.clear(); vi.useRealTimers(); document.body.replaceChildren(); });

    it('draws nothing while the view has no reward, or one that is not active', async () => {
        await render(null);
        expect(dialog()).toBeNull();
        for (const reward of [REWARD_REDEEMED, REWARD_EXPIRED]) {
            withReward(reward);
            await settle();
            expect(dialog(), reward.state).toBeNull();
        }
        expect(attention.visible()).toEqual([]);
    });

    describe('when an active reward has not been shown on this device', () => {
        beforeEach(async () => { await render(); });

        it('opens as a modal dialog on the night, named by its heading and described by the gift, with the stars of the shared sky', () => {
            const night = dialog()!;
            expect(night.open).toBe(true);
            expect(night.getAttribute('aria-labelledby')).toBe('reward-title');
            expect(night.getAttribute('aria-describedby')).toBe('reward-gift');
            expect(root.querySelector('app-night-sky')).not.toBeNull();
            expect(root.querySelector('.sky')).not.toBeNull();
            expect(plain(root.querySelector('#reward-title')!.textContent)).toBe('Вы освоили Mnema полностью');
        });

        it('puts the focus on the heading', () => {
            expect(document.activeElement).toBe(root.querySelector('#reward-title'));
            expect(root.querySelector('#reward-title')!.tagName).toBe('H1');
        });

        it('says the approved words: the rubric, the title, the gift with the date it ends in Moscow time, and the terms', () => {
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Путь пройден');
            expect(plain(root.querySelector('#reward-gift')!.textContent)).toBe('Ваш подарок: −10 % на подписку до 29 октября, 21:41 МСК.');
            expect(plain(root.querySelector('.muted')!.textContent)).toBe('Предложение действует один раз. Условия и сочетание с промокодами — на странице тарифов.');
        });

        it('has no countdown, seconds or urgent word anywhere', () => {
            expect(root.textContent).not.toMatch(/\d+:\d+:\d+|осталось|успей|скорее|последн|спешите|торопитесь|только сегодня/iu);
        });

        it('shows the code as a named, selectable and copyable field', () => {
            const field = root.querySelector<HTMLInputElement>('app-reward-code input')!;
            expect(field.value).toBe('K7PM-W2QX-H9RT');
            expect(root.querySelector('app-reward-code label')!.textContent).toBe('Ваш промокод');
            expect(root.querySelector('app-reward-code')!.classList.contains('night')).toBe(true);
            expect(root.querySelector('app-reward-code button')!.textContent).toBe('Скопировать');
        });

        it('links «Выбрать тариф» to the plans with the code (without dashes) for the promo field, never applying it', () => {
            const link = root.querySelector<HTMLAnchorElement>('.actions a.button.primary')!;
            expect(link.textContent).toBe('Выбрать тариф');
            expect(link.getAttribute('href')).toBe('/plans?promo=K7PM-W2QX-H9RT');
            expect(root.querySelector('.actions button')!.textContent).toBe('Позже');
        });

        it('is on the screen for the coordinator while it is open, and marked shown for this device at once', () => {
            expect(attention.speaking()).toBe('reward');
            expect(localStorage.getItem(`${PATH_REWARD_STORAGE_PREFIX}acc-1`)).toBe(REWARD_ACTIVE.issuedAt);
            expect(TestBed.inject(PathPreferences).rewardShown(REWARD_ACTIVE.issuedAt)).toBe(true);
        });

        it('stays while the view is read again with the same reward', async () => {
            store.view.set({ ...store.view()! });
            await settle();
            expect(dialog()?.open).toBe(true);
        });

        it('«Позже» closes it, returns the focus to the page and does not bring it back', async () => {
            root.querySelector<HTMLButtonElement>('.actions button')!.click();
            await settle();
            await Promise.resolve();
            expect(dialog()).toBeNull();
            expect(attention.visible()).toEqual([]);
            store.view.set({ ...store.view()! });
            await settle();
            expect(dialog()).toBeNull();
        });

        it('closes on Esc as «Позже» does, whatever the browser would do with the event', async () => {
            const cancel = new Event('cancel', { cancelable: true });
            dialog()!.dispatchEvent(cancel);
            await settle();
            expect(cancel.defaultPrevented).toBe(true);
            expect(dialog()).toBeNull();
        });

        it('is released whenever the dialog is closed from outside, with the focus back on the page', async () => {
            dialog()!.close();
            await settle();
            await Promise.resolve();
            expect(dialog()).toBeNull();
            expect(attention.visible()).toEqual([]);
            expect(attention.speaking()).toBeNull();
            store.view.set({ ...store.view()! });
            await settle();
            expect(dialog()).toBeNull();
        });

        it('goes away behind the link to the plans', async () => {
            const link = root.querySelector<HTMLAnchorElement>('.actions a.button.primary')!;
            link.addEventListener('click', event => event.preventDefault());
            link.click();
            await settle();
            expect(dialog()).toBeNull();
            expect(attention.visible()).toEqual([]);
        });

        it('goes away when the reward is redeemed elsewhere', async () => {
            withReward(REWARD_REDEEMED);
            await settle();
            expect(dialog()).toBeNull();
            expect(attention.visible()).toEqual([]);
        });

        it('goes away when the view is forgotten (the account signs out)', async () => {
            store.reset();
            await settle();
            expect(dialog()).toBeNull();
            expect(attention.visible()).toEqual([]);
        });
    });

    describe('focus', () => {
        it('returns to where it was before the night opened, when that is still on the page', async () => {
            const before = document.createElement('button');
            document.body.append(before);
            before.focus();
            await render();
            expect(document.activeElement).toBe(root.querySelector('#reward-title'));
            root.querySelector<HTMLButtonElement>('.actions button')!.click();
            await settle();
            await vi.waitFor(() => expect(document.activeElement).toBe(before));
        });

        it('returns to the heading of the page when nothing had the focus (the body is no place to return to)', async () => {
            const main = document.createElement('main');
            main.id = 'main-content';
            main.innerHTML = '<h1 tabindex="-1">Профиль</h1>';
            document.body.append(main);
            (document.activeElement as HTMLElement | null)?.blur();
            expect(document.activeElement).toBe(document.body);
            await render();
            root.querySelector<HTMLButtonElement>('.actions button')!.click();
            await settle();
            await vi.waitFor(() => expect(document.activeElement).toBe(main.querySelector('h1')));
        });

        it('returns to the heading of the page when the place it came from is gone', async () => {
            const main = document.createElement('main');
            main.id = 'main-content';
            main.innerHTML = '<h1 tabindex="-1">Профиль</h1>';
            const before = document.createElement('button');
            main.append(before);
            document.body.append(main);
            before.focus();
            await render();
            before.remove();
            root.querySelector<HTMLButtonElement>('.actions button')!.click();
            await settle();
            await vi.waitFor(() => expect(document.activeElement).toBe(main.querySelector('h1')));
        });
    });

    describe('once per reward on a device', () => {
        it('does not open for a reward this device has shown before', async () => {
            localStorage.setItem(`${PATH_REWARD_STORAGE_PREFIX}acc-1`, REWARD_ACTIVE.issuedAt);
            await render();
            expect(dialog()).toBeNull();
        });

        it('does not open again after a reload (a new page over the same storage)', async () => {
            await render();
            expect(dialog()).not.toBeNull();
            fixture.destroy();
            TestBed.resetTestingModule();
            await render();
            expect(dialog()).toBeNull();
        });

        it('opens for a reward that arrives later, once the view is read again', async () => {
            await render(null);
            expect(dialog()).toBeNull();
            withReward(REWARD_ACTIVE);
            await settle();
            expect(dialog()?.open).toBe(true);
        });

        it('opens on a device that cannot keep the memory, once per page', async () => {
            vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
            vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
            await render();
            expect(dialog()?.open).toBe(true);
            root.querySelector<HTMLButtonElement>('.actions button')!.click();
            await settle();
            store.view.set({ ...store.view()! });
            await settle();
            expect(dialog()).toBeNull();
        });

        it('keeps the memory per account: what another account of this browser was shown does not hide the congratulation from this one', async () => {
            localStorage.setItem(`${PATH_REWARD_STORAGE_PREFIX}acc-2`, REWARD_ACTIVE.issuedAt);
            await render();
            expect(dialog()?.open).toBe(true);
        });
    });

    describe('waits for the way to be free', () => {
        it('does not open during the tour, and opens when it ends', async () => {
            await render(null);
            active.set(true);
            withReward(REWARD_ACTIVE);
            await settle();
            expect(dialog()).toBeNull();
            active.set(false);
            await settle();
            expect(dialog()?.open).toBe(true);
        });

        it('opens over a paused tour (a plaque is not a loud surface)', async () => {
            await render(null);
            active.set(true);
            paused.set(true);
            withReward(REWARD_ACTIVE);
            await settle();
            expect(dialog()?.open).toBe(true);
        });

        it('does not open while an answer is open in Study, and opens when the quiet zone clears', async () => {
            await render(null);
            const quiet = TestBed.inject(QuietZone);
            quiet.set(true);
            withReward(REWARD_ACTIVE);
            await settle();
            expect(dialog()).toBeNull();
            quiet.set(false);
            await settle();
            expect(dialog()?.open).toBe(true);
        });

        it('does not open on the study page itself, and opens when the learner leaves it', async () => {
            await render(null);
            const router = TestBed.inject(Router);
            await router.navigateByUrl('/decks/d1/study');
            withReward(REWARD_ACTIVE);
            await settle();
            expect(dialog()).toBeNull();
            await router.navigateByUrl('/decks/d1');
            await settle();
            expect(dialog()?.open).toBe(true);
        });

        it.each(['first-entry', 'hint', 'promo'] as const)('does not open while the %s is on the screen, and opens when it goes', async surface => {
            await render(null);
            attention.report(surface, true);
            withReward(REWARD_ACTIVE);
            await settle();
            expect(dialog(), surface).toBeNull();
            attention.report(surface, false);
            await settle();
            expect(dialog()?.open, surface).toBe(true);
        });

        it('holds back the louder-than-toast surfaces below it (a promo window, a milestone toast) while it is open, and frees them when it is gone', async () => {
            await render();
            expect(attention.canShow('promo')).toBe(false);
            expect(attention.canShow('milestone')).toBe(false);
            root.querySelector<HTMLButtonElement>('.actions button')!.click();
            await settle();
            expect(attention.canShow('milestone')).toBe(true);
        });

        it('does not stack over another modal dialog, and looks again every second', async () => {
            vi.useFakeTimers();
            await render(null);
            const other = document.createElement('dialog');
            document.body.append(other);
            other.setAttribute('open', '');
            withReward(REWARD_ACTIVE);
            await settle();
            expect(dialog()).toBeNull();
            await vi.advanceTimersByTimeAsync(1_000);
            await settle();
            expect(dialog()).toBeNull();
            other.removeAttribute('open');
            await vi.advanceTimersByTimeAsync(1_000);
            await settle();
            expect(dialog()?.open).toBe(true);
        });

        it('does not open for a learner who is not signed in', async () => {
            await render(null);
            status.set('anonymous');
            withReward(REWARD_ACTIVE);
            await settle();
            expect(dialog()).toBeNull();
        });
    });

    it('opens for a learner who hid «Путь»: it is a gift with an end date, not a milestone toast', async () => {
        localStorage.setItem('mnema.path.hidden.acc-1', '1');
        await render();
        expect(dialog()?.open).toBe(true);
    });

    it('lets go of the coordinator when the page goes', async () => {
        await render();
        expect(attention.speaking()).toBe('reward');
        fixture.destroy();
        expect(attention.visible()).toEqual([]);
    });
});
