/**
 * The words of a copy of somebody else's deck («Добавить к себе», Share/10). Where the product contract has approved wording
 * (docs/product/community-decks.md, «Копии» and «Экраны») it is used as written; the rest are plain statements of state.
 */
export const COPY_TEXT = {
    /** The name of the mark on a copy. */
    mark: 'Копия',
    /** Approved: «Пока копия готовится, видно «Готовим колоду…»». */
    preparing: 'Готовим колоду…',
    failed: 'Не удалось подготовить колоду',
    /** Approved: «копия остаётся с пометкой «Источник больше недоступен»». */
    sourceClosedMark: 'Источник больше недоступен',
    /** Approved («Экраны», «Источник недоступен»). */
    sourceClosed: 'Автор закрыл исходную колоду. Ваша копия остаётся с вами, но обновлений больше не будет.',
    /** `Копия колоды «…»` is drawn around the link to the source, whose text is the source's title. */
    originBefore: 'Копия колоды «',
    originAfter: '»',
    detached: 'Отвязана от автора',

    /** The preparing block of the hub. */
    preparingHint: 'Страница обновится сама, когда колода будет готова.',
    preparingLine: (summary: string): string => `В копии будет: ${summary}.`,
    failedHint: 'Заполнить копию не получилось. Повторите подготовку или удалите колоду.',
    stalledHint: 'Подготовка идёт дольше обычного. Страница больше не обновляется сама.',
    retry: 'Повторить',
    retrying: 'Повторяем…',
    check: 'Проверить снова',
    ready: 'Колода готова.',
    retried: 'Повторяем подготовку.',
    retryFailed: 'Не удалось повторить подготовку. Проверьте соединение и повторите.',

    /** Detach confirmation and its result. Approved label: «Отвязать от автора». */
    detach: 'Отвязать от автора',
    detachTitle: 'Отвязать от автора?',
    detachBody: 'Копия останется у вас со всем, что в ней есть. Обновления от автора приходить не будут, и вернуть связь потом нельзя.',
    detaching: 'Отвязываем…',
    cancel: 'Отмена',
    detachedDone: 'Копия отвязана от автора.',
    detachFailed: 'Не удалось отвязать копию от автора. Повторите.',
    deleteConsequence: 'Копия исчезнет из ваших колод. Колода автора не изменится.',
    deckMissing: 'Колода не найдена или больше недоступна.'
} as const;
