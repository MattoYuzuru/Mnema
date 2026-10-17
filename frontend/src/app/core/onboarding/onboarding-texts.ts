/**
 * Product strings of the onboarding surfaces, copied verbatim from the texts the owner approved (design/prototype/onboarding/texts.js,
 * 2026-10-16). Later tasks add their keys here; a string is never written inline in a component. `{name}` marks a placeholder.
 */
export const ONBOARDING_TEXTS = {
    // Tour chrome
    'tour.counter': 'Шаг {n} из {m}',
    'tour.back': 'Назад',
    'tour.next': 'Дальше',
    'tour.finish': 'Завершить знакомство',
    'tour.finish.armed': 'Удерживайте 2\u00a0секунды, чтобы завершить знакомство',
    'tour.finish.toast': 'Знакомство завершено. Пройти его снова можно в профиле.',
    'tour.gate': 'Нажмите «{action}», чтобы продолжить',
    'tour.missing': 'Этого места сейчас нет на экране. Продолжим со следующего шага.',
    'tour.preparing': 'Готовим колоду…',
    'tour.pause.label': 'Знакомство на паузе',
    'tour.pause.resume': 'Продолжить знакомство',
    'tour.slider.prev': 'Предыдущее пояснение',
    'tour.slider.next': 'Следующее пояснение',
    // First entry: welcome and skip
    'welcome.eyebrow': 'Начало пути',
    'welcome.title': 'Mnema помогает надолго запомнить то, что вы учите',
    'welcome.lead': 'Покажем, как это работает. Это займёт две минуты.',
    'welcome.start': 'Познакомиться — 2\u00a0минуты',
    'welcome.skip': 'Мне это знакомо — сразу к делу',
    'skip.toast': 'Знакомство пропущено. Пройти его можно в профиле, а все возможности Mnema собраны в «Пути».',
    'skip.toast.link': 'Открыть «Путь»',
    // Survey
    'survey.counter': 'Вопрос {n} из 4',
    'survey.next': 'Дальше',
    'survey.back': 'Назад',
    'survey.skip': 'Пропустить',
    'survey.done': 'Готово',
    'survey.privacy': 'Ответы не видны другим пользователям, изменить их можно в профиле. Они помогают подобрать подсказки, колоды и тариф, но не меняют цены и лимиты.',
    'survey.goal.title': 'Для чего вам Mnema?',
    'survey.goal.hint': 'Подберём примеры и подсказки под вашу цель.',
    'survey.interests.title': 'Что хотите изучать?',
    'survey.interests.hint': 'Выберите до 20\u00a0тем. По ним Mnema предложит колоды Сообщества.',
    'survey.experience.title': 'Учились ли вы по карточкам раньше?',
    'survey.experience.new': 'Впервые',
    'survey.experience.new.hint': 'Покажем всё по шагам.',
    'survey.experience.familiar': 'Да, в похожих приложениях',
    'survey.experience.familiar.hint': 'Anki, Quizlet и другие. Покажем только главное.',
    'survey.experience.confident': 'Да, уверенно',
    'survey.experience.confident.hint': 'Знакомство можно будет пропустить.',
    'survey.days.title': 'Сколько дней в неделю хотите заниматься?',
    'survey.days.hint': 'Это цель, а не обязательство: пропущенный день ничего не сжигает. Изменить её можно в профиле.',
    'survey.days.everyday': 'Каждый день',
    // Branch choice
    'branch.title': 'С чего начнём?',
    'branch.sample': 'Посмотреть на примере',
    'branch.sample.hint': 'Учебная колода о том, как устроена память: материалы, упражнения и короткое занятие.',
    'branch.sample.stamp': 'Советуем для начала',
    'branch.community': 'Найти готовую колоду',
    'branch.community.hint': 'Колоды Сообщества по вашим темам. Понравившуюся можно добавить к себе.',
    'branch.own': 'Создать свою',
    'branch.own.hint': 'Название, первый материал и упражнение — и можно заниматься.',
    'branch.continue': 'Начать',
    // Tour: the sample deck
    'sample.1.title': 'Это ваша учебная колода',
    'sample.1.body': 'Здесь название, описание и все <span class="key">материалы</span> колоды. Её можно менять и учить, как свою.',
    'sample.2.title': 'Материал — то, что вы учите',
    'sample.2.slide1': 'Текст можно оформить: выделить <span class="key">жирным</span>, собрать в список, добавить цитату или ссылку.',
    'sample.2.slide2': 'В материал можно добавить <span class="key">картинку</span> и <span class="key">аудио</span>.',
    'sample.2.slide3': 'У каждого материала — свои упражнения. Их можно добавить позже.',
    'sample.3.title': 'Упражнения проверяют, что вы помните',
    'sample.3.body': 'Одно и то же можно спросить по-разному: выбрать ответ, вписать слово или <span class="key">объяснить своими словами</span>. Объяснение проверит ИИ.',
    'sample.4.title': 'Пройдите первое занятие',
    'sample.4.body': 'Короткое занятие — несколько упражнений. Дальше Mnema сама подскажет, что и когда повторить.',
    'sample.4.action': 'Начать занятие',
    // The sample deck outside the tour
    'sample.stamp': 'Учебная',
    'sample.plaque': 'Учебная колода: образец, к которому можно возвращаться',
    'sample.share.refused': 'Учебной колодой поделиться нельзя. Создайте свою — ею можно.',
    'sample.ai.free': 'В учебной колоде 3 проверки ИИ бесплатны. Осталось: {n}.',
    'sample.ai.spent': 'Бесплатные проверки в учебной колоде закончились. Дальше проверка расходует ваш ИИ-бюджет.',
    // Tour: community
    'community.1.title': 'Поиск и полка «Для вас»',
    'community.1.body': 'Ищите по названию или теме. На полке <span class="key">«Для вас»</span> — колоды по темам, которые вы выбрали.',
    'community.2.title': 'Что внутри колоды',
    'community.2.body': 'Здесь видно, сколько в колоде материалов, какие в ней упражнения и кто её автор.',
    'community.3.title': 'Добавьте колоду к себе',
    'community.3.body': 'Копия станет вашей: её можно учить и менять. Если автор обновит колоду, вы решите, принять ли изменения.',
    'community.3.action': 'Добавить к себе',
    'community.4.title': 'Пройдите первое занятие',
    'community.4.body': 'Колода уже у вас. Начните с короткого занятия — дальше Mnema сама подскажет, что повторить.',
    'community.browse.hint': 'Не знаете, что выбрать? Начните с темы из вашего списка.',
    'community.stuck.title': 'Не нашли подходящую?',
    'community.stuck.body': 'Создайте свою колоду — это займёт пару минут.',
    'community.stuck.action': 'Создать свою',
    // Tour: own deck
    'own.1.title': 'Назовите колоду',
    'own.1.body': 'Название и пара слов о том, что внутри. Всё это можно поменять позже.',
    'own.2.title': 'Первый материал',
    'own.2.body': 'Впишите текст сами или попросите <span class="key">ИИ</span> собрать материал из ваших заметок.',
    'own.2.body.noai': 'Впишите текст: определение, правило или слово с переводом.',
    'own.3.title': 'Первое упражнение',
    'own.3.body': 'Выберите, как проверять себя: вопрос и ответ, пропуск в тексте или выбор из вариантов.',
    'own.4.title': 'Пройдите первое занятие',
    'own.4.body': 'Всё готово. Начните занятие — дальше Mnema сама подскажет, что и когда повторить.',
    // Finale
    'ai.eyebrow': 'ИИ в Mnema',
    'ai.title': 'Найти материал легко. Выучить — труднее',
    'ai.body': 'Трудно не найти материал, а упорядочить его и действительно выучить. ИИ в Mnema за минуты соберёт материалы и упражнения, а повторять поможет расписание.',
    'ai.workshop': '<span class="key">Мастерская</span> превращает заметки в материалы и упражнения.',
    'ai.check': '<span class="key">Проверка ИИ</span> оценивает ответ, данный своими словами.',
    'ai.free': 'Бесплатно каждый месяц: {freeAllowance}.',
    'ai.plans': 'Тарифы',
    'ai.next': 'Дальше',
    'congrats.eyebrow': 'Знакомство завершено',
    'congrats.title': 'Начало положено',
    'congrats.body': 'Это ваш обычный путь в Mnema: колода, материалы, занятие. Все возможности собраны в <a href="#path">«Пути»</a> в профиле.',
    'congrats.action': 'К колоде',
    // Profile
    'path.replay': 'Пройти знакомство заново',
    // From the prototype's markup, not from its text list
    'tour.slider.label': 'Пояснения к шагу',
    'survey.progress.label': 'Пройдено вопросов',
    'survey.progress.value': '{n} из 4',
    // pending owner approval (S1): the replay of «Своя колода» for an account that already has decks
    'replay.own.title': 'С какой колоды начнём?',
    'replay.own.new': 'Новая колода'
} as const;

/** Keys that are not in the approved text list of the prototype (texts.js): the markup of the prototype, or new strings awaiting approval. */
export const UNLISTED_TEXT_KEYS: readonly OnboardingTextKey[] = ['tour.slider.label', 'survey.progress.label', 'survey.progress.value', 'replay.own.title', 'replay.own.new'];

export type OnboardingTextKey = keyof typeof ONBOARDING_TEXTS;

/** The text for `key` with its `{placeholders}` filled; an unfilled one stays visible so a missing value is noticed. */
export function onboardingText(key: OnboardingTextKey, params: Readonly<Record<string, string | number>> = {}): string {
    return ONBOARDING_TEXTS[key].replace(/\{(\w+)\}/gu, (_, name: string) => String(params[name] ?? `{${name}}`));
}
