import { UsageSnapshot } from './usage.models';
import { calendarDay, describeUsage } from './usage-view';

const NBSP = '\u00a0';

function snapshot(overrides: Partial<UsageSnapshot> = {}): UsageSnapshot {
    const bucket = { used: 0, limit: 100, usedToday: 0, limitToday: 10, warn: false };
    return {
        rateCardVersion: 'rc-v1', plan: 'PRO',
        period: { periodId: '2026-10', start: '2026-09-30T21:00:00Z', end: '2026-10-31T21:00:00Z' },
        credits: { total: 820, unlocked: 820, used: 100, reserved: 0, remaining: 720, percentUsed: 12 },
        dailyBurst: null, weeklyUnlock: null, fairUse: { stt: bucket, assessment: bucket }, updatedAt: '2026-10-02T09:00:00Z',
        ...overrides
    };
}

describe('describeUsage', () => {
    it('formats days in the account calendar zone, not the reader zone', () => {
        // 21:00 UTC is already midnight of the next day in Europe/Moscow.
        expect(calendarDay('2026-10-04T21:00:00Z')).toBe('5 октября');
        expect(calendarDay('2026-12-31T21:00:00Z')).toBe('1 января');
    });

    it('names the month the period starts in, in Moscow time', () => {
        expect(describeUsage(snapshot()).label).toBe('ИИ в октябре');
        const december = snapshot({ period: { periodId: '2026-12', start: '2026-11-30T21:00:00Z', end: '2026-12-31T21:00:00Z' } });
        expect(describeUsage(december).label).toBe('ИИ в декабре');
    });

    it('shows reserved credits, a deferred burst and rounds «≈ N материалов» down', () => {
        const usage = describeUsage(snapshot({
            credits: { total: 820, unlocked: 820, used: 100, reserved: 20, remaining: 699, percentUsed: 15 },
            dailyBurst: { limitCredits: 287, debitedTodayCredits: 287, remainingTodayCredits: 0,
                resetsAt: '2026-10-02T21:00:00Z', deferredUntil: '2026-10-02T21:00:00Z' }
        }));
        expect(usage).toMatchObject({ reserved: 20, remaining: { count: 69 }, unlocked: null,
            burstNote: 'Дневной предел расхода достигнут: оставшаяся работа продолжится 3 октября.' });
    });

    it('lists a fair-use counter only when the server flags it above 80 %', () => {
        const quiet = { used: 40, limit: 100, usedToday: 1, limitToday: 10, warn: false };
        const loud = { used: 85, limit: 100, usedToday: 2, limitToday: null, warn: true };
        expect(describeUsage(snapshot({ fairUse: { stt: quiet, assessment: quiet } })).counters).toEqual([]);
        expect(describeUsage(snapshot({ fairUse: { stt: quiet, assessment: loud } })).counters).toEqual([
            { id: 'assessment', text: `Проверка ответов ИИ: 85${NBSP}из${NBSP}100${NBSP}ответов за месяц` }]);
        // No monthly limit (MAX speech): nothing to count towards.
        expect(describeUsage(snapshot({ fairUse: { stt: { ...loud, limit: null }, assessment: quiet } })).counters).toEqual([]);
    });

    it('draws no ticks for a degenerate bar', () => {
        const usage = describeUsage(snapshot({ credits: { total: 0, unlocked: 0, used: 0, reserved: 0, remaining: 0, percentUsed: 0 },
            weeklyUnlock: { portions: [13], unlockedPortions: 0, nextUnlockAt: null, nextPortionCredits: null } }));
        expect(usage.ticks).toEqual([]);
        expect(usage.nextUnlock).toBeNull();
    });
});
