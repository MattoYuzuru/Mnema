// OB-001 texts under owner review. One source for the prototype and for
// docs/engineering/evidence/onboarding/ob-prep/texts-for-approval.md (same IDs). Edit here, not in markup.
// Placeholders in {braces} are filled by the product at runtime.
export const T = {
  // Welcome and skip
  'welcome.eyebrow': 'Начало пути',
  'welcome.title': 'Mnema помогает надолго запомнить то, что вы учите',
  'welcome.lead': 'Покажем, как это работает. Это займёт две минуты.',
  'welcome.start': 'Познакомиться — 2 минуты',
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
  'survey.interests.hint': 'Выберите до 20 тем. По ним Mnema предложит колоды Сообщества.',
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

  // Branch
  'branch.title': 'С чего начнём?',
  'branch.sample': 'Посмотреть на примере',
  'branch.sample.hint': 'Учебная колода о том, как устроена память: материалы, упражнения и короткое занятие.',
  'branch.sample.stamp': 'Советуем для начала',
  'branch.community': 'Найти готовую колоду',
  'branch.community.hint': 'Колоды Сообщества по вашим темам. Понравившуюся можно добавить к себе.',
  'branch.own': 'Создать свою',
  'branch.own.hint': 'Название, первый материал и упражнение — и можно заниматься.',
  'branch.continue': 'Начать',

  // Tour chrome
  'tour.counter': 'Шаг {n} из {m}',
  'tour.back': 'Назад',
  'tour.next': 'Дальше',
  'tour.finish': 'Завершить знакомство',
  'tour.finish.armed': 'Удерживайте 2 секунды, чтобы завершить знакомство',
  'tour.finish.toast': 'Знакомство завершено. Пройти его снова можно в профиле.',
  'tour.gate': 'Нажмите «{action}», чтобы продолжить',
  'tour.missing': 'Этого места сейчас нет на экране. Продолжим со следующего шага.',
  'tour.preparing': 'Готовим колоду…',
  'tour.pause.label': 'Знакомство на паузе',
  'tour.pause.resume': 'Продолжить знакомство',
  'tour.slider.prev': 'Предыдущее пояснение',
  'tour.slider.next': 'Следующее пояснение',

  // Branch «На примере»
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
  'sample.stamp': 'Учебная',
  'sample.plaque': 'Учебная колода: образец, к которому можно возвращаться',
  'sample.share.refused': 'Учебной колодой поделиться нельзя. Создайте свою — ею можно.',
  'sample.ai.free': 'В учебной колоде 3 проверки ИИ бесплатны. Осталось: {n}.',
  'sample.ai.spent': 'Бесплатные проверки в учебной колоде закончились. Дальше проверка расходует ваш ИИ-бюджет.',

  // Branch «Сообщество»
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

  // Branch «Своя колода»
  'own.1.title': 'Назовите колоду',
  'own.1.body': 'Название и пара слов о том, что внутри. Всё это можно поменять позже.',
  'own.2.title': 'Первый материал',
  'own.2.body': 'Впишите текст сами или попросите <span class="key">ИИ</span> собрать материал из ваших заметок.',
  'own.2.body.noai': 'Впишите текст: определение, правило или слово с переводом.',
  'own.3.title': 'Первое упражнение',
  'own.3.body': 'Выберите, как проверять себя: вопрос и ответ, пропуск в тексте или выбор из вариантов.',
  'own.4.title': 'Пройдите первое занятие',
  'own.4.body': 'Всё готово. Начните занятие — дальше Mnema сама подскажет, что и когда повторить.',

  // Final and congratulations
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

  // Path
  'path.title': 'Путь Mnema',
  'path.intro': 'Что умеет Mnema. Пункт отмечается, когда вы делаете это на самом деле.',
  'path.total': '{done} из {total}',
  'path.hide': 'Скрыть «Путь»',
  'path.replay': 'Пройти знакомство заново',
  'path.state.done': 'Сделано',
  'path.state.todo': 'Не начато',
  'path.state.partial': 'Начато',
  'path.open': 'Открыть «Путь»',
  'path.new.stamp': 'Новое',
  'path.go': 'Перейти: {title}',
  'path.error': 'Не удалось загрузить «Путь».',
  'path.retry': 'Повторить',
  'path.new': '«Путь Mnema» — что умеет Mnema. Часть пунктов уже отмечена по вашей истории.',
  'path.toast': 'Путь: {event} · {done} из {total}',
  'path.toast.example': 'вы добавили первую картинку',
  'path.chapter.start': 'Начало',
  'path.chapter.materials': 'Материалы',
  'path.chapter.exercises': 'Упражнения и ИИ',
  'path.chapter.rhythm': 'Ритм и Сообщество',
  'm.GOAL_TOLD': 'Расскажите о цели', 'm.GOAL_TOLD.b': 'Подсказки и примеры подстроятся под неё.',
  'm.FIRST_DECK': 'Заведите первую колоду', 'm.FIRST_DECK.b': 'Свою, из Сообщества или учебную — подойдёт любая.',
  'm.FIRST_SESSION': 'Пройдите первое занятие', 'm.FIRST_SESSION.b': 'С него начинается расписание повторений.',
  'm.OWN_MATERIAL': 'Напишите свой материал', 'm.OWN_MATERIAL.b': 'Учите то, что нужно именно вам.',
  'm.MEDIA_IN_MATERIAL': 'Добавьте картинку или звук', 'm.MEDIA_IN_MATERIAL.b': 'Так материал запоминается лучше.',
  'm.CAPTURE_NOTE': 'Сохраните заметку «На потом»', 'm.CAPTURE_NOTE.b': 'Мысль не потеряется: разберёте её, когда будет время.', 'm.CAPTURE_NOTE.partial': 'Заметка сохранена, осталось её разобрать.',
  'm.OWN_EXERCISES': 'Создайте три своих упражнения', 'm.OWN_EXERCISES.b': 'Проверяйте себя так, как удобно вам.',
  'm.AI_EXPLANATION': 'Объясните своими словами', 'm.AI_EXPLANATION.b': 'ИИ проверит, понятна ли мысль, и подскажет, чего не хватает.',
  'm.VOICE_ANSWER': 'Ответьте голосом', 'm.VOICE_ANSWER.b': 'Удобно, когда заняты руки или сказать проще, чем напечатать.',
  'm.WORKSHOP_APPROVED': 'Соберите материалы в Мастерской', 'm.WORKSHOP_APPROVED.b': 'ИИ превратит заметки в материалы и упражнения, а вы решите, что оставить.',
  'm.RHYTHM_WEEK': 'Проведите неделю в ритме', 'm.RHYTHM_WEEK.b': 'Занимайтесь столько дней в неделю, сколько выбрали.',
  'm.TWO_DAYS': 'Позанимайтесь в другой день', 'm.TWO_DAYS.b': 'Повторение после паузы запоминается прочнее.',
  'm.COMMUNITY_COPY': 'Возьмите готовую колоду', 'm.COMMUNITY_COPY.b': 'В Сообществе есть колоды по вашим темам.',
  'hint.capture': 'Сохраните сюда мысль или ссылку — разберёте её потом.',
  'hint.ok': 'Понятно',

  // Rhythm
  'rhythm.summary.eyebrow': 'Занятие завершено',
  'rhythm.summary.title': 'Все упражнения на сегодня пройдены',
  'rhythm.summary.next': 'Следующее повторение — завтра.',
  'rhythm.day': '{ordinal} день на этой неделе',
  'rhythm.target': 'Цель — {days} в неделю',
  'rhythm.met.toast': 'Цель недели выполнена: вы занимались {days}.',
  'rhythm.profile.weeks': 'Недель в ритме: {n}',

  // Reward (production only after OB-002)
  'reward.eyebrow': 'Путь пройден',
  'reward.title': 'Вы освоили Mnema полностью',
  'reward.gift': 'Ваш подарок: −10 % на подписку до {date}.',
  'reward.terms': 'Предложение действует один раз. Условия и сочетание с промокодами — на странице тарифов.',
  'reward.action': 'Выбрать тариф',
  'reward.later': 'Позже',
};

// Plural and ordinal helpers the product supplies through Intl.PluralRules('ru'); `{days}` is «2 дня», «5 дней».
const ORDINALS = ['Первый', 'Второй', 'Третий', 'Четвёртый', 'Пятый', 'Шестой', 'Седьмой'];
const plural = new Intl.PluralRules('ru');
export const days = n => `${n}\u00a0${{ one: 'день', few: 'дня', many: 'дней', other: 'дня' }[plural.select(n)]}`;
export const ordinal = n => ORDINALS[n - 1];

export const t = (id, params = {}) => {
  const text = T[id];
  if (text === undefined) throw new Error(`No text ${id}`);
  return text.replace(/\{(\w+)\}/g, (_, key) => params[key] ?? `{${key}}`);
};
