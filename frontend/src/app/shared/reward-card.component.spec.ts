import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { PathReward } from '../core/onboarding/onboarding.models';
import { REWARD_ACTIVE, REWARD_EXPIRED, REWARD_REDEEMED } from '../features/onboarding/onboarding-test-data';
import { RewardCardComponent } from './reward-card.component';

const plain = (text: string | null): string => (text ?? '').replace(/\u00a0/gu, ' ').trim();

@Component({ imports: [RewardCardComponent], template: '<app-reward-card [reward]="reward()" [level]="level()" />' })
class HostComponent {
    readonly reward = signal<PathReward | null>(REWARD_ACTIVE);
    readonly level = signal<2 | 3>(2);
}

describe('RewardCardComponent (the reward on the page of the path and in the profile)', () => {
    const render = async (reward: PathReward | null = REWARD_ACTIVE, level: 2 | 3 = 2) => {
        TestBed.configureTestingModule({ providers: [provideRouter([])] });
        const fixture = TestBed.createComponent(HostComponent);
        fixture.componentInstance.reward.set(reward);
        fixture.componentInstance.level.set(level);
        fixture.detectChanges();
        await fixture.whenStable();
        return { fixture, root: fixture.nativeElement as HTMLElement };
    };

    describe('while the reward is active', () => {
        it('says the path is done, what the gift is and when it ends as text, with the terms', async () => {
            const { root } = await render();
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Путь пройден');
            expect(plain(root.querySelector('h2')!.textContent)).toBe('Вы освоили Mnema полностью');
            expect(plain(root.querySelector('.gift')!.textContent)).toBe('Ваш подарок: −10 % на подписку до 29 октября, 21:41 МСК.');
            expect(plain(root.querySelector('.hint')!.textContent)).toBe('Предложение действует один раз. Условия и сочетание с промокодами — на странице тарифов.');
        });

        it('shows the code to copy, named, and links to the plans with the code (without dashes) for the promo field', async () => {
            const { root } = await render();
            expect(root.querySelector<HTMLInputElement>('app-reward-code input')!.value).toBe('K7PM-W2QX-H9RT');
            const link = root.querySelector<HTMLAnchorElement>('a.button.primary')!;
            expect(link.textContent).toBe('Выбрать тариф');
            expect(link.getAttribute('href')).toBe('/plans?promo=K7PM-W2QX-H9RT');
        });

        it('names itself by its heading and takes the heading level of the page it sits in', async () => {
            const { root } = await render(REWARD_ACTIVE, 3);
            expect(root.querySelector('h2')).toBeNull();
            const heading = root.querySelector('h3')!;
            expect(root.querySelector('section')!.getAttribute('aria-labelledby')).toBe(heading.id);
            expect(root.querySelector('section')!.classList.contains('nested')).toBe(true);
        });

        it('tells a tier by its plan and days, with no countdown or urgency', async () => {
            const tier: PathReward = { ...REWARD_ACTIVE, offer: { type: 'TIER_DAYS', percent: null, plan: 'PRO', days: 14 } };
            const { root } = await render(tier);
            expect(plain(root.querySelector('.gift')!.textContent)).toBe('Ваш подарок: Pro на 14 дней. Активировать до 29 октября, 21:41 МСК.');
            expect(root.textContent).not.toMatch(/\d+:\d+:\d+|осталось|успей|скорее|последн/iu);
        });
    });

    it('is one quiet line once the reward was redeemed, with no code and no link', async () => {
        const { root } = await render(REWARD_REDEEMED);
        expect(plain(root.textContent)).toBe('Подарок за «Путь» использован.');
        expect(root.querySelector('a, button, input, h2, h3')).toBeNull();
    });

    it.each([['expired', REWARD_EXPIRED], ['absent', null]] as const)('says nothing when the reward is %s: no message of a loss', async (_name, reward) => {
        const { root } = await render(reward);
        expect(plain(root.textContent)).toBe('');
        expect(root.querySelector('section, p')).toBeNull();
    });

    it('follows the reward when the view is read again: active, then redeemed, then gone', async () => {
        const { fixture, root } = await render();
        expect(root.querySelector('app-reward-code')).not.toBeNull();
        fixture.componentInstance.reward.set(REWARD_REDEEMED);
        fixture.detectChanges();
        expect(root.querySelector('app-reward-code')).toBeNull();
        expect(plain(root.textContent)).toBe('Подарок за «Путь» использован.');
        fixture.componentInstance.reward.set(REWARD_EXPIRED);
        fixture.detectChanges();
        expect(plain(root.textContent)).toBe('');
    });
});
