import { PLAN_LABEL, rub } from '../plans/plans-view';
import { calendarDay } from '../usage/usage-view';
import { Order } from './billing.models';

const NBSP = '\u00a0';
const KOPECKS = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** What the return page can say. `pending` also covers the wait before the first answer; `slow` is a `PENDING` order the bank has not confirmed in time. */
export type PaymentOutcome = 'pending' | 'slow' | 'paid' | 'failed' | 'refunded' | 'review' | 'not-found' | 'unknown';

export interface PaymentCopy {
    readonly heading: string;
    readonly text: string;
    /** The check button: only where waiting longer can still change the answer. */
    readonly recheck: boolean;
}

/** «449 ₽» for whole rubles, «449,50 ₽» otherwise. */
export function amountText(kopecks: number): string {
    return kopecks % 100 === 0 ? rub(kopecks / 100) : `${KOPECKS.format(kopecks / 100).replace(/\s/gu, NBSP)}${NBSP}₽`;
}

export function paymentCopy(outcome: PaymentOutcome, order: Order | null): PaymentCopy {
    switch (outcome) {
        case 'paid': {
            const plan = order === null ? 'Тариф' : PLAN_LABEL[order.plan];
            const until = order?.periodEnd == null ? '' : ` до ${calendarDay(order.periodEnd)}`;
            return { heading: 'Оплата прошла', text: `${plan}${until}.`, recheck: false };
        }
        case 'failed':
            return { heading: 'Оплата не прошла', text: 'Деньги не списаны или вернутся банком автоматически. Можно попробовать ещё раз.', recheck: false };
        case 'refunded':
            return { heading: 'Платёж возвращён', text: 'Банк вернул деньги за этот заказ.', recheck: false };
        case 'review':
            return { heading: 'Проверяем платёж', recheck: false,
                text: 'Сумма платежа не совпала с заказом, мы проверим его вручную. Доступ не потерян: если оплата прошла, мы откроем тариф или вернём деньги.' };
        case 'not-found':
            return { heading: 'Заказ не найден', text: 'Такого заказа нет в вашем аккаунте.', recheck: false };
        case 'slow':
            return { heading: 'Банк ещё не подтвердил оплату', recheck: true,
                text: 'Доступ появится сам, как только банк пришлёт подтверждение. Можно закрыть страницу.' };
        case 'unknown':
            return { heading: 'Не удалось узнать статус оплаты', recheck: true,
                text: 'Если деньги списаны, доступ появится сам, как только банк пришлёт подтверждение.' };
        default:
            return { heading: 'Ждём подтверждения банка', text: 'Обычно это занимает несколько секунд. Страницу можно не обновлять.', recheck: false };
    }
}
