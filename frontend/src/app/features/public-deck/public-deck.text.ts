import { sampleShareRefusedText } from '../../core/onboarding/sample-deck';
import { PublicDeckFailure } from './public-deck.models';

/** A calm sentence for a public read that failed, naming what could not be loaded («материалы», «упражнения»). */
export function readFailureText(failure: PublicDeckFailure | null, what: string): string {
    switch (failure?.kind) {
        case 'rate-limited': return `Слишком много запросов. ${waitText(failure.retryAfter)}`;
        case 'busy': return 'Сейчас много читателей. Повторите через секунду.';
        default: return `Не удалось загрузить ${what}. Проверьте соединение и повторите.`;
    }
}

/** «Подождите 37 с и повторите.» */
export function waitText(retryAfter: number | null): string {
    return retryAfter === null ? 'Подождите немного и повторите.' : `Подождите ${retryAfter} с и повторите.`;
}

/** The «Сообществе» link of the «Колода не найдена» screen: the community catalogue. */
export const COMMUNITY_ROUTE = '/community';

/** The label of an exercise type, for the preview list; a mechanic newer than this client is just «Упражнение». */
export const EXERCISE_FALLBACK_LABEL = 'Упражнение';

/** Said (politely) when the deck was republished while a list was being read and the list started again from its first page. */
export const RESTART_NOTICE = 'Автор обновил колоду, пока вы читали. Список загружен заново с начала.';

/** What the retry screen says once the wait that a 429 asked for has passed. */
export const RETRY_NOW_TEXT = 'Можно повторить.';

/** «45 с», «12 мин», «2 ч»: a wait the server asked for, in the unit a person can use. */
export function longWaitText(seconds: number | null): string {
    if (seconds === null) return 'Подождите немного и повторите.';
    if (seconds < 90) return `Подождите ${seconds} с и повторите.`;
    if (seconds < 5400) return `Подождите ${Math.ceil(seconds / 60)} мин и повторите.`;
    return `Подождите ${Math.ceil(seconds / 3600)} ч и повторите.`;
}

/**
 * The words of the copy button, of the invitation redemption and of the access request on the public view. Approved by the product
 * contract (docs/product/community-decks.md, «Копии» and «Экраны»): «Добавить к себе», «Открыть мою копию», «Запросить доступ» and
 * the sentence after the request. The rest are plain statements of what happened.
 */
export const PUBLIC_ACTION_TEXT = {
    add: 'Добавить к себе',
    adding: 'Добавляем…',
    openCopy: 'Открыть мою копию',
    copyPreparing: 'Копия ещё готовится.',
    copyNotFound: 'Сейчас добавить колоду к себе нельзя. Попробуйте позже.',
    copyOwn: 'Это ваша колода: добавлять её к себе не нужно.',
    /** The deck is a sample deck: the approved sentence of the onboarding, said wherever sharing one is refused. */
    copySample: sampleShareRefusedText(),
    copyLimit: (limit: number | null): string => `${limit === null ? 'Одновременно можно готовить ограниченное число копий.' : `Одновременно можно готовить не больше ${limit} копий.`} Дождитесь, пока часть будет готова, и повторите.`,
    copyBusy: 'Сейчас много читателей. Повторите через секунду.',
    copyQueueBusy: 'Сейчас готовится много копий. Повторите через минуту.',
    copyFailed: 'Не удалось добавить колоду. Проверьте соединение и повторите.',

    requestAccess: 'Запросить доступ',
    requestSending: 'Отправляем…',
    requestSent: 'Запрос отправлен. Если автор согласится, колода появится по этой же ссылке.',
    requestWithdraw: 'Отменить запрос',
    requestWithdrawing: 'Отменяем…',
    requestWithdrawn: 'Запрос отменён.',
    requestWithdrawFailed: 'Не удалось отменить запрос. Повторите.',
    requestLimit: 'У вас уже слишком много открытых запросов. Дождитесь ответов или отмените лишние.',
    requestFailed: 'Не удалось отправить запрос. Проверьте соединение и повторите.',

    signInToAccept: 'Войдите, чтобы принять приглашение.',
    accepting: 'Принимаем приглашение…',
    fullHeading: 'В колоде нет мест',
    fullBody: 'У колоды уже максимальное число читателей. Попросите автора освободить место.'
} as const;
