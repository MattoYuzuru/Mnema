import { promoMessage, waitText } from './promo-messages';
import { PromoProblem, PromoProblemCode } from './promo.models';

const problem = (code: PromoProblemCode, retryAfterSeconds: number | null = null): PromoProblem => ({ code, retryAfterSeconds, retryable: false });

describe('promo messages', () => {
    it('does not assert an unchanged tariff when the command outcome is unknown', () => {
        const message = promoMessage({ code: 'UNKNOWN', retryAfterSeconds: null, retryable: true });
        expect(message).toContain('Не удалось получить результат');
        expect(message).toContain('Повторите');
        expect(message).not.toContain('Тариф не изменился');
    });
    it('says every refusal calmly, in words, and never blames', () => {
        const codes: PromoProblemCode[] = ['PROMO_INVALID', 'PROMO_EXHAUSTED', 'PROMO_ALREADY_USED', 'PROMO_NOT_ELIGIBLE', 'PROMO_VELOCITY',
            'RATE_LIMITED', 'IDENTITY_UNAVAILABLE', 'UNKNOWN'];
        const messages = codes.map(code => promoMessage(problem(code)));
        expect(new Set(messages).size).toBe(codes.length);
        for (const message of messages) expect(message).toMatch(/[.]$/u);
    });

    it('uses one sentence for every reason a code cannot be used, so it is no oracle', () => {
        expect(promoMessage(problem('PROMO_INVALID'))).toBe('Этот промокод не подходит. Проверьте, что он введён без опечаток, и попробуйте ещё раз.');
        expect(promoMessage(problem('PROMO_ALREADY_USED'))).toBe('Вы уже использовали этот промокод.');
    });

    it('names the wait of a rate limit in the units a person waits in', () => {
        expect(promoMessage(problem('RATE_LIMITED', 3000))).toBe('Слишком много попыток. Повторите через 50 минут.');
        expect(promoMessage(problem('RATE_LIMITED', null))).toBe('Слишком много попыток. Повторите через некоторое время.');
        expect(waitText(30)).toBe('через минуту');
        expect(waitText(60)).toBe('через минуту');
        expect(waitText(61)).toBe('через 2 минуты');
        expect(waitText(5 * 60)).toBe('через 5 минут');
        expect(waitText(21 * 60)).toBe('через 21 минуту');
        expect(waitText(11 * 60)).toBe('через 11 минут');
        expect(waitText(3600)).toBe('через 1 час');
        expect(waitText(59 * 60)).toBe('через 59 минут');
        expect(waitText(3601)).toBe('через 2 часа');
        expect(waitText(5 * 3600)).toBe('через 5 часов');
        expect(waitText(3 * 3600)).toBe('через 3 часа');
    });
});
