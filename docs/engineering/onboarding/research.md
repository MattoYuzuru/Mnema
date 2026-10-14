---
artifact:
  id: onboarding-research-2026-10
  type: research
  title: "Onboarding primer and evidence ledger"
  status: proposed
  created_at: "2026-10-14"
  evidence_accessed: "2026-10-14"
  owners: ["project-owner"]
---

# Онбординг: словарь и доказательная база

Веб-исследование 2026-10-14 (отдельный research-агент, только чтение) плюс сверка с кодом
кода эпиков Сообщества 3–4. Метки: **[S]** — рецензируемое исследование, крупный first-party датасет, стандарт
или текст закона; **[V]** — бенчмарк вендора онбординг-инструментов (направление, а не истина);
**[O]** — мнение/разбор практиков; **[I]** — наш вывод. «⚠ старое» — источник до 2019 года.

## 1. Курс дела: какие онбординги бывают

| Паттерн | Простыми словами | Где уместен |
|---|---|---|
| Приветственный опрос (intent survey) | 1–3 вопроса в начале («что хотите учить?»), ответ меняет следующий экран | Везде, но только если ответ видимо что-то меняет |
| Продуктовый тур: modal, spotlight, coachmarks, tooltips, hotspots | Цепочка подсказок поверх интерфейса; spotlight — затемнение с «окном» вокруг элемента; hotspot — пульсирующая точка, которую открывают по желанию | Сложные инструменты; слаб для простых consumer-приложений |
| Интерактивный walkthrough (action-gated) | Шаг ждёт, пока пользователь сам сделает действие | Создание первого объекта (колоды, материала) |
| Чек-лист | Постоянный список первых задач с прогрессом | Продукты с несколькими сильными функциями |
| Пустые состояния (empty states) | Пустой экран объясняет, почему пусто, и даёт первое действие | Везде; самый дешёвый и полезный паттерн |
| Образцы и шаблоны (sample content) | Аккаунт стартует с примером: «Getting Started» в Notion, задачи-обучалки Linear | Инструменты создания, каталоги |
| Контекстный, «just-in-time» | Подсказка в момент первой встречи с функцией | Везде, особенно в обучении |
| Геймификация | Серии (streaks), значки, полосы прогресса | Привычки, consumer-обучение |
| Ветвление по персоне/цели | Разные пути для разных целей | Продукты с разными группами |
| Reverse trial / место paywall | Премиум на N дней, затем Free; или paywall после первой ценности | Freemium |

Обоснование: опрос оправдан, только если ответ меняет следующий шаг [V] ([Appcues](https://www.appcues.com/blog/user-onboarding),
[Chameleon](https://www.chameleon.io/blog/onboarding-flows)). NN/g советует по возможности обходиться
без онбординга и делать обучающую часть короткой и необязательной [S/O]
([NN/g mobile onboarding](https://www.nngroup.com/articles/mobile-app-onboarding/)); помощь в момент
нужды работает лучше вводных туториалов [O] ([NN/g](https://www.nngroup.com/articles/onboarding-tutorials/)),
туториалы эффективнее рядом с механикой, которой учат [S] ([обзор](https://pmc.ncbi.nlm.nih.gov/articles/PMC9676530/)).
Пустое состояние: объяснить пустоту, назначение области и дать путь к ключевому действию
([NN/g](https://www.nngroup.com/articles/empty-state-interface-design/)). Notion — страница-чек-лист и вопрос
«для чего» при регистрации [O] ([GoodUX](https://goodux.appcues.com/blog/notions-lightweight-onboarding));
Linear — задачи-обучалки в самом списке, без туров [O] ([Candu](https://www.candu.ai/blog/linear-onboarding-teardown));
Todoist — шаблон онбординга [S] ([Todoist](https://www.todoist.com/templates/todoist-onboarding)).
Reverse trial: заявленный прирост 10–40 % — оценка практика, не датасет [O] ([Verna](https://www.elenaverna.com/p/reverse-trials-examples)).

**Для Mnema [I]:** продукт одновременно обучающий (занятия), инструмент создания (Мастерская, редактор)
и каталог (Сообщество). Значит нужны: короткий опрос, образец («Моя первая колода»), action-gated
создание первого, контекстные подсказки, чек-лист и мягкая привычка; длинный тур — нет.

## 2. Длина тура, завершение, пропуск

| Источник | Выборка | Находка |
|---|---|---|
| Chameleon 2019 [V] ⚠ | 15 млн взаимодействий | ≤ 4 шагов; длинное делить на короткие туры по запросу ([PDF](https://www.chameleon.io/assets/chameleon-product-tour-benchmarks-report-2019.pdf)) |
| Chameleon 2022 [V] | — | 3 шага ≈ 72 % завершения, 7 шагов ≈ 16 % ([отчёт](https://www.chameleon.io/benchmark-report-2022)) |
| Chameleon 2023 [V] | 212 млн | Среднее 30 %; лучшие — 4 шага; резкий спад с 5+; запущенные пользователем 61,7 %, по элементу 69,6 %; ~1 из 5 отложенных потом завершают ([отчёт](https://www.chameleon.io/benchmark-report-2023)) |
| Chameleon 2025 [V] | 550 млн+ | Запущенные пользователем 67 % против 31 %; после 5 шагов теряется больше половины; индикатор прогресса +12 %; топ-1 % — все ≤ 5 шагов и запущены пользователем ([отчёт](https://www.chameleon.io/benchmark-report)) |

Оговорки: данные — клиенты Chameleon (в основном B2B SaaS), «завершение» = прокликивание, не понимание.
Тиражируемые в блогах «61 % в среднем» смешивают годы — не цитировать. Утверждение «после 14+ шагов
бросают» в первоисточниках не найдено; реальная граница ниже — 4–5 шагов.

Помогают ли туториалы: контролируемый тест NN/g (70 человек, 4 приложения) — успешность задач 91 % с
туториалом «колода карточек» против 94 % без него (незначимо), читавшие оценили задачи как более трудные
[S, мало] ([NN/g](https://www.nngroup.com/articles/mobile-tutorials/)); coachmarks быстро закрывают, одна
подсказка — одно взаимодействие [O ⚠] ([NN/g 2014](https://www.nngroup.com/articles/mobile-instructional-overlay/));
Andersen et al., CHI 2012 (~45 тыс. игроков): туториал +29 % времени игры только в самой сложной игре, в
простых эффекта нет [S ⚠] ([ResearchGate](https://www.researchgate.net/publication/254005149_The_Impact_of_Tutorials_on_Games_of_Varying_Complexity)).
Количественного доказательства «опытные пропускают» не найдено — правдоподобно, но не измерено [I].

**Вывод для Mnema:** ≤ 4 шага на ветку, тур только по выбору пользователя («Познакомиться» на
приветствии и «Пройти заново» в профиле), прогресс «Шаг N из M», длинные сценарии — отдельными
побочными квестами со стрелки «Пути».

## 3. Чек-листы и прогресс

- **Endowed progress** (Nunes & Drèze 2006, JCR): карта на 10 штампов с 2 подаренными — 34 % завершили
  против 19 % у пустой на 8, и возвращались быстрее [S ⚠] ([SSRN](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=991962)).
- **Goal-gradient** (Kivetz, Urminsky & Zheng 2006, JMR): ближе к награде — чаще покупки; иллюзорный
  старт ускоряет; после награды мотивация сбрасывается [S ⚠] ([обзор](https://bookdown.org/mike/social-theory/goal-gradient-hypothesis.html)).
- **Зейгарник не подтверждается:** мета-анализ 2025 — преимущества запоминания незавершённого нет;
  эффект Овсянкиной (возврат к прерванному ~67 % против 50 %) воспроизводится [S]
  ([Nature HSSC](https://www.nature.com/articles/s41599-025-05000-w)). Честное обоснование чек-листа —
  «к незаконченному возвращаются», а не «незаконченное помнят».
- Вендоры: среднее завершение чек-листов 19,2 %, медиана 10,1 %, EdTech 15,9 % [V]
  ([Userpilot](https://userpilot.com/blog/product-metrics-benchmark-report/)); 27 % выполнили действие
  чек-листа после Welcome State [V] ([Chameleon](https://www.chameleon.io/benchmark-report)).

**Вывод:** отмечать только реально сделанное (это и есть честный «подаренный» прогресс); показывать
текущую главу из 3–5 вех, остальные свёрнуты; позволять скрыть «Путь»; полное прохождение — не цель.

## 4. Серии, достижения, геймификация

Duolingo (first-party A/B, относительные эффекты) [S]: Streak Wager — D7 retention +14 % ⚠;
серия отделена от дневной цели, один урок сохраняет серию — D14 +3,3 %, DAU +1 %
([блог](https://blog.duolingo.com/improving-the-streak)); вторая заморозка — DAU +0,38 %; серия 7 дней —
в 3,6 раза чаще заканчивают курс (корреляция) ([блог](https://blog.duolingo.com/how-duolingo-streak-builds-habit));
Streak Revival 2026 — 15,4 млн восстановили серию ([письмо акционерам, SEC](https://www.sec.gov/Archives/edgar/data/1562088/000162828026053299/q2fy26duolingo6-30x26share.htm)).
Общий вывод их опытов: **чем снисходительнее серия, тем выше вовлечённость.** Цифру «заморозка −21 % оттока» не цитировать — источник не подтверждён.

Академически [S]: прерванная серия снижает дальнейшее участие сильнее, если человек винит себя, и слабее,
если серию можно «починить» (Silverman & Barasch 2023, JCR, [INSEAD](https://www.insead.edu/faculty-research/publications/journal-articles/or-track-how-broken-streaks-affect-consumer));
«аварийный запас» дней повышает настойчивость после срыва, до +40 % дней с выполненной целью
(Sharif & Shu 2021, [PDF](https://anderson-review.ucla.edu/wp-content/uploads/2021/03/Sharif-Shu_EmergencyReserveFailure_OBHDP2019.pdf));
эффект «да гори оно всё» после ощущения провала ([обзор](https://pmc.ncbi.nlm.nih.gov/articles/PMC7096476/));
тревога из-за серии у пользователей Duolingo, жалобы тех, кто учится по выходным ([arXiv](https://arxiv.org/pdf/2203.16175)).

Геймификация в обучении: мета-анализ Sailer & Homner 2020 — когнитивный эффект g = 0,49 (устойчив),
мотивационный 0,36 и поведенческий 0,25 (менее устойчивы) [S] ([ERIC](https://eric.ed.gov/?id=EJ1245270));
значки + рейтинг в курсе снизили мотивацию и оценку экзамена (Hanus & Fox 2015, n = 71) [S ⚠]
([ScienceDirect](https://www.sciencedirect.com/science/article/abs/pii/S0360131514002000)); ожидаемые
материальные награды подрывают внутреннюю мотивацию, информативная похвала — повышает
(Deci, Koestner & Ryan 1999, спорно) [S ⚠] ([PDF](https://home.ubalt.edu/tmitch/642/articles%20syllabus/Deci%20Koestner%20Ryan%20meta%20IM%20psy%20bull%2099.pdf)).

**Вывод:** недельная цель как встроенный запас, никаких «серия сгорела», тихие информативные поздравления
(«вспомнили 18 из 20»), без рейтингов и очков — это и лежит в [ADR-ONB-004](adr/adr-onb-004-rhythm.md)
и [ADR-ONB-002](adr/adr-onb-002-milestones.md).

## 5. Как стартуют обучающие продукты

| Продукт | Что спрашивает/делает | Источник |
|---|---|---|
| Duolingo | Язык, цель, «зачем», тест уровня; первый урок **до** регистрации (+≈20 % DAU от отложенной регистрации, интервью) | [First Round](https://review.firstround.com/the-tenets-of-a-b-testing-from-duolingos-master-growth-hacker/), [GoodUX](https://goodux.appcues.com/blog/duolingo-user-onboarding) |
| Babbel | Мотив, уровень или тест, недельная цель, пробный урок, свой экран перед запросом микрофона; 11–17 экранов — критикуют за длину | [Babbel](https://www.babbel.com/how-babbel-works), [ScreensDesign](https://screensdesign.com/showcase/babbel-language-learning) |
| Anki | Онбординга нет; руководство советует начать с общих колод | [Anki manual](https://docs.ankiweb.net/getting-started.html) |
| Quizlet | Учитель отмечает роль; предмет — через поиск, «найди набор и скопируй» | [гид учителя](https://quizlet.com/features/quizlet-teacher-guide-getting-started) |
| Brainscape | Три пути: создать (вручную/ИИ), найти публичные, сертифицированные; копировать и править | [справка](https://brainscape.zendesk.com/hc/en-us/articles/17773783760141-Everything-you-need-to-know-to-study-effectively-in-Brainscape) |
| RemNote | После регистрации — загрузить слайды/PDF, «RemNote за 5 минут» | [блог](https://www.remnote.com/blog/how-to-turn-lectures-into-flashcards) |
| Skyeng | Сначала цель, затем тест уровня под цель, ИИ-тест речи | [Skyeng](https://skyeng.ru/magazine/kak-ustroeny-testy-na-uroven-yazyka-obuyasnyayut-metodisty-skyeng/) |

Языковые приложения задают 3–6 вопросов, и каждый ответ что-то меняет; карточные инструменты опросов не
делают и ведут в каталог или к созданию. Mnema ближе к Brainscape/RemNote [I]. Разборы Memrise,
Яндекс Практикума, Puzzle English не найдены.

**О регистрации до ценности:** Duolingo выиграл от отложенной регистрации, владелец Mnema сознательно
держит цены и продукт за входом. Пакет это решение не меняет, но фиксирует альтернативу в [RFC](rfc.md) R6.

## 6. Скидка с ограниченным сроком

Короткий срок сертификата повышал погашение (31 % при 3 неделях против 6 % при 2 месяцах, n = 64) [S ⚠]
([Shu & Gneezy 2010](https://anderson-review.ucla.edu/wp-content/uploads/2021/03/Shu-Gneezy_ProcrastinationofEnjoyable_2010.pdf)),
но в полевом опыте эффект дедлайна немонотонен [S] ([MPRA](https://mpra.ub.uni-muenchen.de/109501/1/MPRA_paper_109501.pdf)).
Исследований именно «скидки после онбординга» нет. Фальшивые таймеры — признанный dark pattern:
393 таймера на 361 сайте, часть обманных ([Mathur et al. 2019](https://arxiv.org/pdf/1907.07032)); FTC 2022
(«Baseless Countdown Timer», [PDF](https://www.ftc.gov/system/files/ftc_gov/pdf/P214800+Dark+Patterns+Report+9.14.2022+-+FINAL.pdf));
ЕС: ложная ограниченность по сроку запрещена всегда (UCPD guidance 2021, Annex I № 7,
[EUR-Lex](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=OJ%3AC%3A2021%3A526%3AFULL)).

Россия [S, требует юриста]: 38-ФЗ «О рекламе» ст. 5 — условия скидки и срок должны быть видимы, не мелким
шрифтом ([garant](https://base.garant.ru/12145525/5633a92d35b966c2ba2f1e859e7bdd69/),
[ФАС](https://fas.gov.ru/pages/postateiny_kommentary)); ЗоЗПП ст. 16 — запрет «автоматического согласия»
на доп. услуги ([garant](https://www.garant.ru/news/1859822/)); ст. 16.1 — подписки и отмена
([Роскачество](https://rskrf.ru/tips/eksperty-obyasnyayut/kak-zashchitit-sebya-ot-avtomaticheskikh-spisaniy-za-podpiski/));
точный объём 376-ФЗ и штрафы 561-ФЗ сверить по текстам законов.

**Вывод:** реальная фиксированная дата («до 21 октября, 23:59 МСК»), выдача на сервере, без сброса и
секундного таймера, срок 72 ч – 7 дней, условия и сочетаемость с промокодами текстом — [ADR-ONB-005](adr/adr-onb-005-path-reward.md).

## 7. Доступность тура и удержания

WCAG 2.2 ([W3C](https://www.w3.org/TR/WCAG22/)): 2.4.11 — оверлей не закрывает элемент в фокусе
([Understanding](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html)); 2.4.3 порядок
фокуса; 1.4.13 подсказка закрываемая и устойчивая; 4.1.3 объявление статуса; 2.2.1/2.2.2 — без
автоперелистывания; 2.3.3 и `prefers-reduced-motion`; 2.2.4 (AAA) — прерывания можно отложить, главный
аргумент против автозапуска туров. APG dialog: имя из заголовка, Tab внутри, Escape закрывает, фокус
возвращается к инициатору, модальность — только если фон реально заблокирован (`inert`)
([APG](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/)); немодальные диалоги недоспецифицированы [O]
([O'Hara](https://www.scottohara.me/blog/2023/01/26/use-the-dialog-element.html)).

**Удержание:** 2.5.1 не нарушается (удержание — не жест по траектории,
[Understanding 2.5.1](https://www.w3.org/WAI/WCAG22/Understanding/pointer-gestures.html)), но **2.1.1 (A)**
запрещает требовать особого времени нажатия клавиш — нужен путь без таймера (подтверждение или «Вернуть»)
([Understanding 2.1.1](https://www.w3.org/WAI/WCAG22/Understanding/keyboard.html)). Существующий
`app-hold-to-delete-button` на клавиатуре тоже требует удерживать Enter/Space — это отдельная a11y-находка
для стайлгайда, вне scope онбординга. Для обратимого завершения тура удержание не нужно вовсе.

## 8. Реализация в Angular

Проект — Angular **22.2.1**, `@angular/cdk` **не установлен** (только core-пакеты, ProseMirror, Mermaid).

| Вариант | Лицензия (проверено 2026-10) | Оценка |
|---|---|---|
| Нативная платформа: `<dialog>`, Popover API, `inert`, `ResizeObserver`, своя live-region | — | Уже используется в проекте (`app-action-menu` на `popover`, окно жалобы на `<dialog>`); без зависимостей; a11y под нашим контролем |
| `@angular/cdk` (Overlay, A11y) | MIT, first-party | Хорошие примитивы, но это новая зависимость — по root AGENTS только с обоснованием и разрешением |
| driver.js | MIT, v1.9.0, ~5 КБ | Нет документированного focus trap и объявлений; обёртка съест выгоду ([releases](https://github.com/kamranahmedse/driver.js/releases)) |
| Shepherd.js | **AGPL-3.0 или коммерческая** с v14 ([license](https://docs.shepherdjs.dev/guides/license/)) | Исключён лицензией |
| intro.js | **AGPL-3.0 или коммерческая** ([introjs.com](https://introjs.com/)) | Исключён лицензией |
| ngx-ui-tour | MIT, поддерживает Angular 22 ([releases](https://github.com/hakimio/ngx-ui-tour/releases)) | Нужен UI-адаптер дизайн-библиотеки или своя UI-обёртка |

Решение — [ADR-ONB-001](adr/adr-onb-001-tour-engine.md).

## 9. Активация и эксперимент

Активация для обучающего продукта [I]: первое завершённое занятие, наличие колоды, возврат во 2-й день в
первую неделю; определение проверять по связи с D30 (корреляция ≠ причинность). Средняя активация SaaS
37,5 % [V] ([Userpilot](https://userpilot.com/blog/product-metrics-benchmark-report/)) к Mnema не переносится.
Онбординг выпускать как A/B с **постоянным holdout 10–20 %** и смотреть D30/D60 и оплату, а не
завершение тура: краткосрочный прирост часто сжимается (Pinterest: 7 % → 2,5 % DAU)
([Pinterest](https://medium.com/pinterest-engineering/how-holdout-groups-drive-sustainable-growth-35a4786c3801)).
При трафике Mnema holdout 1–2 % бесполезен. События — first-party на сервере, агрегаты; без свободного
текста; Вебвизор на экранах опроса не включать.

## 10. ПДн опроса (152-ФЗ)

Ответы, связанные с аккаунтом, — персональные данные: нужна законная основа (ст. 6) и конкретная цель
(ст. 5) ([garant](https://base.garant.ru/12148567/493aff9450b0b89b29b367693300b74a/)). С 1 сентября 2025
(156-ФЗ) согласие, если основание — согласие, оформляется **отдельно** от других документов
([Контур](https://kontur.ru/articles/1577), [ст. 9](https://legalacts.ru/doc/152_FZ-o-personalnyh-dannyh/glava-2/statja-9/)).
Политика обработки публикуется (ст. 18.1); отзыв и уничтожение (ст. 21). Основание (исполнение договора
для персонализации или отдельное согласие) решает юрист — [OB-002](tasks/ob-002.md). Не спрашивать
возраст, здоровье, инвалидность.

## 11. Неопределённости

- Нет независимых данных о завершении туров в consumer/learning; Chameleon — B2B и прокликивание.
- Нет первичных разборов Memrise, Яндекс Практикума, Puzzle English, Canva, Figma.
- Нет исследования «скидка после онбординга»; данные о дедлайнах старые и противоречивые.
- Объём 376-ФЗ и суммы 561-ФЗ; основание обработки ответов опроса — к юристу.
- Поддержка CSS anchor positioning по браузерам — проверить по MDN Baseline при реализации; без неё
  позиционирование считается в JS.
- Реакция пожилой русскоязычной аудитории на серии и опросы может отличаться от западных выборок.
