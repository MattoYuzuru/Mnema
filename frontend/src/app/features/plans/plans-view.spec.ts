import { parsePlans } from './plans-api.service';
import {
    COMPARE_ROWS, autoRenewText, cta, discountText, entitlementText, limitContext, plansHeading, priceText, recommendation, renewalDate, rub, yearSwitchHint, yearTerms
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
});
