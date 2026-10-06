import { PromoProblem } from './promo.models';

/** «через 12 минут»: the wait of a 429 in the units a person waits in. */
export function waitText(seconds: number | null): string {
    if (seconds === null) return 'через некоторое время';
    const minutes = Math.ceil(seconds / 60);
    if (minutes <= 1) return 'через минуту';
    if (minutes < 60) return `через ${minutes} ${plural(minutes, ['минуту', 'минуты', 'минут'])}`;
    const hours = Math.ceil(minutes / 60);
    return `через ${hours} ${plural(hours, ['час', 'часа', 'часов'])}`;
}

function plural(count: number, forms: readonly [string, string, string]): string {
    const lastTwo = count % 100;
    const last = count % 10;
    if (lastTwo >= 11 && lastTwo <= 14) return forms[2];
    if (last === 1) return forms[0];
    return last >= 2 && last <= 4 ? forms[1] : forms[2];
}

/**
 * What a refused promo code says: calm, one sentence of what happened and one of what can be done. «Не подходит» covers an unknown,
 * switched-off, expired and not yet started code in the same words, as the server does, so the message is no hint for guessing.
 */
export function promoMessage(problem: PromoProblem): string {
    if (problem.retryable) return 'Не удалось получить результат применения промокода. Повторите попытку — повторной активации не будет.';
    switch (problem.code) {
        case 'PROMO_INVALID':
            return 'Этот промокод не подходит. Проверьте, что он введён без опечаток, и попробуйте ещё раз.';
        case 'PROMO_EXHAUSTED':
            return 'У этого промокода закончились активации. Тариф не изменился.';
        case 'PROMO_ALREADY_USED':
            return 'Вы уже использовали этот промокод.';
        case 'PROMO_NOT_ELIGIBLE':
            return 'Сейчас промокод применить нельзя: подтвердите почту в профиле или проверьте, что у вас не действует тариф выше.';
        case 'PROMO_VELOCITY':
            return 'С этой сети сейчас нельзя активировать промокоды. Попробуйте позже или из другой сети.';
        case 'RATE_LIMITED':
            return `Слишком много попыток. Повторите ${waitText(problem.retryAfterSeconds)}.`;
        case 'IDENTITY_UNAVAILABLE':
            return 'Не удалось проверить аккаунт. Попробуйте ещё раз чуть позже: тариф не изменился.';
        default:
            return 'Не удалось применить промокод. Тариф не изменился, можно повторить.';
    }
}
