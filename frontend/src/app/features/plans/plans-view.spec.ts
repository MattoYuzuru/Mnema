import { parsePlans } from './plans-api.service';
import {
    COMPARE_ROWS, autoRenewText, cta, discountText, discountFor, discountedPrice, oneMonthLater, oneOffText, entitlementText, limitContext, plansHeading, priceText, recommendation, renewalDate, rub, yearSwitchHint, yearTerms
} from './plans-view';
import { plansBody } from './plans-test-data';

const NBSP = '\u00a0';
const catalog = parsePlans(plansBody());
const [free, plus, pro, max] = catalog.plans;

describe('plans view', () => {
    it('formats rubles with no-break spaces', () => {
        expect(rub(449)).toBe(`449${NBSP}₽`);
        expect(rub(5119)).toBe(`5${NBSP}119${NBSP}₽`);
    });

    it('computes the yearly terms from the server price: per month and the saving', () => {
        expect(yearTerms(plus)).toEqual({ year: 5119, perMonth: 427, savings: 269 });
        expect(yearTerms(pro)).toEqual({ year: 10692, perMonth: 891, savings: 1188 });
        expect(yearTerms(free)).toEqual({ year: 0, perMonth: 0, savings: 0 });
    });

    it('shows the monthly price with the daily comparison and the yearly one with the saving', () => {
        expect(priceText(plus, 'MONTH')).toEqual({ main: `449${NBSP}₽ в${NBSP}месяц`, note: `≈ 15${NBSP}₽ в${NBSP}день`, saving: null });
        expect(priceText(plus, 'YEAR')).toEqual({ main: `5${NBSP}119${NBSP}₽ в${NBSP}год`, note: `≈ 427${NBSP}₽ в${NBSP}месяц`,
            saving: `Экономия 269${NBSP}₽ в${NBSP}год` });
        expect(priceText(free, 'YEAR').main).toBe(`0${NBSP}₽`);
        expect(priceText(free, 'YEAR').saving).toBeNull();
    });

    it('names the span of discounts on the year switch from the tiers that are for sale', () => {
        expect(yearSwitchHint(catalog.plans)).toBe(`Платите раз в год и экономьте 5–10${NBSP}%.`);
        expect(yearSwitchHint([free])).toBe('Платите раз в год.');
        expect(yearSwitchHint([pro])).toBe(`Платите раз в год и экономьте 10${NBSP}%.`);
    });

    it('changes the call to action with the choice', () => {
        expect(cta(free, 'MONTH', 'FREE')).toEqual({ text: 'Остаться на Free', disabled: false, action: 'stay' });
        expect(cta(plus, 'MONTH', 'FREE').text).toBe(`Перейти на Plus — 449${NBSP}₽ в${NBSP}месяц`);
        expect(cta(plus, 'YEAR', 'FREE').text).toBe(`Перейти на Plus — 5${NBSP}119${NBSP}₽ в${NBSP}год`);
        expect(cta(plus, 'MONTH', 'PLUS')).toEqual({ text: 'Это ваш тариф', disabled: true, action: 'none' });
        expect(cta(free, 'MONTH', 'PRO')).toEqual({ text: 'Вернуться на Free', disabled: false, action: 'downgrade' });
        expect(cta(max, 'MONTH', 'FREE')).toEqual({ text: 'Тариф в работе', disabled: true, action: 'none' });
    });

    it('spells out the amount and the date of the auto-renewal', () => {
        const now = new Date('2026-10-06T09:00:00Z');
        expect(renewalDate(now, 'MONTH')).toBe('5 ноября');
        expect(renewalDate(now, 'YEAR')).toBe('6 октября 2027');
        expect(autoRenewText(plus, 'MONTH', now)).toBe(`Продлевать автоматически: 449${NBSP}₽ каждые 30${NBSP}дней, следующее списание 5 ноября`);
        expect(autoRenewText(plus, 'YEAR', now)).toBe(`Продлевать автоматически: 5${NBSP}119${NBSP}₽ раз в${NBSP}год, следующее списание 6 октября 2027`);
    });

    it('words the heading by goal and stays neutral without one', () => {
        expect(plansHeading('EXAMS')).toMatch(/^Подготовка к экзаменам: /u);
        expect(plansHeading(null)).toBe('Тарифы Mnema');
    });

    it('recommends the tier the server points at for the goal, as text', () => {
        expect(recommendation(catalog, 'EXAMS')).toEqual({ plan: 'PRO', badge: 'Рекомендуем для подготовки к экзаменам' });
        expect(recommendation(catalog, 'LANGUAGE')?.plan).toBe('PLUS');
        expect(recommendation(catalog, null)).toBeNull();
    });

    it('reads the limit context as display only', () => {
        expect(limitContext('limit', '92')).toBe(`Использовано 92${NBSP}% лимита.`);
        expect(limitContext('limit', null)).toBe('Лимит ИИ исчерпан или близок к концу.');
        expect(limitContext('limit', '9999')).toBe('Лимит ИИ исчерпан или близок к концу.');
        expect(limitContext('limit', '<b>')).toBe('Лимит ИИ исчерпан или близок к концу.');
        expect(limitContext('other', '92')).toBeNull();
        expect(limitContext(null, '92')).toBeNull();
    });

    it('renders every comparison row for every tier, with a word for what a tier does not offer', () => {
        const cells = (plan: typeof free) => COMPARE_ROWS.map(row => row.cell(plan.allowances));
        expect(cells(free)).toContain('Нет');
        expect(cells(plus)).toContain(`300${NBSP}мин`);
        expect(cells(plus)).toContain(`4 в${NBSP}месяц`);
        expect(cells(pro)).toContain(`1 в${NBSP}неделю`);
        expect(cells(max)).toContain('Без месячного лимита');
        expect(cells(max)).toContain(`до 120${NBSP}мин`);
        expect(cells(plus)).toContain(`≈${NBSP}12–17`);
        expect(cells(free)).toContain(`≈${NBSP}2`);
    });

    it('states a tier from a promo or a payment with its end and no renewal, and nothing for the default plan', () => {
        const current = catalog.current;
        expect(entitlementText(current)).toBeNull();
        expect(entitlementText({ ...current, plan: 'PLUS', source: 'PROMO', validUntil: '2026-10-19T21:00:00Z' })).toBe('Plus до 20 октября, без автопродления');
        expect(entitlementText({ ...current, plan: 'PRO', source: 'BILLING', autoRenew: true, validUntil: '2026-10-19T21:00:00Z' })).toBe('Pro до 20 октября');
    });

    it('words a pending discount with its plan and its end', () => {
        expect(discountText({ percent: 20, plan: 'PLUS', validUntil: '2026-10-31T20:59:59Z' })).toBe(`Скидка 20${NBSP}% на Plus применится к оплате до 31 октября`);
        expect(discountText({ percent: 10, plan: null, validUntil: '2026-10-31T20:59:59Z' })).toBe(`Скидка 10${NBSP}% применится к оплате до 31 октября`);
    });

    describe('checkout call to action', () => {
        const offer = { discount: null };
        const discount = { percent: 20, plan: 'PRO' as const, validUntil: '2026-10-31T20:59:59Z' };

        it('pays the month price, never the year, and leaves the old text when payments are not on', () => {
            expect(cta(plus, 'MONTH', 'FREE', offer)).toEqual({ text: `Оплатить Plus — 449${NBSP}₽`, disabled: false, action: 'checkout' });
            expect(cta(plus, 'MONTH', 'FREE')).toMatchObject({ action: 'buy' });
            expect(cta(plus, 'YEAR', 'FREE', offer)).toEqual({ text: `Перейти на Plus — 5${NBSP}119${NBSP}₽ в${NBSP}год`, disabled: false, action: 'checkout-year' });
        });

        it('names the discounted amount against the list price only for a tier the discount applies to', () => {
            expect(cta(pro, 'MONTH', 'FREE', { discount }).text).toBe(`Оплатить Pro — 792${NBSP}₽ вместо 990${NBSP}₽`);
            expect(cta(plus, 'MONTH', 'FREE', { discount }).text).toBe(`Оплатить Plus — 449${NBSP}₽`);
            expect(cta(plus, 'MONTH', 'FREE', { discount: { ...discount, plan: null } }).text).toBe(`Оплатить Plus — 359${NBSP}₽ вместо 449${NBSP}₽`);
        });

        it('leaves Free, the current tier and a teaser alone', () => {
            expect(cta(free, 'MONTH', 'PLUS', offer).action).toBe('downgrade');
            expect(cta(plus, 'MONTH', 'PLUS', offer)).toMatchObject({ disabled: true, action: 'none' });
            expect(cta(max, 'MONTH', 'FREE', offer)).toMatchObject({ disabled: true, action: 'none' });
        });

        it('rounds a discounted price half up', () => {
            expect(discountedPrice(990, 20)).toBe(792);
            expect(discountedPrice(449, 20)).toBe(359);
            expect(discountedPrice(449, 15)).toBe(382);
            expect(discountedPrice(990, 5)).toBe(941);
            expect(discountedPrice(1, 50)).toBe(1);
            expect(discountFor(null, 'PLUS')).toBeNull();
            expect(discountFor(discount, 'PRO')).toBe(discount);
            expect(discountFor(discount, 'PLUS')).toBeNull();
        });
    });

    describe('one-off payment line', () => {
        it('names the date one calendar month on, on the Moscow calendar', () => {
            expect(oneMonthLater(new Date('2026-10-09T09:00:00Z'))).toBe('9 ноября');
            expect(oneMonthLater(new Date('2026-12-15T09:00:00Z'))).toBe('15 января');
            expect(oneMonthLater(new Date('2026-10-31T09:00:00Z'))).toBe('30 ноября');
            expect(oneMonthLater(new Date('2027-01-31T09:00:00Z'))).toBe('28 февраля');
            // 23:30 UTC on 8 October is already 9 October in Moscow
            expect(oneMonthLater(new Date('2026-10-08T23:30:00Z'))).toBe('9 ноября');
        });

        it('says there is no renewal', () => {
            expect(oneOffText(new Date('2026-10-09T09:00:00Z'))).toBe('Разовая оплата за месяц, без автопродления. Доступ — до 9 ноября.');
        });
    });
});
