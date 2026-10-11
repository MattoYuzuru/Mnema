import { BlockedMedia, ChecklistKey, ContentLevel, PublicationFailure, RELEASE_NOTE_MAX_CODE_POINTS } from './publication.models';

/** The words of the publication block and the «Сделать публичной» checklist (docs/product/community-decks.md; plain language, no long dashes). */
export const PUBLICATION_TEXT = {
    heading: 'Доступ и публикация',
    privateLine: 'Колода видна только вам',
    makePublic: 'Сделать публичной…',
    loading: 'Загружаем сведения о доступе…',
    loadError: 'Не удалось загрузить сведения о доступе.',
    retry: 'Повторить',
    published: 'Опубликовано',
    allPublished: 'Все изменения опубликованы',
    headOnly: 'Название или описание изменились, но ученики пока видят прежние',
    publishUpdate: 'Опубликовать обновление',
    releaseNote: 'Что нового',
    releaseNoteHint: 'Необязательно. Увидят те, кто добавил колоду к себе.',
    publish: 'Опубликовать',
    publishing: 'Публикуем…',
    cancel: 'Отмена',
    updatePublished: 'Обновление опубликовано',
    madePublic: 'Колода стала публичной',
    stale: 'Колода изменилась, пока вы публиковали. Мы загрузили новые сведения: проверьте их и повторите.',
    network: 'Не удалось связаться с сервером. Проверьте соединение и повторите.',
    unknown: 'Не удалось опубликовать. Повторите чуть позже.',
    requirements: 'Условия не выполнены',
    copyLink: 'Скопировать ссылку',
    linkCopied: 'Ссылка скопирована',
    linkNotCopied: 'Не удалось скопировать. Выделите ссылку и скопируйте её.',
    linkLabel: 'Ссылка на колоду',
    opensInNewTab: ' (откроется в новой вкладке)',
    noteTooLong: `Сократите текст до ${RELEASE_NOTE_MAX_CODE_POINTS} символов.`,
    overBy: (count: number): string => `Лишних символов: ${count}`
} as const;

export const CHECKLIST_TEXT = {
    heading: 'Сделать колоду публичной',
    lede: 'Публичную колоду увидят все, а рядом будет ваш логин. Для этого нужно несколько сведений.',
    ready: 'Готово',
    todo: 'Нужно сделать',
    description: 'Описание',
    descriptionDone: 'Описание у колоды есть.',
    descriptionTodo: 'Напишите, о чём колода: это увидят те, кто её откроет.',
    descriptionAction: 'Добавить описание',
    topic: 'Тема',
    topicHint: 'Выберите самую близкую тему.',
    topicSuggested: 'Подходит по названию',
    topicPlaceholder: 'Выберите тему',
    language: 'Язык',
    languageHint: 'Язык определён автоматически, его можно поправить.',
    level: 'Уровень (необязательно)',
    levelNone: 'Не указан',
    tags: 'Теги (необязательно)',
    tagsHint: 'До пяти слов, по которым колоду проще найти. Enter или запятая добавляют тег.',
    profile: 'Публичный профиль',
    profileDone: 'Публичный профиль включён.',
    profileTodo: 'У публичной колоды виден ваш логин, поэтому нужен публичный профиль. Включите его в профиле.',
    profileAction: 'Открыть профиль',
    media: 'Картинки',
    mediaTodo: 'В публичных колодах нельзя использовать картинки, лицензия которых запрещает коммерческое использование. Замените их.',
    mediaDone: 'Запрещённых картинок нет.',
    catalogHeading: 'Сообщество',
    catalogMet: (materials: number, exercises: number): string =>
        `Колода подходит для раздела «Сообщество»: материалов ${materials}, упражнений ${exercises}.`,
    catalogUnmet: (materials: number, exercises: number, needMaterials: number): string =>
        `В «Сообщество» колода попадёт, когда в ней будет не меньше ${needMaterials} материалов и хотя бы одно упражнение. Сейчас материалов ${materials}, упражнений ${exercises}. Публичной она станет и сейчас.`,
    submit: 'Опубликовать и сделать публичной',
    submitting: 'Публикуем…',
    cancel: 'Отмена',
    blockedSummary: (labels: readonly string[]): string => `Чтобы опубликовать, закройте пункты: ${labels.join(', ')}.`,
    topicsLoading: 'Загружаем темы…',
    languageChoose: 'Выберите язык, на котором написана колода.',
    failedWord: 'Не принято',
    optionalWord: 'По желанию',
    levelLabel: 'Уровень',
    tagsLabel: 'Теги',
    openBlocked: (entry: BlockedMedia, title: string | null): string =>
        `${entry.memberKey !== null ? 'Открыть материал' : 'Открыть упражнение'}${title === null || title.length === 0 ? '' : ` «${title}»`}`,
    topicsError: 'Не удалось загрузить список тем.',
    topicsRetry: 'Загрузить темы снова',
    rejected: (labels: readonly string[]): string => `Сервер не принял публикацию, не хватает: ${labels.join(', ')}. Мы обновили сведения.`,
    openMaterial: 'Открыть материал',
    openExercise: 'Открыть упражнение',
    material: 'Материал',
    exercise: 'Упражнение',
    mediaReason: 'картинка с лицензией, запрещающей коммерческое использование'
} as const;

export const CHECKLIST_LABEL: Readonly<Record<ChecklistKey, string>> = {
    description: 'описание',
    topic: 'тема',
    language: 'язык',
    publicProfile: 'публичный профиль',
    blockedMedia: 'картинки'
};

export const CONTENT_LEVEL_LABEL: Readonly<Record<ContentLevel, string>> = {
    A1: 'A1', A2: 'A2', B1: 'B1', B2: 'B2', C1: 'C1', C2: 'C2',
    BEGINNER: 'Начальный', INTERMEDIATE: 'Средний', ADVANCED: 'Продвинутый'
};

/** Why a publication request failed, in the words shown under the block. */
export function publicationFailureText(failure: PublicationFailure | null): string {
    if (failure === null || failure.status === 0) return PUBLICATION_TEXT.network;
    if (failure.status === 412) return PUBLICATION_TEXT.stale;
    if (failure.status === 409 && failure.code === 'PUBLICATION_REQUIREMENTS') {
        return CHECKLIST_TEXT.rejected(failure.failed.map(key => CHECKLIST_LABEL[key]));
    }
    return PUBLICATION_TEXT.unknown;
}

export function unpublishedChangesText(count: number): string {
    return `Изменения для учеников не опубликованы (${count})`;
}

export function blockedMediaLabel(entry: BlockedMedia, title: string | null): string {
    const kind = entry.memberKey !== null ? CHECKLIST_TEXT.material : CHECKLIST_TEXT.exercise;
    return title === null || title.length === 0 ? kind : `${kind} «${title}»`;
}
