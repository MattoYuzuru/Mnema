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

/**
 * The «Сообществе» link of the «Колода не найдена» screen. The Community catalogue is epic 3 and has no route yet, so the
 * sentence «…или поищите похожие в Сообществе» is left out until it does.
 * TODO(Community catalogue epic): set the route (for example '/community') when it exists.
 */
export const COMMUNITY_ROUTE: string | null = null;

/** The label of an exercise type, for the preview list; a mechanic newer than this client is just «Упражнение». */
export const EXERCISE_FALLBACK_LABEL = 'Упражнение';

/** Said (politely) when the deck was republished while a list was being read and the list started again from its first page. */
export const RESTART_NOTICE = 'Автор обновил колоду, пока вы читали. Список загружен заново с начала.';

/** What the retry screen says once the wait that a 429 asked for has passed. */
export const RETRY_NOW_TEXT = 'Можно повторить.';
