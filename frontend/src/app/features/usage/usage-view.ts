import { plural } from '../../core/notifications/notification-presenter';
import { RuPluralForms, UsageRemaining } from '../../shared/usage-meter.component';
import { UsageFairUseBucket, UsagePlan, UsageSnapshot } from './usage.models';

/**
 * Credits of one medium material in the rate card (`contracts/usage/rate-card-v1.json`, `MATERIAL_MEDIUM`). The usage
 * response has no «materials left» field, so «≈ N материалов» is remaining credits ÷ this weight; a spec keeps it equal
 * to the rate card.
 */
export const MATERIAL_MEDIUM_CREDITS = 10;

/** Every day, week and month boundary of the account is a calendar boundary in this zone (owner decision 2026-10-02). */
export const CALENDAR_ZONE = 'Europe/Moscow';

const PLAN_NAMES: Readonly<Record<UsagePlan, string>> = { FREE: 'Free', PLUS: 'Plus', PRO: 'Pro', MAX: 'Max' };
const MATERIALS: RuPluralForms = ['материал', 'материала', 'материалов'];
const MINUTES: RuPluralForms = ['минута', 'минуты', 'минут'];
const ANSWERS: RuPluralForms = ['ответ', 'ответа', 'ответов'];
/** «ИИ в октябре»: the prepositional case, which `Intl` does not produce. */
const MONTHS_IN: readonly string[] = ['январе', 'феврале', 'марте', 'апреле', 'мае', 'июне', 'июле', 'августе', 'сентябре',
    'октябре', 'ноябре', 'декабре'];
const NBSP = '\u00a0';

export interface UsageCounter {
    readonly id: 'stt' | 'assessment';
    readonly text: string;
}

/** Everything the profile block shows, derived from one `GET /api/usage` body. */
export interface UsageView {
    readonly planName: string;
    readonly label: string;
    readonly total: number;
    readonly used: number;
    readonly reserved: number;
    /** `null` on paid plans (the whole bar is open). */
    readonly unlocked: number | null;
    /** Cumulative Free portions as fractions of the bar; empty on paid plans. */
    readonly ticks: readonly number[];
    readonly remaining: UsageRemaining;
    readonly nextUnlock: string | null;
    readonly resetsOn: string;
    /** Paid plans only, while steps wait for the next day because the daily burst is reached. */
    readonly burstNote: string | null;
    /** Fair-use counters above 80 % of their monthly limit; empty means the whole list stays hidden. */
    readonly counters: readonly UsageCounter[];
}

/** «5 октября» in the calendar zone of the account, not the reader's. */
export function calendarDay(instant: string): string {
    return new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', timeZone: CALENDAR_ZONE }).format(Date.parse(instant));
}

export function describeUsage(usage: UsageSnapshot): UsageView {
    const { credits, weeklyUnlock, dailyBurst } = usage;
    const free = weeklyUnlock !== null;
    const month = Number(new Intl.DateTimeFormat('en-US', { month: 'numeric', timeZone: CALENDAR_ZONE })
        .format(Date.parse(usage.period.start)));
    let cumulative = 0;
    const ticks = weeklyUnlock === null || credits.total <= 0 ? [] : weeklyUnlock.portions.map(portion => {
        cumulative += portion;
        return cumulative / credits.total;
    }).filter(fraction => fraction > 0 && fraction < 1);
    const deferred = dailyBurst?.deferredUntil ?? null;
    return {
        planName: PLAN_NAMES[usage.plan],
        label: `ИИ в ${MONTHS_IN[month - 1] ?? 'этом месяце'}`,
        total: credits.total,
        used: credits.used,
        reserved: credits.reserved,
        unlocked: free ? credits.unlocked : null,
        ticks,
        remaining: { count: Math.floor(credits.remaining / MATERIAL_MEDIUM_CREDITS), forms: MATERIALS },
        nextUnlock: weeklyUnlock?.nextUnlockAt ? calendarDay(weeklyUnlock.nextUnlockAt) : null,
        resetsOn: calendarDay(usage.period.end),
        burstNote: deferred === null ? null
            : `Дневной предел расхода достигнут: оставшаяся работа продолжится ${calendarDay(deferred)}.`,
        counters: [
            counter('stt', 'Распознавание речи', usage.fairUse.stt, MINUTES),
            counter('assessment', 'Проверка ответов ИИ', usage.fairUse.assessment, ANSWERS)
        ].filter((entry): entry is UsageCounter => entry !== null)
    };
}

/** A quiet counter: shown only when the server says `warn` (above 80 % of the monthly limit) and there is a limit. */
function counter(id: UsageCounter['id'], name: string, bucket: UsageFairUseBucket, forms: RuPluralForms): UsageCounter | null {
    if (!bucket.warn || bucket.limit === null) return null;
    const today = bucket.limitToday === null ? '' : `, сегодня ${bucket.usedToday}${NBSP}из${NBSP}${bucket.limitToday}`;
    return { id, text: `${name}: ${bucket.used}${NBSP}из${NBSP}${bucket.limit}${NBSP}${plural(bucket.limit, forms)} за месяц${today}` };
}
