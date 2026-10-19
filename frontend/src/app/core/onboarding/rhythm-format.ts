import { onboardingText } from './onboarding-texts';

/**
 * Pure helpers of the weekly rhythm (OB-401): calendar dates as the contract sends them (`YYYY-MM-DD`, the learner's local day, no time
 * zone to convert) and the Russian forms of the sentences. The week is Monday first; nothing here reads the clock.
 */

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/u;
const DAY_MS = 86_400_000;
const WEEK_DAYS = 7;
const RUSSIAN_PLURALS = new Intl.PluralRules('ru');

/** The ordinals of the day of the week the learner studied on, from the prototype's helper (design/prototype/onboarding/texts.js). */
const ORDINALS = ['Первый', 'Второй', 'Третий', 'Четвёртый', 'Пятый', 'Шестой', 'Седьмой'] as const;

/** The day count of a calendar date, or `null` when the text is not a real date of the calendar (`2026-02-30`). */
function dayNumber(value: string): number | null {
    const match = ISO_DATE.exec(value);
    if (match === null) return null;
    const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
    const time = Date.UTC(year, month - 1, day);
    const date = new Date(time);
    return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day ? time / DAY_MS : null;
}

export function isIsoDate(value: unknown): value is string {
    return typeof value === 'string' && dayNumber(value) !== null;
}

/** Monday is the first day of the week of the contract (`constants.rhythm.weekStartsOn`). */
export function isMonday(value: string): boolean {
    const day = dayNumber(value);
    // 1970-01-01 was a Thursday: day 0 is a Thursday, so a Monday has `day % 7 === 4`.
    return day !== null && ((day % WEEK_DAYS) + WEEK_DAYS) % WEEK_DAYS === 4;
}

/** The date `offset` days after `value`; `value` must be a real date. */
export function addDays(value: string, offset: number): string {
    const day = dayNumber(value);
    if (day === null) throw new RangeError('Not a calendar date.');
    return new Date((day + offset) * DAY_MS).toISOString().slice(0, 10);
}

/** The seven dates of the week that starts on `weekStart`, Monday first. */
export function weekDates(weekStart: string): readonly string[] {
    return Array.from({ length: WEEK_DAYS }, (_, index) => addDays(weekStart, index));
}

/** Whether `value` lies in `[weekStart, weekStart + 7)`; both must be real dates. */
export function isInWeek(weekStart: string, value: string): boolean {
    const start = dayNumber(weekStart);
    const day = dayNumber(value);
    return start !== null && day !== null && day >= start && day < start + WEEK_DAYS;
}

/** «2 дня», «5 дней»: the form of «день» a number asks for, with a non-breaking space. */
export function daysLabel(count: number): string {
    const words: Record<Intl.LDMLPluralRule, string> = { zero: 'дней', one: 'день', two: 'дня', few: 'дня', many: 'дней', other: 'дня' };
    return `${count}\u00a0${words[RUSSIAN_PLURALS.select(count)]}`;
}

/** «Третий» for the third day of the week the learner studied on (`count` 1–7). */
export function dayOrdinal(count: number): string {
    return ORDINALS[Math.min(WEEK_DAYS, Math.max(1, count)) - 1];
}

type WeekdayKey = `rhythm.week.on.${1 | 2 | 3 | 4 | 5 | 6 | 7}`;

/** «Эта неделя: занятия во вторник, в четверг и в пятницу», or the sentence of a week without a session. The one thing a screen reader says of the week. */
export function weekSummary(weekStart: string, studied: readonly string[]): string {
    const names = weekDates(weekStart).flatMap((date, index) => studied.includes(date) ? [onboardingText(`rhythm.week.on.${index + 1}` as WeekdayKey)] : []);
    if (names.length === 0) return onboardingText('rhythm.week.summary.none');
    const list = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} ${onboardingText('rhythm.week.and')} ${names[names.length - 1]}`;
    return onboardingText('rhythm.week.summary', { days: list });
}
