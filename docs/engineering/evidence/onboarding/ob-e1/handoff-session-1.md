---
artifact:
  id: onboarding-handoff-session-1
  type: handoff
  title: "Onboarding S1: OB-E1 — первый вход, опрос, тур, holdout"
  status: current
  created_at: "2026-10-17"
  owners: ["project-owner"]
---

# Handoff S1 — OB-E1 «Первый вход, опрос и тур»

Сессия S1 по [prompts](../../../onboarding/prompts.md#s1--ob-e1-первый-вход-опрос-и-тур): OB-101…OB-105
([эпик](../../../onboarding/epics/ob-e1-first-entry-and-tour.md), Issues #520–#525).

## База и HEAD

| Поле | Значение |
|---|---|
| Ветка | `feat/517-onboarding` (worktree `/Users/yuzuru/Projects/Mnema-wt-onboarding`), draft PR [MattoYuzuru/Mnema#554](https://github.com/MattoYuzuru/Mnema/pull/554) |
| База | S0 HEAD 71ab1c89 + merge `origin/main` b6f14df8 (a29b7301, 2026-10-16) |
| Код на gate | 748043a2 (полный gate и harness — ниже); handoff и progress — следующий коммит, только документы |
| Push | не выполнялся: ветка локальная, PR #554 показывает только S0 |
| Ведущий / саб-агенты | Claude Opus 5.5 (integrator: контракт, отчёт кабинета, документы, мелкие правки, коммиты); код — 2 × Sonnet 5.5 high (backend; frontend); harness — Sonnet 5.5 high; review — 3 × Opus 5.5 high (backend, frontend, повторный frontend). Одновременно ≤ 2 |

## Проверка предыдущей работы (S0)

Коммиты S0 (0801bcb7…71ab1c89) входят в HEAD; AC OB-001 отмечены со ссылками; тексты в
`design/prototype/onboarding/texts.js`, их дословность теперь проверяет `onboarding-texts.spec.ts`; `verify_docs` зелёный.
Кода продукта S0 не меняла — тестов для перепроверки не было. Расхождений не найдено. В worktree лежит **чужая**
незакоммиченная правка `design/prototype/onboarding/README.md` (2 строки «утверждено 2026-10-16») — не трогал, не коммитил.

## Что сделано по ID

| ID | Результат | Ключевые коммиты |
|---|---|---|
| OB-101 | Контракт `contracts/onboarding/` (README, `journey.json`: виды, таблица переходов, реестр 20 шагов, ошибки, отчёт); V72 `onboarding_journey` (+ `cohort`); `GET /api/onboarding` (ETag/304, одна строка при параллельных GET), `PUT /journey`, `PUT /preferences` (receipts, CAS, 409/412/428); флаги `learning.onboarding.enabled/eligible-from` (prod off, local on) | 4d824cc5, 9b2b69c5, a0d2bf3b, 70d6b537 |
| OB-102 | `core/onboarding`: `JourneyStore`, `TourEngine`, `mnTourAnchor` + реестр ключей, `TourEvents`, вуаль (SVG even-odd), `app-tour-card` (`<dialog>` show/showModal, нижний лист < 600 px), `app-night-sky`, `app-tour-pause`; удержание 2 с — параметры общего `app-hold-to-delete-button`, знак `finger-hold`; 10 токенов; стайлгайд «Знакомство», правило 4 | 213d67c6, 18f78cc1 |
| OB-103 | «Начало пути» (приветствие, опрос 4 карточки, выбор ветки), `AttentionCoordinator`, промо ждёт журнал, `GoalOnboarding` по правилу `surveyAsksTheGoal` | e5f30a26, e034d361, 335e59bf, 062ed44b, 748043a2 |
| OB-104 | Туры v1 `COMMUNITY` и `OWN` (≤ 4 шагов, основные шаги для FAMILIAR/CONFIDENT), пауза на занятии и финал, «ИИ в Mnema» (скрыта без ИИ), поздравление, «Пройти знакомство заново» в профиле, второе устройство | те же |
| OB-105 | Holdout `onboarding_v1` (`control:15,tour:85`, server-only), когорта в строке, EXPOSURE после коммита; отчёт `GET /api/admin/onboarding/experiment` + `/manage/onboarding`; метрики `mnema_onboarding_journey_total{action,step}`; harness `--onboarding` | 9b2b69c5, 420d43cb, a0d2bf3b, 77294b3a |

## Отклонения от канона и почему

Записаны в [architecture §12](../../../onboarding/architecture.md#12-уточнено-реализацией-s1-ob-e1-2026-10-16)
и [proposal «Уточнено реализацией»](../../../onboarding/proposal.md#уточнено-реализацией-s1-ob-e1-2026-10-17):

- `cohort` (TOUR/CONTROL/UNASSIGNED/NULL) вместо вычисления группы на лету — отчёт не смешивает периоды без
  эксперимента; ключ `onboarding_v1` (формат ключей ExperimentSettings); вариант не уходит клиенту (`client=false`).
- Eligibility через `mnema_new_account` (< 48 ч) + `eligible-from` + нет колод — «первого появления» Learning не хранит.
- `CONVERSION`-счётчика нет: активация и возврат считаются в отчёте по `study_session` (вех ещё нет, S3).
- Короткий тур для «Пользовался» = основные шаги ветки (для отдельного тура не было текстов).
- Подпись шага занятия цитирует кнопку «Учить» (утверждено «Начать занятие»); у «ИИ в Mnema» нет «Назад».
- Ветка «На примере» скрыта до OB-E2 (`SAMPLE_TOUR = null`, TODO OB-203).

**Новые строки на утверждение владельцу:** «С какой колоды начнём?», «Новая колода» (перепрохождение «Своей колоды»
при существующих колодах); служебные подписи из разметки прототипа «Пояснения к шагу», «Пройдено вопросов», «{n} из 4».

## Независимое review и исправления

- Backend (Opus high): 0 blockers; исправлено: смешение когорт в отчёте (→ `UNASSIGNED`), вариант в `GET /api/plans`
  и клиентские события (→ server-only), REPLAY без проверки шага, версия строки REPLAY, EXPOSURE до коммита, 400 до 428,
  выключенный GET без транзакции; записан рост `command_receipt` (follow-up).
- Frontend (Opus high, два прохода): 3 + 1 blockers и ~20 замечаний — гонка навигации страниц и тура, пауза уводила
  со страницы, подсказка «Не нашли?» под `inert`, промо поверх приветствия, отказ команды как успех, неотличимый
  фейк-стор, Escape, объявления, эпохи стора; второй проход — пауза «съедала» переход страницы. Все исправлены со specs.
- Real browser (harness): B0 — `auth.interceptor` не слал bearer на `/api/onboarding` (тур не открывался вовсе;
  unit-тесты этого не видят); фокус после смены маршрута, 412 при пропуске интересов (причина: ответ о цели на сервере
  поднимает версию профиля интересов — опрос перечитывает интересы), второе устройство без колоды, выход из выбора ветки.

## Результаты gate (точный commit 748043a2)

Все шаги — через `workstation-dev` `dev.py run` под общей блокировкой `heavy`, JDK 25.0.4.1, Node 24.21.0, Colima
`unix:///Users/yuzuru/.colima/default/docker.sock`; каждый receipt `ok`, `source_changed: false`, HEAD 748043a2
(в дереве только чужая правка прототипа README).

| Команда | Итог |
|---|---|
| `(cd backend && ./gradlew clean quality)` | OK за 14 мин: 3439 JUnit-тестов (identity + learning), 0 падений, 0 ошибок, 25 пропущено (как до S1; онбординг и эксперименты без пропусков); покрытие — пороги `coverage-baseline.json` |
| `npm ci` · `npm run lint` · `npm run test` · `npm run build` · `node scripts/verify-no-styleguide.mjs` | OK: 307 файлов, 4334 теста; покрытие statements 92.2 %, branches 87.66 %, functions 90.98 %, lines 95.47 %; build — 9 предупреждений «is not ESM» из `mermaid` (как в main) |
| `python3 scripts/verify_docs.py` | OK (290 файлов на коде; повторно на коммите handoff) |
| `python3 -m unittest discover -s scripts/browser-identity -p 'test_*.py'` | OK, 47 тестов |
| `run.py --dist frontend/dist/mnema-frontend --onboarding` (harness, Chrome 155 headless) | OK: 16 сценариев `onboarding_*` passed, 0 runtime errors, 0 findings, ≈ 49 с |

Flaky (не на этом прогоне): `exercise-authoring-page.component.spec.ts` (ORDER/CATEGORIZE) один раз падал под нагрузкой
у саб-агента и проходил отдельно и при повторе; не исследован.

## A11y evidence

Harness `--onboarding` (Chrome headless, реальные Identity/Learning/PostgreSQL, одноразовые аккаунты), каталог
`/var/folders/…/T/mnema-browser-evidence-jc1os0ri (локально, вне git)`; копия ключевых файлов — [результаты](onboarding-results.json), [a11y](onboarding-a11y.json) и 8 снимков в этой папке:
клавиатура (опрос только клавишами, Tab/Escape/Enter), фокус на заголовке приветствия и карточки шага (в т. ч. после смены
маршрута), дерево доступности (dialog + имя, кнопки, progressbar с текстом), `inert` на шаге с действием и снятие после тура,
1440/390/320 без горизонтальной прокрутки, нижний лист, reduced motion (0 идущих анимаций), forced-colors (рамка выреза
outline 3 px). **VoiceOver/NVDA smoke не выполнялся** — дерево доступности его не заменяет.

## Миграции и rollback

V72 `onboarding_journey` (+ индекс `command_receipt_onboarding_actor`), additive, неприменённая в production.
Rollback поведения — `learning.onboarding.enabled=false` (вид выключен, команды 409, прежний вопрос о цели);
схемы — таблица остаётся. Production-флаги не менялись (по умолчанию off).

## Что не проверено

- VoiceOver/NVDA, реальное касание, Safari/Firefox, 200 % масштаб.
- Ветка «Сообщество» в браузере — до открытия занятия (само занятие не доиграно); с ИИ — финал «ИИ в Mnema»; holdout в
  браузере (эксперимент выключен в harness); FAMILIAR/CONFIDENT в браузере (только specs).
- Retained local stack `mnema-local-v2` не перезапускался на ветке (runtime owner — main checkout); e2e — в одноразовом harness.
- Нет real-browser сценария геометрии выреза при resize/scroll (чистые функции + ручная проверка OB-102).
- На 390 px кнопка «Создать колоду» под нижним листом достигается прокруткой (отступ высотой листа, утверждённое уточнение) — harness не утверждает это отдельно.
- Незакрытые AC: OB-102 «скринридер» и «resize/scroll», OB-104 «на local stack», эпик «VoiceOver/NVDA smoke» — с пояснением в task-файлах.
- Flaky: `exercise-authoring-page.component.spec.ts` (ORDER/CATEGORIZE) один раз упал под нагрузкой, отдельно зелёный.

## Статус

OB-101…OB-105 — local complete, delivery pending. Ветка не запушена; доска не менялась (#520–#525 в Backlog).

## Следующий шаг

Push `feat/517-onboarding` и перевод PR #554 из draft — по поручению владельца (включает S0 и S1); затем S2 (OB-E2)
от этой ветки. Production-включение — только после даты вступления политики 1.7 (OB-002) и решения о новых строках.
