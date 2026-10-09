import { HttpHeaders } from '@angular/common/http';

/**
 * A `GET /api/plans` body as the Learning service answers it for a Free account with the Max teaser on. Mirrors
 * `PlansView` of the backend (prices: 449 / 990 / 1900 a month, year discounts 5 / 10 / 10 percent).
 */
export function plansBody(options: {
    readonly teaser?: boolean; readonly current?: string; readonly source?: string; readonly experiments?: Record<string, string>;
    readonly pendingDiscount?: Record<string, unknown> | null; readonly checkout?: 'AVAILABLE' | 'UNAVAILABLE';
} = {}): Record<string, any> {
    const teaser = options.teaser ?? true;
    const table = (materials: [number, number], voiceMonth: number | null, voiceDay: number, checksMonth: number, checksDay: number,
        podcasts: number, images: number, factChecks: number, smart: [number, string]): Record<string, any> => ({
        materialsPerMonth: { min: materials[0], max: materials[1] }, voiceMinutesPerMonth: voiceMonth, voiceMinutesPerDay: voiceDay,
        answerChecksPerMonth: checksMonth, answerChecksPerDay: checksDay, podcastsPerMonth: podcasts, qualityImagesPerMonth: images,
        factChecksPerMonth: factChecks, smartPlans: { limit: smart[0], window: smart[1] }
    });
    const plans: Record<string, any>[] = [
        { plan: 'FREE', availability: 'AVAILABLE', priceRub: { month: 0, year: 0 }, perDayRub: 0, yearDiscountPercent: 0,
            highlights: ['≈ 2 материала с ИИ в месяц', 'Голос: 60 мин в месяц', 'Проверка ответов: 50 в месяц'],
            allowances: table([2, 2], 60, 10, 50, 5, 0, 0, 0, [0, 'MONTH']), recommendedFor: [] },
        { plan: 'PLUS', availability: 'AVAILABLE', priceRub: { month: 449, year: 5119 }, perDayRub: 15, yearDiscountPercent: 5,
            highlights: ['≈ 12–17 материалов с ИИ в месяц', 'Голос: 300 мин в месяц', 'Проверка ответов: 500 в месяц'],
            allowances: table([12, 17], 300, 30, 500, 40, 2, 0, 2, [4, 'MONTH']), recommendedFor: ['INTERVIEW', 'LANGUAGE', 'SELF'] },
        { plan: 'PRO', availability: 'AVAILABLE', priceRub: { month: 990, year: 10692 }, perDayRub: 33, yearDiscountPercent: 10,
            highlights: ['≈ 29–39 материалов с ИИ в месяц', 'Голос: 600 мин в месяц', 'Проверка ответов: 1000 в месяц'],
            allowances: table([29, 39], 600, 60, 1000, 80, 6, 10, 5, [1, 'WEEK']), recommendedFor: ['EXAMS', 'WORK'] }
    ];
    if (teaser) {
        plans.push({ plan: 'MAX', availability: 'TEASER', priceRub: { month: 1900, year: 20520 }, perDayRub: 63, yearDiscountPercent: 10,
            highlights: ['≈ 63–85 материалов с ИИ в месяц', 'Голос: без месячного лимита', 'Проверка ответов: 1500 в месяц'],
            allowances: table([63, 85], null, 120, 1500, 120, 15, 25, 12, [8, 'MONTH']), recommendedFor: [] });
    }
    return {
        current: { plan: options.current ?? 'FREE', period: 'MONTH', validUntil: '2026-10-31T21:00:00Z', autoRenew: false,
            source: options.source ?? 'CONFIG' },
        checkout: options.checkout ?? 'UNAVAILABLE',
        plans, experiments: options.experiments ?? {}, pendingDiscount: options.pendingDiscount ?? null
    };
}

export const plansHeaders = new HttpHeaders({ 'Cache-Control': 'private, no-store' });
