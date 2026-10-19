import contract from '../../../../../contracts/onboarding/journey.json';
import { PathReward } from './onboarding.models';
import { rewardDate, rewardGift, rewardPlanLabel, rewardPlansQuery } from './reward-format';

const NBSP = ' ';
const reward = (patch: Partial<PathReward> = {}): PathReward => ({ ...(contract.views.rewardReady.reward as PathReward), ...patch });

describe('reward format', () => {
    describe('rewardDate', () => {
        it('is the day, the month in words and the minute in Moscow time, with «МСК» after a no-break space', () => {
            // 18:41 UTC is 21:41 in Moscow (UTC+3, no daylight saving).
            expect(rewardDate('2026-10-29T18:41:00Z')).toBe(`29 октября, 21:41${NBSP}МСК`);
        });

        it('takes the Moscow day, not the UTC one, and pads the hour', () => {
            expect(rewardDate('2026-10-28T21:05:00Z')).toBe(`29 октября, 00:05${NBSP}МСК`);
            expect(rewardDate('2026-12-31T22:30:00Z')).toBe(`1 января, 01:30${NBSP}МСК`);
        });

        it('does not depend on the zone of the machine and has no seconds', () => {
            expect(rewardDate('2026-10-29T18:41:59+00:00')).toBe(`29 октября, 21:41${NBSP}МСК`);
            expect(rewardDate('2026-10-29T21:41:00+03:00')).toBe(`29 октября, 21:41${NBSP}МСК`);
        });

        it('is empty for an unreadable instant', () => {
            expect(rewardDate('soon')).toBe('');
        });
    });

    describe('rewardGift', () => {
        it('is the approved sentence with the percent of the campaign and the date of the end', () => {
            expect(rewardGift(reward())).toBe(`Ваш подарок: −10${NBSP}% на подписку до 29 октября, 21:41${NBSP}МСК.`);
        });

        it('names the plan of a discount that applies to one plan', () => {
            const offer = { type: 'DISCOUNT_PERCENT', percent: 15, plan: 'PRO', days: null } as const;
            expect(rewardGift(reward({ offer }))).toBe(`Ваш подарок: −15${NBSP}% на подписку Pro до 29 октября, 21:41${NBSP}МСК.`);
        });

        it('names the plan and the days of a tier, with the form of «день» the number asks for', () => {
            const tier = (days: number): PathReward => reward({ offer: { type: 'TIER_DAYS', percent: null, plan: 'PLUS', days } });
            expect(rewardGift(tier(30))).toBe(`Ваш подарок: Plus на 30${NBSP}дней — до 29 октября, 21:41${NBSP}МСК.`);
            expect(rewardGift(tier(1))).toContain(`на 1${NBSP}день`);
            expect(rewardGift(tier(3))).toContain(`на 3${NBSP}дня`);
            expect(rewardGift(tier(21))).toContain(`на 21${NBSP}день`);
            expect(rewardGift(tier(11))).toContain(`на 11${NBSP}дней`);
        });

        it('has no countdown, seconds or urgency in any form', () => {
            const sentences = [rewardGift(reward()), rewardGift(reward({ offer: { type: 'TIER_DAYS', percent: null, plan: 'PRO', days: 7 } }))];
            for (const sentence of sentences) {
                expect(sentence).not.toMatch(/\d+:\d+:\d+|осталось|успей|только сегодня|скорее|последн|спешите|торопитесь/iu);
            }
        });
    });

    it('labels the plans the way the plans page does', () => {
        expect([rewardPlanLabel('PLUS'), rewardPlanLabel('PRO')]).toEqual(['Plus', 'Pro']);
    });

    it('drops the dashes of the code for the link to the plans', () => {
        expect(rewardPlansQuery('K7PM-W2QX-H9RT')).toEqual({ promo: 'K7PMW2QXH9RT' });
    });
});
