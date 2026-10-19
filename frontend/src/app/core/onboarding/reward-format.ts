import { onboardingText } from './onboarding-texts';
import { PathReward, RewardPlan } from './onboarding.models';
import { daysLabel } from './rhythm-format';

const NBSP = ' ';
const PLAN_LABEL: Readonly<Record<RewardPlan, string>> = { PLUS: 'Plus', PRO: 'Pro' };

/** The product calendar zone of promo and billing (`learning.usage.calendar-zone`): the end of an offer is always shown in it. */
const REWARD_ZONE = 'Europe/Moscow';
const MOSCOW_PARTS = new Intl.DateTimeFormat('ru', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit', timeZone: REWARD_ZONE, hourCycle: 'h23' });

/**
 * The end of an offer as text, «29 октября, 21:41 МСК»: the day, the month in words and the minute, in Moscow time, with no seconds. The parts
 * are joined here and not taken from `Intl`'s own pattern, because the pattern differs between engines («29 октября в 21:41» in newer ICU). An
 * unreadable instant gives an empty string.
 */
export function rewardDate(instant: string): string {
    const time = Date.parse(instant);
    if (Number.isNaN(time)) return '';
    const parts = new Map(MOSCOW_PARTS.formatToParts(time).map(part => [part.type, part.value] as const));
    return `${parts.get('day')} ${parts.get('month')}, ${parts.get('hour')}:${parts.get('minute')}${NBSP}МСК`;
}

export function rewardPlanLabel(plan: RewardPlan): string {
    return PLAN_LABEL[plan];
}

/**
 * The gift sentence of a reward: a discount («Ваш подарок: −10 % на подписку до 29 октября, 21:41 МСК.», with the plan when the campaign names
 * one) or a tier for some days («Ваш подарок: Plus на 30 дней. Активировать до …»). The date is the end of the offer; there is no countdown.
 */
export function rewardGift(reward: PathReward): string {
    const date = rewardDate(reward.expiresAt);
    const offer = reward.offer;
    if (offer.type === 'TIER_DAYS') return onboardingText('reward.gift.tier', { plan: PLAN_LABEL[offer.plan], days: daysLabel(offer.days), date });
    return offer.plan === null
        ? onboardingText('reward.gift', { percent: offer.percent, date })
        : onboardingText('reward.gift.plan', { percent: offer.percent, plan: PLAN_LABEL[offer.plan], date });
}

/**
 * The query of the link to the plans page: the plans page fills its promo field from `?promo=` (and takes the parameter off the address
 * again) and never applies it for the reader. The code keeps the dashes of its display form, so the field shows the same code as the card.
 */
export function rewardPlansQuery(code: string): { readonly promo: string } {
    return { promo: code };
}
