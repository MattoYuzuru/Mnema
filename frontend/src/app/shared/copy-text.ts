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

/** The kinds of unit an update is made of (docs/product/community-decks.md, «Обновления»). */
export type UpdateUnitKind = 'material' | 'exercise' | 'goal' | 'description' | 'order';
/** What the author's change means for this reader's copy. */
export type UpdateUnitClass = 'new' | 'fix' | 'conflict' | 'deleted' | 'changed-again';
/** The learner's decision for a unit. `null` on a unit means «not decided» (new, fix) or the default of its class (conflict, deleted). */
export type UpdateChoice = 'accept' | 'skip' | 'keep-mine' | 'take-author' | 'keep' | 'delete';

/**
 * The words of the update review screen of a copy (Updates/5, #444). «Сейчас у вас», «Вариант автора», «Есть обновление», «Принять всё
 * безопасное», «Удалить и у меня?» and «Источник больше недоступен» are the approved product wording
 * (docs/product/community-decks.md, «Обновления» and «Тексты для пользователя»); the rest are plain statements of state.
 */
export const UPDATE_TEXT = {
    /** Approved: the badge of a copy that has an update waiting. */
    badge: 'Есть обновление',

    /** The two sides of a comparison. */
    mine: 'Сейчас у вас',
    author: 'Вариант автора',
    switchLegend: 'Какую версию показать',
    showingMine: 'Показана ваша версия.',
    showingAuthor: 'Показан вариант автора.',
    absentMine: 'Нет у вас',
    absentMineNote: 'В вашей копии этого пока нет.',
    absentAuthor: 'Автор удалил',
    absentAuthorNote: 'Автор убрал это из своей колоды.',

    /** What a changed part of a document says about itself, next to the colour. */
    changed: 'Изменено',
    added: 'Добавлено',
    removed: 'Удалено автором',
    /** Read before an inserted and a removed word, so the change never rests on colour or line style alone. */
    insertedWord: 'добавлено: ',
    removedWord: 'удалено: ',

    /** A unit of the plan. */
    kinds: { material: 'Материал', exercise: 'Упражнение', goal: 'Цель', description: 'Описание колоды', order: 'Порядок материалов' } satisfies Record<UpdateUnitKind, string>,
    classes: { new: 'Новое', fix: 'Исправление', conflict: 'Конфликт', deleted: 'Удалено автором', 'changed-again': 'Снова изменено' } satisfies Record<UpdateUnitClass, string>,
    choiceAccept: 'Принять',
    choiceSkip: 'Не брать',
    choiceKeepMine: 'Оставить моё',
    choiceTakeAuthor: 'Взять вариант автора',
    choiceKeep: 'Оставить у себя',
    choiceDelete: 'Удалить и у меня',
    hintAccept: 'Это появится в вашей копии после применения.',
    hintSkip: 'Это не появится у вас, и мы не предложим это снова, пока автор не изменит его ещё раз.',
    hintKeepMine: 'Ваша версия останется как есть.',
    hintTakeAuthor: 'Ваша версия будет заменена вариантом автора.',
    hintKeep: 'Это останется у вас, даже если автор убрал.',
    hintDelete: 'Это исчезнет из вашей копии.',
    choiceFor: (title: string): string => `Что сделать с «${title}»`,
    /** Approved: «Удалить и у меня?». */
    deleteQuestion: (title: string): string => `Удалить «${title}» и у меня?`,
    compare: 'Сравнить',
    collapse: 'Свернуть сравнение',
    compareFor: (title: string): string => `Сравнить: «${title}»`,
    collapseFor: (title: string): string => `Свернуть сравнение: «${title}»`,
    dependency: (title: string): string => `Нужен материал «${title}». Он будет добавлен вместе с этим.`,

    /** The summary. */
    summaryTitle: 'Что изменил автор',
    nothingChanged: 'Для вас в этом обновлении ничего нет.',
    whatsNew: 'Что нового',
    acceptSafe: 'Принять всё безопасное',
    accepting: 'Принимаем…',
    acceptSafeNote: 'Берём новое и исправления того, что вы не меняли. Ваши правки и удаления не трогаем.',
    nothingSafe: 'Безопасного для приёма сейчас нет.',

    /** The tabs of the classes. */
    tabs: { new: 'Новое', fix: 'Исправления', conflict: 'Конфликты', deleted: 'Удалено автором' }
} as const;
