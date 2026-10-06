import { CALENDAR_ZONE, calendarDay } from '../usage/usage-view';
import { GOAL_COPY, copyFor } from '../goal/goal-copy';
import { LearningGoal } from '../goal/goal.models';
import { PendingDiscount, PlanAllowances, PlanEntry, PlanId, PlanPeriod, PlansCatalog, PlansCurrent } from './plans.models';

const NBSP = '\u00a0';
const RUBLES = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });
const DAY_MONTH = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', timeZone: CALENDAR_ZONE });
const YEAR = new Intl.DateTimeFormat('ru-RU', { year: 'numeric', timeZone: CALENDAR_ZONE });
/** The renewal of a monthly plan is every 30 days, as the checkbox says; a yearly one is a year after. */
const MONTHLY_STEP_DAYS = 30;
const DAY_MS = 86_400_000;

export const PLAN_LABEL: Readonly<Record<PlanId, string>> = { FREE: 'Free', PLUS: 'Plus', PRO: 'Pro', MAX: 'Max' };

/** «5 119 ₽»: grouped with no-break spaces so a price never splits across lines. */
export function rub(amount: number): string {
    return `${RUBLES.format(amount).replace(/\s/gu, NBSP)}${NBSP}₽`;
}

/** The yearly price in the terms a reader compares with a month: per month, and how much a year saves. */
export function yearTerms(entry: PlanEntry): { readonly year: number; readonly perMonth: number; readonly savings: number } {
    const { month, year } = entry.priceRub;
    return { year, perMonth: Math.round(year / 12), savings: Math.max(0, month * 12 - year) };
}

export interface PriceText {
    /** The big figure: «449 ₽ в месяц», «5 119 ₽ в год». */
    readonly main: string;
    /** The smaller line under it, or null. */
    readonly note: string | null;
    /** «Экономия 269 ₽ в год» for a yearly price with a discount. */
    readonly saving: string | null;
}

export function priceText(entry: PlanEntry, period: PlanPeriod): PriceText {
    if (entry.priceRub.month === 0) return { main: `0${NBSP}₽`, note: 'всегда бесплатно', saving: null };
    if (period === 'MONTH') return { main: `${rub(entry.priceRub.month)} в${NBSP}месяц`, note: `≈ ${rub(entry.perDayRub)} в${NBSP}день`, saving: null };
    const terms = yearTerms(entry);
    return {
        main: `${rub(terms.year)} в${NBSP}год`, note: `≈ ${rub(terms.perMonth)} в${NBSP}месяц`,
        saving: terms.savings > 0 ? `Экономия ${rub(terms.savings)} в${NBSP}год` : null
    };
}

/** The line of the year switch: the span of discounts across the paid tiers that are for sale. */
export function yearSwitchHint(plans: readonly PlanEntry[]): string {
    const discounts = plans.filter(plan => plan.availability === 'AVAILABLE' && plan.priceRub.month > 0)
        .map(plan => plan.yearDiscountPercent).filter(percent => percent > 0);
    if (discounts.length === 0) return 'Платите раз в год.';
    const low = Math.min(...discounts);
    const high = Math.max(...discounts);
    return `Платите раз в год и экономьте ${low === high ? low : `${low}–${high}`}${NBSP}%.`;
}

export interface Cta {
    readonly text: string;
    /** The current plan or a teaser: nothing to do. */
    readonly disabled: boolean;
    /**
     * `stay` keeps the free plan and leaves the page; `buy` opens the notice that payments are being connected;
     * `downgrade` opens the notice that the plan returns to Free when the paid period ends.
     */
    readonly action: 'stay' | 'buy' | 'downgrade' | 'none';
}

export function cta(entry: PlanEntry, period: PlanPeriod, current: PlanId): Cta {
    if (entry.availability === 'TEASER') return { text: 'Тариф в работе', disabled: true, action: 'none' };
    if (entry.plan === 'FREE') {
        return current === 'FREE' ? { text: 'Остаться на Free', disabled: false, action: 'stay' }
            : { text: 'Вернуться на Free', disabled: false, action: 'downgrade' };
    }
    if (entry.plan === current) return { text: 'Это ваш тариф', disabled: true, action: 'none' };
    const price = period === 'MONTH' ? `${rub(entry.priceRub.month)} в${NBSP}месяц` : `${rub(entry.priceRub.year)} в${NBSP}год`;
    return { text: `Перейти на ${PLAN_LABEL[entry.plan]} — ${price}`, disabled: false, action: 'buy' };
}

export const PAYMENT_NOTICE = 'Оплату подключаем: тариф можно будет оформить здесь же. Пока доступен промокод.';
export const DOWNGRADE_NOTICE = 'Тариф вернётся на Free после окончания оплаченного периода.';

/** «5 ноября» (this year's date has no year; a yearly renewal names its year). */
export function renewalDate(now: Date, period: PlanPeriod): string {
    if (period === 'MONTH') return DAY_MONTH.format(new Date(now.getTime() + MONTHLY_STEP_DAYS * DAY_MS));
    const next = new Date(now.getTime());
    next.setUTCFullYear(next.getUTCFullYear() + 1);
    return `${DAY_MONTH.format(next)} ${YEAR.format(next)}`;
}

/** The text of the auto-renew checkbox: the amount and the date, always spelled out. The box itself is never ticked for the reader. */
export function autoRenewText(entry: PlanEntry, period: PlanPeriod, now: Date): string {
    const date = renewalDate(now, period);
    return period === 'MONTH'
        ? `Продлевать автоматически: ${rub(entry.priceRub.month)} каждые 30${NBSP}дней, следующее списание ${date}`
        : `Продлевать автоматически: ${rub(entry.priceRub.year)} раз в${NBSP}год, следующее списание ${date}`;
}

/** «Plus до 20 октября, без автопродления» for a tier a promo or a payment gave; null on the plan the account has by default. */
export function entitlementText(current: PlansCurrent): string | null {
    if (current.source === 'CONFIG') return null;
    return `${PLAN_LABEL[current.plan]} до ${calendarDay(current.validUntil)}${current.autoRenew ? '' : ', без автопродления'}`;
}

/** «Скидка 20 % на Plus применится к оплате до 31 октября»: what a promo earned and until when. */
export function discountText(discount: PendingDiscount): string {
    const target = discount.plan === null ? '' : ` на ${PLAN_LABEL[discount.plan]}`;
    return `Скидка ${discount.percent}${NBSP}%${target} применится к оплате до ${calendarDay(discount.validUntil)}`;
}

export function plansHeading(goal: LearningGoal | null): string {
    return copyFor(goal).plansHeading;
}

/** «Рекомендуем для подготовки к экзаменам» on the tier a goal points at; null for no goal or no such tier. */
export function recommendation(catalog: PlansCatalog, goal: LearningGoal | null): { readonly plan: PlanId; readonly badge: string } | null {
    if (goal === null) return null;
    const entry = catalog.plans.find(plan => plan.availability === 'AVAILABLE' && plan.recommendedFor.includes(goal));
    return entry === undefined ? null : { plan: entry.plan, badge: `Рекомендуем для ${GOAL_COPY[goal].recommendedFor}` };
}

/** `?from=limit&used=92`: «Использовано 92 % лимита». Display only; any other value says nothing and changes nothing. */
export function limitContext(from: string | null, used: string | null): string | null {
    if (from !== 'limit') return null;
    if (used === null || !/^\d{1,3}$/u.test(used) || Number(used) > 100) return 'Лимит ИИ исчерпан или близок к концу.';
    return `Использовано ${Number(used)}${NBSP}% лимита.`;
}

export interface CompareRow {
    readonly id: string;
    readonly label: string;
    readonly cell: (allowances: PlanAllowances) => string;
}

const NONE = 'Нет';

function count(value: number, unit = ''): string { return value === 0 ? NONE : `${value}${unit}`; }

export const COMPARE_ROWS: readonly CompareRow[] = [
    { id: 'materials', label: 'Материалы с ИИ в месяц', cell: a => a.materialsPerMonth.min === a.materialsPerMonth.max
        ? `≈${NBSP}${a.materialsPerMonth.max}` : `≈${NBSP}${a.materialsPerMonth.min}–${a.materialsPerMonth.max}` },
    { id: 'voice-month', label: 'Голос в месяц', cell: a => a.voiceMinutesPerMonth === null ? 'Без месячного лимита'
        : `${a.voiceMinutesPerMonth}${NBSP}мин` },
    { id: 'voice-day', label: 'Голос в день', cell: a => `до ${a.voiceMinutesPerDay}${NBSP}мин` },
    { id: 'checks-month', label: 'Проверка ответов в месяц', cell: a => `${a.answerChecksPerMonth}` },
    { id: 'checks-day', label: 'Проверка ответов в день', cell: a => `до ${a.answerChecksPerDay}` },
    { id: 'podcasts', label: 'Подкасты в месяц', cell: a => count(a.podcastsPerMonth) },
    { id: 'images', label: 'Качественные изображения в месяц', cell: a => count(a.qualityImagesPerMonth) },
    { id: 'factcheck', label: 'Глубокая проверка фактов в месяц', cell: a => count(a.factChecksPerMonth) },
    { id: 'smart', label: 'Умный план', cell: a => a.smartPlans.limit === 0 ? NONE
        : `${a.smartPlans.limit} в${NBSP}${a.smartPlans.window === 'WEEK' ? 'неделю' : 'месяц'}` }
];
