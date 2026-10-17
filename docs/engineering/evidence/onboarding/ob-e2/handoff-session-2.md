---
artifact:
  id: onboarding-handoff-session-2
  type: handoff
  title: "Onboarding S2: OB-E2 — «Моя первая колода»"
  status: current
  created_at: "2026-10-17"
  owners: ["project-owner"]
---

# Handoff S2 — OB-E2 «Моя первая колода»

Сессия S2 по [prompts](../../../onboarding/prompts.md#s2--ob-e2-моя-первая-колода): OB-201…OB-204
([эпик](../../../onboarding/epics/ob-e2-sample-deck.md), Issues #526–#530), [ADR-ONB-003](../../../onboarding/adr/adr-onb-003-sample-deck.md).

## База и HEAD

| Поле | Значение |
|---|---|
| Ветка | `feat/517-onboarding` (worktree `/Users/yuzuru/Projects/Mnema-wt-onboarding`), draft PR [MattoYuzuru/Mnema#554](https://github.com/MattoYuzuru/Mnema/pull/554) |
| База | S1 05c1d45c + docs OB-003 a003caf3 (другая сессия); `origin/main` без новых коммитов на 2026-10-17 |
| Код на gate | 3ecff102; handoff и progress — следующий коммит, только документы |
| Push | не выполнялся (поручения не было): PR #554 показывает только S0 |
| Ведущий / саб-агенты | Claude Opus 5.5 (integrator: контракт, документы, сборка и установка шаблона, local stack, браузерная проверка, коммиты); код — 2 × Sonnet 5.5 high (backend; frontend); MnemaDeckStudio — Sonnet 5.5 high (импортёр, вариант A) и Sonnet 5.5 high (автор pack); review — Opus 5.5 high (код S2) и Opus 5.5 high (содержание колоды). Один раз одновременно работали три саб-агента (два исправления review + автор pack) — превышение лимита «не больше двух», без последствий для файлов (разные деревья) |

## Проверка предыдущей работы (S1)

Коммиты S1 (4d824cc5…05c1d45c) входят в HEAD; полный gate S1 на 748043a2 был зелёным, после него до начала S2 менялись только
документы (05c1d45c, a003caf3). TODO в коде онбординга имеют владельцев (#409, OB-203 — закрыт в S2). AC OB-101…105 отмечены со
ссылками; открытые AC S1 (VoiceOver, resize/scroll, retained stack) — без изменений, кроме OB-104 «local stack»: в S2 ветка
«На примере» пройдена на retained local stack (см. ниже). Расхождений канона не найдено.

## Что сделано по ID

| ID | Результат | Ключевые коммиты |
|---|---|---|
| OB-201 | V73 `deck.purpose` (+ `deck_one_live_sample`, `deck_head_guard` с единственным переходом USER→SAMPLE_TEMPLATE, индекс бесплатных проверок); `SampleDeckService` + `POST /api/onboarding/sample-deck` (201/200, одна живая копия, advisory-lock, receipts); пометка шаблона при старте (`SampleTemplateMarker`, только PRIVATE/LINK); `DeckPurposeGuard` (публикация, доступ, приглашения, запросы, копия по коду → 409 `SAMPLE_DECK_NOT_SHAREABLE`) + инвентарный тест маршрутов; исключения engagement/catalog outbox/events/interests; `sampleDeck` в виде | 89dcc666, 9fe582df, 482de3df |
| OB-202 | MnemaDeckStudio: импортёр ИИ-заданий (вариант A: `ai-semantic`/`TEXT_OR_SPEECH` только при live capability и с SELF_CHECK того же objective; тесты 98 OK); pack «Как устроена память» (11 материалов, 31 упражнение, 7 механик, 2 ИИ-задания, 4 клипа, 3 собственные схемы), независимое review содержания PASSED; сборка и установка на local stack (deck `d59520c8-d144-49f5-910b-4aaf833b8434`, LINK `xLZLdhEjrd`, редакция — локальный аккаунт `mnema_editorial_1a434184`); [runbook](sample-template-runbook.md) | студия вне git; ea723063, c9eb70c5 |
| OB-203 | Ветка тура `SAMPLE` v1 (хаб → материал со слайдером → упражнения → «Учить»), создание копии с ожиданием, плашка и штамп «Учебная» (`app-sample-plaque`, сначала в стайлгайде), скрытие «Поделиться»/доступа, тексты 409, подсказка бесплатных проверок; backend: `ONBOARDING_SAMPLE_CHECK` (0-кредитный DEBIT) для первых 3 проверок ИИ аккаунта в учебной колоде | 691d1919, 96c15aaf, 729e6fd4 |
| OB-204 | Независимое review (0 blockers, 3 medium + 12 low — исправлены или записаны), полный gate, браузерная проверка ветки на local stack, этот handoff | — |

## Отклонения от канона и почему

Записаны в [architecture §13](../../../onboarding/architecture.md#13-уточнено-реализацией-s2-ob-e2-2026-10-17) и
[proposal «Уточнено реализацией (S2)»](../../../onboarding/proposal.md#уточнено-реализацией-s2-ob-e2-2026-10-17):

- **Шаблон публикуется на `LINK`**, не приватно: только так копии получают «Есть обновление» (CD-5). Следствие (L4 review,
  **на решение владельца**): код ссылки шаблона виден владельцам копий, шаблон читается по ссылке (копия по коду — 409).
- **Пометка шаблона без API**: `template-id` в конфиге + пометка при старте; PUBLIC/INVITE-колода не помечается.
- **Первое занятие** — существующий пресет «Короткая» (на копии — 2 задания), без нового пресета на 4 (новый UI-контракт).
- **Голосовой ответ на задание с ИИ-оценкой** расходует две бесплатные проверки (распознавание не связано с попыткой); при
  остатке 1 подсказка для голосового ответа скрыта.
- **Системная копия не зависит от `learning.community.forks.enabled`** (онбординг независим от запуска Сообщества).

## Независимое review и исправления

- Код S2 (Opus high): 0 blockers. Исправлено: M1 предел бесплатных проверок 0–3 (клиент и текст «3 проверки»), M2 обычная копия
  шаблона больше не выдаётся за учебную (409), M3 пометка не трогает PUBLIC/INVITE-колоду, L3 тур догоняет материал, L5 скрытый
  шаблон — непрозрачный 404, L10 «На примере» предлагается при живой копии. Записаны как принятое поведение: L1, L2, L6–L9, L11, L12
  (guide.md, runbook, контракт).
- Содержание колоды (Opus high): главное — неверный вывод из Dunlosky 2013 о маркере, текст про кнопку «Учить» вместо
  «Начать занятие», два задания на арифметику вместо темы, мягкая проверка ответов (е/ё, точка), уточнения по Murre & Dros,
  Kornell & Bjork, Cepeda 2008, Rohrer & Taylor; цитата Эббингауза сверена дословно.
- Real browser на local stack нашёл **B0-повтор**: `POST /api/onboarding/sample-deck` не было в allowlist `auth.interceptor`
  (401); исправлено, и добавлен `onboarding-credentials.spec.ts`, который берёт все `/api`-маршруты из контракта и падает на
  пропущенном.

## Результаты gate (точный commit 3ecff102)

Все шаги — `workstation-dev` `dev.py run` под блокировкой `heavy`, JDK 25.0.4.1, Node 24.21.0, Colima; каждый receipt `ok`,
`source_changed: false`, HEAD 3ecff102 (код = 729e6fd4 + снимки evidence; в дереве только чужая правка README прототипа). Первый
прогон на 729e6fd4 отклонён раннером (`source_changed`: во время него в дерево были скопированы снимки) — Gradle в нём был зелёным.

| Команда | Итог |
|---|---|
| `(cd backend && ./gradlew clean quality)` | OK за 15 мин: 3484 JUnit-теста (identity + learning), 0 падений, 0 ошибок, 25 пропущено (opt-in live/eval, как в S1); пороги покрытия (learning 96,26 %, identity 93,08 % по отчёту саб-агента) |
| `npm ci` · `npm run lint` · `npm run test` · `npm run build` · `node scripts/verify-no-styleguide.mjs` | OK: 311 файлов, 4413 тестов; statements 92,22 %, branches 87,7 %, functions 91,02 %, lines 95,48 %; build — предупреждения «is not ESM» из `mermaid` (как в main) |
| `python3 scripts/verify_docs.py` | OK (на коде; повторно на коммите handoff) |
| harness: unit 47 + `run.py --onboarding` | OK (сценарий S1 на коде S2; ветку SAMPLE harness не покрывает — она проверена на local stack) |

## Установка шаблона и local stack

- Local stack `mnema-local-v2` перезапущен **из worktree ветки** (volumes и state dir сохранены, `MNEMA_LOCAL_OAUTH_ENV_FILE` —
  `.env` main checkout) с флагами онбординга и заглушками ИИ (`LEARNING_AI_PROVIDER=stub`, `aiAssessment`, `speechToText`) и
  `LEARNING_ONBOARDING_SAMPLE_DECK_TEMPLATE_ID=d59520c8-…`. Один запуск упал на таймауте загрузки Gradle внутри сборки образа
  (сеть), лаунчер при этом остановил стек; повтор прошёл. **Стек сейчас работает на коде ветки** — до merge владелец видит
  онбординг; вернуть main — обычный `start` из `/Users/yuzuru/Projects/Mnema` (Flyway игнорирует будущие V72/V73).
- Студия: `studio approve` по digest плана `16d97a2b…dd0b` с цитатой поручения 2026-10-17; pack `9385c786…`; Google Batch TTS 4
  клипа, **$0.005241** (лимит $0.25); `publish` → COMPLETE (11 / 31 / 2 ИИ, 7 медиа); `verify_live_generic.py` —
  `PASSED_RUNTIME_API` (материалы 11/11, медиа 7/7, preview и Practice по 7 механикам, 18 попыток, 0 ошибок).
- API на стеке: новый аккаунт — `available=true`; POST → 201, повтор → 200 тот же deckId; публикация копии и копия шаблона по
  коду → 409 `SAMPLE_DECK_NOT_SHAREABLE`; чтение шаблона по ссылке — 200 (принятое следствие LINK).
- Браузер (встроенный, ширина ≈ 400 px): приветствие → опрос (пропуски, 3 дня) → выбор «Посмотреть на примере» → копия → шаги
  1–4 (хаб со штампом и плашкой, материал со слайдером, упражнения, «Учить») → занятие «Короткая» без вуали (2 задания) → «Начало
  положено» → сервер `COMPLETED`, `branch=SAMPLE`; в хабе учебной колоды нет «Поделиться»/доступа. Снимки — в этой папке
  (`local-sample-*.jpg`).

## Миграции и rollback

V73 `deck.purpose` (metadata-only ALTER с default, частичные индексы, замена `deck_head_guard`) — additive; применена к retained
local DB. Rollback поведения — `learning.onboarding.sample-deck.enabled=false` (новые копии не создаются, созданные остаются;
бесплатные проверки — `free-ai-checks=0`). Production-флаги не менялись (по умолчанию off), production-установки шаблона нет.

## Что не проверено

- В браузере: бесплатные проверки ИИ (только integration/component tests), «Есть обновление» шаблона в копии (integration test),
  отказ материализации и `failed`/«Повторить» на учебной колоде, VoiceOver, forced-colors/320 px ветки SAMPLE (mobile ≈ 400 px
  проверен), desktop-раскладка ветки.
- Аудио клипов не прослушано (оценка длины); вид блоков в браузере проверен выборочно (материал 01).
- Шаг «упражнения» тура открывается на странице `exercises/new` (там список упражнений материала) — работает, но выглядит как
  переход в создание упражнения; на 400 px рамка выреза слегка заходит на верх нижнего листа (косметика).
- Account purge учебных колод, `ONBOARDING_SAMPLE_CHECK` и receipts — TODO #409.
- В этой сессии в вывод инструмента однажды попало значение демо-пароля терминала T-Bank из окружения local stack (фильтр
  маскирования не сработал на одной строке); в документы и коммиты не попало — владельцу стоит решить, менять ли его.

## Статус

OB-201, OB-202 (local), OB-203, OB-204 — local complete, delivery pending. Production-установка шаблона и флаги — отдельное
поручение (после OB-002 и решения по L4/LINK). Ветка не запушена; доска не менялась.

## Следующий шаг

Владельцу: решить L4 (шаблон на `LINK` виден по ссылке владельцам копий) и утвердить 2 новые строки S1; затем push ветки и
вывод PR #554 из draft, после — S3 (OB-E3 «Путь Mnema») от этой ветки.
