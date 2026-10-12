import { plural } from '../core/notifications/notification-presenter';

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
    /** The stamp of a decision that is fixed because another unit needs this one. */
    required: 'Обязательно',
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
    tabs: { new: 'Новое', fix: 'Исправления', conflict: 'Конфликты', deleted: 'Удалено автором' },

    /** The review screen (`/decks/:id/update`): its header and the states of the copy. */
    pageTitle: 'Обновление от автора',
    backToDeck: 'К колоде',
    sourceLine: (title: string): string => `Копия колоды «${title}»`,
    checkUpdates: 'Проверить обновления',
    /** The deck hub of a copy that has an update waiting (Updates/6): the badge is the heading, this is the call to action. */
    viewUpdate: 'Посмотреть обновление',
    offerNote: 'Автор опубликовал новое. Вы сами решаете, что взять, а ваши правки останутся.',

    /** The setting «Принимать безопасное автоматически» in the hub of a copy. */
    autoTitle: 'Обновления от автора',
    autoLabel: 'Принимать безопасное автоматически',
    autoTopic: 'автоматический приём обновлений',
    autoHelp: 'Берём новое и исправления того, что вы не меняли. Конфликты и удаления ждут вашего решения. Принятое автоматически смотрите в «Истории обновлений». Материал или упражнение можно вернуть, пока вы сами не правили его после обновления.',
    autoOn: 'Безопасное будет приниматься автоматически.',
    autoOff: 'Обновления снова принимаются только вручную.',
    autoFailed: 'Не удалось сохранить настройку. Проверьте соединение и повторите.',
    /** Said after the reason when a save failed, so the state of the setting is never a guess. */
    autoStayedOn: 'Настройка осталась включённой.',
    autoStayedOff: 'Настройка осталась выключенной.',
    autoPreparing: 'Копия ещё готовится. Настройку можно изменить, когда она будет готова.',
    autoDetached: 'Копия отвязана от автора, поэтому настройка не действует.',
    loading: 'Загружаем обновление…',
    loadFailed: 'Не удалось загрузить обновление. Проверьте соединение и повторите.',
    loadFailedTitle: 'Не удалось открыть обновление',
    notFound: 'Колода не найдена или больше недоступна.',
    retry: 'Повторить',
    preparingNote: 'Обновления станут доступны, когда копия будет готова.',
    detachedState: 'Колода отвязана от автора, обновлений нет',
    detachedNote: 'Всё, что есть в копии, остаётся с вами.',
    upToDate: 'У вас всё как у автора',
    upToDateNote: 'Новых изменений от автора сейчас нет.',

    /** The list of changes. */
    changesTitle: 'Изменения',
    tabsLabel: 'Виды изменений',
    tabEmpty: 'В этой вкладке ничего нет.',
    /** Under the tabs of the conflicts: what the two stamps mean. */
    conflictNote: 'Конфликт: вы и автор изменили одно и то же, по умолчанию остаётся ваш вариант.',
    againNote: 'Снова изменено: автор изменил то, что вы уже решили, поэтому мы спрашиваем ещё раз.',
    /** Next to «Принять всё безопасное» while the reader has picks on conflicts or deletions. */
    picksWaiting: 'Ваш выбор у конфликтов и удалений применит кнопка «Применить выбранное».',
    tabSearching: 'Ищем записи этой вкладки…',
    loadingMore: 'Загружаем следующие изменения…',
    untitled: { material: 'Материал недоступен', exercise: 'Упражнение недоступно', goal: 'Цель колоды', description: 'Название и описание колоды', order: 'Порядок материалов' } satisfies Record<UpdateUnitKind, string>,
    /** A unit that is needed by one the reader accepts: taken together with it, so it cannot be skipped. */
    lockedFor: (title: string): string => `Нужно для «${title}», которое вы принимаете, поэтому берём вместе с ним.`,
    dependencyBlocked: (needed: string, by: string): string => `«${needed}» нужно для «${by}», которое вы принимаете. Верните «Принять» для «${needed}» или не берите «${by}».`,
    dependencyDeclined: 'Одно из изменений нужно другому, которое вы принимаете. Верните «Принять» для первого или не берите второе.',

    /** Applying. */
    applySelected: 'Применить выбранное',
    applySelectedNone: 'Отметьте, что взять, и кнопка станет доступна.',
    applySelectedNote: (count: number): string => `Отмечено: ${count}. Всё, чего вы не отметили, останется на потом.`,
    applyingSelected: 'Применяем…',
    applying: 'Применяем обновление…',
    applyingNote: 'Обновление большое, оно применяется в фоне. Страница проверит сама, когда всё будет готово.',
    applyingStalled: 'Применение идёт дольше обычного. Страница больше не проверяет сама.',
    check: 'Проверить снова',
    applied: 'Обновление применено.',
    appliedCounts: (applied: number, declined: number, pending: number, alsoAccepted: number): string => {
        const parts = [`принято ${applied}`];
        if (alsoAccepted > 0) parts.push(`вместе с ними ещё ${alsoAccepted}`);
        if (declined > 0) parts.push(`не взято ${declined}`);
        if (pending > 0) parts.push(`осталось на потом ${pending}`);
        return `Обновление применено: ${parts.join(', ')}.`;
    },
    stale: 'Пока вы смотрели, колода изменилась. Мы обновили список.',
    staleClosed: 'Пока вы смотрели, колода изменилась. Мы обновили список, открытые сравнения свёрнуты.',
    applyUnknown: 'Не удалось узнать, применилось ли обновление. Повторите: ничего не будет применено дважды.',
    applyRateLimited: 'Слишком много запросов. Подождите немного и повторите.',
    applyInvalid: 'Этот выбор нельзя применить. Проверьте отмеченное и повторите.',
    applyTooMany: 'Выбрано слишком много изменений сразу. Примените часть из них, потом остальное.',
    applyChoiceNotAllowed: (title: string | null): string => title === null
        ? 'Для одного из изменений этот выбор недоступен. Выберите другое и повторите.'
        : `Для «${title}» этот выбор недоступен. Выберите другое и повторите.`,
    applyNeedsMaterial: (title: string | null): string => title === null
        ? 'Это упражнение нельзя взять: вы удалили материал, к которому оно относится.'
        : `Упражнение «${title}» нельзя взять: вы удалили материал, к которому оно относится.`,
    /** 400 `material_still_used`: deleting a material that exercises of the copy are still about; `exercise` is the one the plan lists, if it does. */
    applyMaterialStillUsed: (material: string | null, exercise: string | null): string => {
        const subject = material === null ? 'Этот материал' : `Материал «${material}»`;
        return exercise === null
            ? `${subject} нельзя удалить: к нему ещё привязаны упражнения. Удалите и их или оставьте материал у себя.`
            : `${subject} нельзя удалить: к нему ещё привязано упражнение «${exercise}». Удалите и его или оставьте материал у себя.`;
    },
    /** 400 `publication_refused`: nothing was written. */
    applyRefused: 'Не удалось применить обновление: результат не принят. Ничего не изменилось.',
    /** 503 `UPDATE_PLAN_BUSY` after the one automatic retry. */
    busy: 'Сервер сейчас занят. Попробуйте через минуту.',
    applyJobFailed: 'Не удалось применить большое обновление. Ничего не потеряно: выберите и примените снова.',

    /** «Что нового» in the hub of a copy after an automatic update (Updates/6 part B). */
    newsApplied: (applied: number): string => applied > 0
        ? `Автор обновил колоду, мы взяли ${applied} ${plural(applied, ['изменение', 'изменения', 'изменений'])} автоматически.`
        : 'Автор обновил колоду.',
    newsPending: (pending: number): string => `${pending} ${plural(pending, ['ждёт', 'ждут', 'ждут'])} вашего решения.`,
    newsLook: 'Посмотреть, что изменилось',
    newsDecide: 'Решить, что взять',

    /** The history of updates (`/decks/:id/update/history`): «Что нового» with the way to take a unit back. */
    historyTitle: 'История обновлений',
    historyLede: 'Что менялось в вашей копии после обновлений от автора. Материал или упражнение можно вернуть, пока вы сами не правили его после обновления.',
    historyLoading: 'Загружаем историю…',
    historyFailed: 'Не удалось загрузить историю. Проверьте соединение и повторите.',
    historyFailedTitle: 'Не удалось открыть историю',
    historyEmpty: 'Обновлений пока не было.',
    historyEntries: 'Обновления',
    historyUnits: 'Что изменилось',
    historyTruncated: 'Показаны не все изменения этого обновления.',
    historyCounts: (applied: number, pending: number): string =>
        `Принято изменений: ${applied}.${pending > 0 ? ` Ждали вашего решения: ${pending}.` : ''}`,
    historyDetached: 'Копия отвязана от автора: изменения можно посмотреть, но вернуть их уже нельзя.',
    modes: { AUTO: 'Автоматически', MANUAL: 'Вручную' } satisfies Record<'AUTO' | 'MANUAL', string>,
    appliedAtExact: 'Точное время',
    justNow: 'только что',
    changes: { ADDED: 'Добавлено', CHANGED: 'Исправлено', REMOVED: 'Удалено', REORDERED: 'Порядок' } satisfies Record<'ADDED' | 'CHANGED' | 'REMOVED' | 'REORDERED', string>,
    unitStates: { RETURNED: 'Вы уже вернули', CHANGED_SINCE: 'Вы изменили после обновления' } satisfies Record<'RETURNED' | 'CHANGED_SINCE', string>,

    /** Taking a unit back. */
    revert: 'Вернуть как было',
    revertFor: (title: string): string => `Вернуть как было: «${title}»`,
    /** The same, told apart from a unit of the same title in another update by the time of that update. */
    revertForAt: (title: string, when: string): string => `Вернуть как было: «${title}», обновление от ${when}`,
    orderChanged: 'Изменился порядок материалов',
    revertAsk: {
        ADDED: (title: string): string => `Уберём «${title}» из вашей колоды.`,
        CHANGED: (title: string): string => `Вернём «${title}» к тому, что было до обновления.`,
        REMOVED: (title: string): string => `Вернём «${title}» в вашу колоду на прежнее место.`
    },
    /** What happens to each unit that goes back together with the asked one; `titles` are the quoted titles, already joined with commas. */
    revertWith: {
        ADDED: (titles: string): string => `Вместе с этим уберём: ${titles}.`,
        REMOVED: (titles: string): string => `Вместе с этим вернём: ${titles}.`,
        CHANGED: (titles: string): string => `Вместе с этим вернём к прежнему виду: ${titles}.`,
        /** A unit the entry does not list: only that it goes back. */
        UNKNOWN: (titles: string): string => `Вместе с этим вернутся: ${titles}.`
    },
    revertNoRepeat: 'Следующее обновление не вернёт это обратно, пока автор не изменит его снова.',
    revertWithWhy: 'Они связаны между собой, поэтому возвращаются вместе.',
    revertConfirm: 'Вернуть',
    revertConfirmAll: 'Вернуть всё это',
    reverting: 'Возвращаем…',
    revertDone: (title: string, more: number): string => more > 0 ? `Вернули «${title}» и ещё ${more}.` : `Вернули «${title}».`,
    revertStaleUnit: 'После обновления вы уже меняли это, поэтому вернуть нельзя. Мы обновили список.',
    revertStaleCopy: 'Пока вы смотрели, колода изменилась. Мы обновили список, попробуйте ещё раз.',
    revertDetached: 'Колода отвязана от автора, поэтому вернуть изменения нельзя. Мы обновили список.',
    revertPreparing: 'Копия ещё готовится. Вернуть изменения можно, когда она будет готова.',
    revertUnknown: 'Не удалось узнать, вернулось ли. Повторите: ничего не будет возвращено дважды.',
    revertStillUsed: (title: string | null): string => title === null
        ? 'Нельзя убрать материал: к нему привязано ваше упражнение, которое не было частью обновления.'
        : `Нельзя убрать материал: к нему привязано ваше упражнение «${title}», которое не было частью обновления.`,
    revertNotReturnable: 'Это изменение нельзя вернуть по отдельности.',
    revertRefused: 'Не удалось вернуть. Ничего не изменилось.',
    revertGone: 'Это обновление или колода больше недоступны. Мы обновили список.',
    revertRateLimited: 'Слишком много запросов. Подождите немного и повторите.',

    /** The two sides of a unit. */
    sidesLoading: 'Загружаем сравнение…',
    sidesFailed: 'Не удалось загрузить сравнение.',
    exercise: {
        type: 'Тип',
        disabled: 'Отключено',
        status: 'Состояние',
        prompt: 'Вопрос',
        reference: 'Эталон ответа',
        objective: 'Цель',
        accepted: 'Допустимые ответы',
        passage: 'Текст с пропусками',
        blank: 'пропуск',
        answers: 'Ответы',
        options: 'Варианты',
        single: 'Можно выбрать один',
        multiple: 'Можно выбрать несколько',
        correct: 'верный',
        pairs: 'Пары',
        sequence: 'Верный порядок',
        groups: 'Группы',
        selfReport: 'Ученик сам оценивает ответ.',
        material: 'Фрагмент материала',
        image: 'Картинка',
        audio: 'Звук',
        video: 'Видео',
        youtube: 'Видео на YouTube',
        empty: 'Без текста'
    },
    metadataTitle: 'Название',
    metadataDescription: 'Описание',
    noDescription: 'Без описания',
    objectiveTitle: 'Цель',
    moved: 'Передвинутые материалы',
    movedPlace: (title: string, from: number | null, to: number | null): string =>
        from === null || to === null ? `«${title}»` : `«${title}»: было место ${from}, стало ${to}`,
    movedCount: (total: number): string => `Передвинуто материалов: ${total}`,
    movedTruncated: (shown: number, total: number): string => `Показано ${shown} из ${total}.`,
    notMoved: 'Здесь порядок не менялся.'
} as const;
