---
artifact:
  id: onboarding-architecture
  type: architecture
  title: "Onboarding architecture: journey state, tour engine, milestones, sample deck, rhythm"
  status: accepted
  created_at: "2026-10-14"
  owners: ["project-owner"]
  base_revision: "f4f5e636 (community epics 3–4, now in main)"
---

# Архитектура онбординга

Поведение — [proposal](proposal.md); выбор и отвергнутые варианты — [RFC](rfc.md) и ADR в
[adr/](adr/). Статус: accepted (решения владельца 2026-10-14); OB-E1 и OB-E2 реализованы и влиты за выключенными флагами (§12–13), OB-E3 — §14. Числа нагрузки ниже — оценки по модели
H из [community architecture §12](../../architecture/community-decks.md), не измерения.

## 1. Требования

**Функциональные.** Однократное предложение «Начала пути» новому аккаунту; опрос до 4 вопросов;
spotlight-тур с ветками, паузой, продолжением на другом устройстве, завершением, отменой
завершения и перепрохождением; учебная колода по запросу; вехи «Пути», вычисляемые из реальных
действий, включая прошлую историю; тосты вех; недельный ритм; опциональная награда.

**Нефункциональные.** Ни одна часть онбординга не может сломать или замедлить основное действие
(создание колоды, занятие, копию): writers вех — один индексированный upsert в транзакции события,
ошибки чтения онбординга деградируют до «тура нет». Идемпотентные команды с `commandId` и
`rowVersion` как в остальных контрактах. Никаких текстов пользователя в новых таблицах.
A11y: WCAG 2.2 AA для тура. Без новых зависимостей, `@angular/cdk` не добавляется ([ADR-ONB-001](adr/adr-onb-001-tour-engine.md)).

## 2. Компоненты и владение

```mermaid
flowchart LR
  subgraph Browser[Angular SPA]
    Shell[AppShell] --> Coordinator[AttentionCoordinator]
    Coordinator --> Tour[TourEngine + mnTourAnchor]
    Coordinator --> Promo[PromoPopupService]
    Coordinator --> Goal[Welcome / survey]
    Tour --> JourneyStore
    PathPage[Path page] --> JourneyStore
    Toasts[ToastService / notification center]
  end
  subgraph Learning[Learning service]
    OnbApi[onboarding API] --> Journey[(onboarding_journey)]
    OnbApi --> Milestones[(account_milestone)]
    Writers[MilestoneRecorder - MANDATORY tx] --> Milestones
    Writers --> Notif[NotificationPublisher]
    Domain[Deck / Fork / Authoring / Study / Capture / Workshop / Speech] --> Writers
    Sample[SampleDeckService] --> Fork[ForkService.copy]
    Rhythm[RhythmQuery] --> Study[(study_session)]
    Reward[RewardIssuer - E4] --> PromoLedger[(promo codes)]
  end
  JourneyStore -->|GET/PUT /api/onboarding| OnbApi
  Goal -->|PUT /api/learning-profile, /api/community/interests| Learning
  Notif -->|existing poll| Toasts
```

| Компонент | Владелец | Ответственность |
|---|---|---|
| `onboarding` package (Learning) | Learning | Journey state, API, eligibility, вехи, ритм, учебная колода, награда |
| `MilestoneRecorder` | Learning, вызывается доменными writers | Upsert вехи + уведомление в той же транзакции |
| `TourEngine` (frontend, `core/onboarding`) | Frontend | Определения туров, шаги, spotlight, пауза, синхронизация с сервером |
| `AttentionCoordinator` (frontend) | Frontend | Одна «громкая» поверхность за раз: тур, промо-окно, опрос, модальные окна, QuietZone |
| Identity | Identity | Только существующие claims (`mnema_new_account`, `mnema_trusted`); новых данных не хранит |

Почему Learning, а не Identity и не новый сервис: все факты вех, профиль цели, интересы,
уведомления, промокоды и A/B уже живут в Learning; отдельный сервис добавил бы межсервисную
консистентность ради нескольких таблиц без отдельной нагрузки.

## 3. Модель данных (Learning, additive)

```sql
-- Номер миграции — по inventory свежего main (после эпиков 3–4 последняя V71).
CREATE TABLE onboarding_journey (
  account_id      uuid PRIMARY KEY,
  tour_version    smallint    NOT NULL,
  state           text        NOT NULL CHECK (state IN ('OFFERED','IN_PROGRESS','PAUSED','SKIPPED','COMPLETED')),
  branch          text        NULL     CHECK (branch IN ('SAMPLE','COMMUNITY','OWN')),
  step_key        text        NULL     CHECK (length(step_key) <= 64),
  experience      text        NULL     CHECK (experience IN ('NEW','FAMILIAR','CONFIDENT')),
  weekly_target   smallint    NULL     CHECK (weekly_target BETWEEN 1 AND 7),
  offered_at      timestamptz NOT NULL,
  finished_at     timestamptz NULL,
  replay_count    smallint    NOT NULL DEFAULT 0,
  weeks_met       smallint    NOT NULL DEFAULT 0,  -- §7, OB-401
  last_met_week   date        NULL,                -- понедельник последней засчитанной недели
  row_version     bigint      NOT NULL DEFAULT 0,
  updated_at      timestamptz NOT NULL
);

CREATE TABLE account_milestone (
  account_id    uuid        NOT NULL,
  milestone_key text        NOT NULL,            -- из закрытого реестра, CHECK по списку версии
  progress      smallint    NOT NULL DEFAULT 1,   -- для счётных вех, насыщается на target
  achieved_at   timestamptz NULL,                 -- NULL пока progress < target
  first_at      timestamptz NOT NULL,
  source        text        NOT NULL CHECK (source IN ('LIVE','BACKFILL')),
  PRIMARY KEY (account_id, milestone_key)
);

ALTER TABLE deck ADD COLUMN purpose text NOT NULL DEFAULT 'USER'
  CHECK (purpose IN ('USER','SAMPLE_TEMPLATE','SAMPLE'));
CREATE UNIQUE INDEX deck_one_live_sample ON deck (owner_id) WHERE purpose = 'SAMPLE' AND deleted_at IS NULL;
```

- Отсутствие строки `onboarding_journey` = «не предлагалось». Строка создаётся при первом
  `GET /api/onboarding`, если аккаунт eligible.
- `weekly_target` и `experience` лежат в journey, а не в `learning_profile`, потому что это настройки
  онбординга/ритма; если владелец захочет использовать опыт в рекомендациях — перенос в профиль
  требует правки политики ПДн (как цель).
- Имя колонок `deck.owner_id`/`deleted_at` сверить с актуальной схемой; инвариант «одна живая
  учебная колода на аккаунт» обязателен в любой форме.
- Account purge (#409): обе новые таблицы удаляются по `account_id`; TODO в стиле существующих
  `deck_engagement_account`.
- Ритм не хранится: вычисляется из `study_session` (§7).

## 4. API (Learning, private, RFC 9457, strong ETag)

| Метод | Назначение | Семантика |
|---|---|---|
| `GET /api/onboarding` | Journey + вехи + ритм | Создаёт `OFFERED` строку для eligible аккаунта (`INSERT … ON CONFLICT DO NOTHING`); ETag по `row_version` + max(milestone first_at/achieved_at); 304 |
| `PUT /api/onboarding/journey` | Команда тура | `{commandId, rowVersion, action: START|STEP|PAUSE|RESUME|SKIP|COMPLETE|UNDO_FINISH|REPLAY, branch?, stepKey?}`; 412 `VERSION_CONFLICT`; повтор `commandId` возвращает прежний ответ |
| `PUT /api/onboarding/preferences` | Опыт и цель ритма | `{commandId, rowVersion, experience?, weeklyTarget?}` |
| `POST /api/onboarding/sample-deck` | Учебная колода | `{commandId}` → `201 {deckId}` или `200` существующая живая; 409 если шаблон не настроен |

Опрос использует существующие `PUT /api/learning-profile` и `PUT /api/community/interests`.
Eligibility: аккаунт впервые увиден Learning после `learning.onboarding.eligible-from` (конфиг даты
релиза) **и** нет своих колод; иначе `eligible=false`, но вехи и ритм всё равно отдаются.
Выключатели: `learning.onboarding.enabled` (весь API → `eligible=false`, вехи продолжают писаться),
`learning.onboarding.sample-deck.enabled`, `learning.onboarding.reward.enabled`.

Ответ `GET` (сокращённо):

```json
{
  "journey": {"state": "PAUSED", "tourVersion": 1, "branch": "SAMPLE", "stepKey": "sample.exercises",
              "experience": "NEW", "weeklyTarget": 3, "eligible": true, "rowVersion": "7"},
  "milestones": [{"key": "FIRST_DECK", "state": "DONE", "progress": 1, "target": 1, "achievedAt": "…"},
                 {"key": "OWN_EXERCISES", "state": "PARTIAL", "progress": 2, "target": 3}],
  "path": {"done": 4, "total": 13, "hidden": ["COMMUNITY_COPY"]},
  "rhythm": {"weekStart": "2026-10-12", "target": 3, "days": ["2026-10-12", "2026-10-14"], "weeksMet": 1},
  "sampleDeck": {"deckId": "…", "updateAvailable": false}
}
```

## 5. Вехи: запись и backfill ([ADR-ONB-002](adr/adr-onb-002-milestones.md))

**Реестр** — enum в коде + JSON-фикстура в `contracts/onboarding/` (key, target, chapter, visibility
condition, route key). Клиент рисует тексты сам по key, как уведомления.

**Запись.** `MilestoneRecorder.record(accountId, key, increment)` — `@Transactional(MANDATORY)`,
одно выражение:

```sql
INSERT INTO account_milestone (account_id, milestone_key, progress, achieved_at, first_at, source)
VALUES (:a, :k, 1, CASE WHEN :target = 1 THEN now() END, now(), 'LIVE')
ON CONFLICT (account_id, milestone_key) DO UPDATE
  SET progress = LEAST(account_milestone.progress + 1, :target),
      achieved_at = COALESCE(account_milestone.achieved_at,
                             CASE WHEN account_milestone.progress + 1 >= :target THEN now() END)
  WHERE account_milestone.achieved_at IS NULL
RETURNING (xmax = 0) AS inserted, achieved_at;
```

Если в этом выражении `achieved_at` стал не NULL — в той же транзакции
`NotificationPublisher.publish(kind=PATH_MILESTONE, params={key, done, total}, route=PATH,
dedupeKey="path:"+key)`. Повторы и параллельные события безопасны: PK + условие `WHERE`.

| Веха | Точка вызова (сверить по actual source) |
|---|---|
| FIRST_DECK | `DeckService.create`, `ForkService.copy`, `SampleDeckService` |
| FIRST_SESSION, TWO_DAYS, RHYTHM_WEEK | там же, где `DeckEngagement` STUDY: `StudySessionRepository.complete`, `AttemptRepository.completeSessionIfTerminal` (SCHEDULED или PRACTICE, не REPLAY, только изменившийся row) |
| OWN_MATERIAL, MEDIA_IN_MATERIAL | публикация ревизии материала в колоде `purpose=USER` |
| CAPTURE_NOTE | создание заметки «На потом» |
| OWN_EXERCISES (target 3) | публикация нового упражнения в колоде `purpose=USER` |
| AI_EXPLANATION | терминальная попытка с effective evaluator `ai-semantic` |
| VOICE_ANSWER | успешная транскрипция для ответа/поля |
| WORKSHOP_APPROVED | approve артефакта Мастерской |
| COMMUNITY_COPY | `ForkService.copy` из не-шаблона |
| GOAL_TOLD | `PUT /api/learning-profile` (ответ или пропуск) |

Фиксация в транзакции доменного действия — тот же idiom, что `DeckEngagement` и уведомления: нет
outbox-лага, нет второй очереди. Цена — один PK-upsert на событие; при сбое вставки откатывается
и действие, поэтому выражение не должно иметь внешних зависимостей и не трогает горячие строки других аккаунтов.

**Backfill** (отдельный bounded job по существующему фреймворку фоновых задач, не в миграции, если
объём не тривиален): пачки по `account_id`, каждая веха — один `INSERT … SELECT … ON CONFLICT DO NOTHING`
с `source='BACKFILL'`, без уведомлений. Вехи без истории (голос, ai-semantic после retention
presentations) честно остаются невыполненными, если факт не восстанавливается; не выдумывать.

## 6. Учебная колода ([ADR-ONB-003](adr/adr-onb-003-sample-deck.md))

- **Шаблон:** обычная колода `purpose=SAMPLE_TEMPLATE` владельца-редакции, опубликованная ревизия,
  уровень доступа не PUBLIC (в каталог не попадает), id — в конфиге `learning.onboarding.sample-deck.template-id`.
  Медиа шаблона — published media CD-6, видимые копиям.
- **Создание:** `SampleDeckService.create(account, commandId)` в одной транзакции: проверка
  существующей живой `SAMPLE` (частичный unique index), `ForkService.copy` системным путём (без
  проверки доступа гостя, источник только из конфига), `deck.purpose='SAMPLE'`,
  `MilestoneRecorder(FIRST_DECK)`. Материализация голов — существующая фоновая задача
  `deck.copy.materialize`; тур ждёт статуса `ready` хаба (уже есть `failed`/повтор в UI копии).
- **Ограничения `purpose=SAMPLE`:** `PublicationService`/access commands → 409
  `SAMPLE_DECK_NOT_SHAREABLE`; `DeckEngagement` и catalog events не пишутся (условие в одном месте —
  `ForkService`/`DeckEngagement` по `purpose`); профиль интересов пропускает тему шаблона;
  копия учебной колоды другим пользователем невозможна (нет публикации).
- **Обновления шаблона:** редакция публикует новую ревизию шаблона → копии получают «Есть обновление»
  стандартным CD-5. Никаких автопринятий.
- **Стоимость:** O(1) на создание + материализация указателей (≈ 0,23 КБ × N единиц, для 12 единиц
  ≈ 3 КБ на аккаунт; 200k аккаунтов H ≈ 0,6 ГБ при 100 % выборе — верхняя оценка).

## 7. Ритм ([ADR-ONB-004](adr/adr-onb-004-rhythm.md))

Вычисление на чтении, без новой таблицы:

```sql
SELECT DISTINCT local_study_date FROM study_session
WHERE account_id = :a AND mode IN ('SCHEDULED','PRACTICE') AND status = 'COMPLETE'
  AND local_study_date >= :weekStart AND local_study_date < :weekStart + 7;
```

Неделя — ISO, с понедельника, в локальной дате, которую Study уже пишет. Нужен индекс
`(account_id, local_study_date)` с частичным условием по `status/mode` — проверить существующие
индексы перед добавлением. `weeksMet` (число выполненных недель) — инкремент в `onboarding_journey`
при достижении цели недели в транзакции завершения занятия (SCHEDULED или PRACTICE; REPLAY не трогает ритм) (idempotent по ключу недели,
`last_met_week date`), чтобы не сканировать историю.

## 8. Frontend

- `core/onboarding/`: `JourneyStore` (signals, один GET на вход, ETag, обновление после
  уведомления `PATH_MILESTONE` и после команд), `TourEngine`, `tour-definitions.ts` (версия, ветки,
  шаги: `{key, route?, anchor, title, body[], gate: 'next'|'action:<event>', placement}`),
  директива `mnTourAnchor="community.search"` — элементы регистрируются по стабильным ключам,
  а не CSS-селекторам.
- **Spotlight** ([ADR-ONB-001](adr/adr-onb-001-tour-engine.md)): без новых зависимостей (`@angular/cdk`
  в проекте нет) — карточка на `<dialog>`, вуаль — SVG-маска во весь viewport (forced-colors fallback —
  рамка `Highlight`); позиция цели — `ResizeObserver` + scroll в `requestAnimationFrame`; карточка
  позиционируется в JS с fallback-сторонами, на узком экране — нижний лист.
- **Фокус и ARIA:** карточка — `role="dialog"` (немодальный, `aria-labelledby` заголовок шага),
  фокус переносится на заголовок карточки при смене шага; цель остаётся в tab-порядке; при
  action-gated шаге остальная страница `inert`, кроме цели и карточки; Escape → PAUSE с возвратом
  фокуса; live-region (`aria-live=polite`) объявляет «Шаг 2 из 4»; переход по маршруту — фокус на карточку после
  появления anchor (timeout 4 с → текстовый fallback шага).
- **AttentionCoordinator:** очередь «громких» поверхностей с приоритетом: системная ошибка >
  занятие (QuietZone) > тур > опрос > промо-окно > тосты вех. Промо-окно не запрашивается в день
  тура. Существующий `GoalOnboardingComponent` перестаёт показываться новым eligible аккаунтам
  (вопрос живёт в «Начале пути»), остаётся для старых без ответа.
- **Визуал:** новые токены `--mn-night` (на основе `--mn-ink`), `--mn-star-lavender`,
  `--mn-star-paper`, `--mn-scrim` и компоненты `app-tour-card`, `app-night-sky`, `app-path-milestone`
  сначала в `/styleguide` (правило «новый элемент — сначала в стайлгайд»), контраст проверяет
  существующий `color-contrast.spec`.
- **Завершение удержанием (Q03):** кнопка «Завершить знакомство» — вариант общего hold-компонента:
  `app-hold-to-delete-button` обобщается (или выделяется общий `HoldGesture`) с параметрами длительности
  (2 с) и знаком вместо подписи во взведённом состоянии; поведение клавиатуры и pointer — как у удаления.
- **Деградация:** ошибка `GET /api/onboarding` → тура нет, «Путь» показывает «Не удалось загрузить»
  с повтором; ни одна страница не ждёт онбординг для своей отрисовки.

## 9. Отказы, согласованность, безопасность

| Ситуация | Поведение |
|---|---|
| Две вкладки двигают тур | `rowVersion` → 412 у второй; вкладка перечитывает и продолжает с серверного шага |
| Потеря ответа на `PUT` | Повтор с тем же `commandId` |
| Колода тура удалена / функция выключена | Шаг пропускается с текстом; тур не падает |
| Материализация учебной колоды долго | Карточка «Готовим колоду…», повтор существующим UI копии; тур ждёт |
| Шаблон не настроен | Вариант «На примере» скрыт (`sampleDeck.available=false`) |
| Уведомление вехи потеряно | Не потеряно: та же транзакция; клиент всё равно видит веху при следующем GET |
| Злоупотребление наградой | Только verified account, одна на аккаунт, `UNIQUE(account_id)`, kill switch, аудит выдачи |

Новые данные: состояние тура, опыт, цель ритма, факты вех — персональные данные, связанные с
аккаунтом (ответы опроса тем более). До включения — раздел политики ПДн (цель «персонализация и
обучение пользованию сервисом»), срок хранения = жизнь аккаунта, удаление с аккаунтом, видимость
и сброс в профиле. Тексты пользователя, названия колод и поисковые запросы в новые таблицы не пишутся.
Логи — только `accountId` хешем по существующим правилам, без params.

## 10. Нагрузка

Ноль пользователей: таблицы пусты, writers — один PK-upsert без конфликтов. H (200k аккаунтов):
`account_milestone` ≤ 13 строк × 200k = 2,6 млн строк ≈ 0,25 ГБ с индексом; `onboarding_journey`
200k узких строк ≈ 30 МБ; ритм — индексный диапазон ≤ 7 дней. Горячий путь занятия получает +1
upsert только на первых событиях (после `achieved_at` условие `WHERE` не пишет — но строка
читается; при желании кешировать «все вехи достигнуты» в journey). Backfill: батчи 1–5k
аккаунтов, ограничение по времени slice, как `CatalogRank`.

## 11. Наблюдаемость и эксперимент

Метрики: `mnema_onboarding_journey_total{action}`, `mnema_path_milestone_total{key,source}`,
латентность GET; логи без персональных данных. Holdout: ключ эксперимента `onboarding-v1`
(control = без автопредложения тура, «Путь» доступен всем) через существующий ExperimentService;
`EXPOSURE` при решении показать «Начало пути», `CONVERSION` — активация (первое SCHEDULED-занятие
за 7 дней) считается на сервере по `account_milestone`. Отчёт — агрегаты в кабинете владельца.

## 12. Уточнено реализацией (S1, OB-E1, 2026-10-16)

Канон поведения не менялся; ниже — что реализация S1 зафиксировала точнее, чем §3–§4, §8 и §11. Контракт —
[`contracts/onboarding`](../../../contracts/onboarding/README.md).

- **Схема (V72).** К DDL §3 добавлена `cohort` (`TOUR` / `CONTROL` / `UNASSIGNED` / NULL): группа holdout
  замораживается при создании строки по eligibility; `UNASSIGNED` — эксперимент выключен или нет секрета (тур есть,
  в отчёт не входит); NULL — строка создана перепрохождением (`REPLAY`) вне когорты. CHECK связывает `finished_at`
  с состояниями `SKIPPED`/`COMPLETED`. `account_milestone` и `deck.purpose` — не в V72 (S2/S3).
- **Состояние на проводе.** Нет строки = `NOT_OFFERED` (`rowVersion "0"`). `eligible` — строка создана по eligibility,
  `autoOffer` — клиент сам открывает «Начало пути» (`OFFERED`, не `CONTROL`). При выключенном флаге `journey: null`,
  команды 409 `ONBOARDING_UNAVAILABLE`, БД не читается. Переходы — таблица `journey.json.transitions`; `UNDO_FINISH`
  возвращает `SKIPPED → OFFERED`, `COMPLETED → PAUSED`; `REPLAY` из `NOT_OFFERED` создаёт строку с версией 1.
- **Eligibility.** «Впервые увиден после релиза» реализовано как: время БД ≥ `eligible-from`, Identity помечает
  аккаунт новым (`mnema_new_account`, младше 48 ч; нет claim — не новый) и нет живых колод. Отдельной таблицы
  «первого появления» нет.
- **Шаги.** Закрытый реестр `stepKey` в контракте (20 ключей версии 1); backend и frontend сверяют его тестами;
  метрика `mnema_onboarding_journey_total{action,step}` берёт метки только из реестра.
- **Эксперимент.** Ключ `onboarding_v1` (подчёркивание — формат ключей ExperimentSettings), `control:15,tour:85`,
  `client=false`: вариант не уходит в `GET /api/plans`, клиентские события не считаются; EXPOSURE считает сервер после
  коммита создания строки. `CONVERSION`-счётчика нет: активация и возврат считаются в отчёте
  `GET /api/admin/onboarding/experiment` (окно консоли, 1–90 дней) по `study_session`, а не по `account_milestone`
  (появится в S3). Кабинет — `/manage/onboarding`, предупреждение при группе < 100.
- **Квитанции команд.** Каждая команда тура хранит вид (~0,5 КБ) в `command_receipt`: 10–20 КБ на аккаунт,
  2–4 ГБ при 200k аккаунтов (§10 это не учитывал). Retention sweep, как у событий каталога, — follow-up.
- **Frontend.** Вуаль — SVG-путь с even-odd вырезом (не `<mask>`): вырез не перехватывает нажатия, цель кликабельна
  без подъёма z-index. Анкоры — `mnTourAnchor` с ключами из `tour-anchor-keys.ts`; удержание завершения —
  параметры общего `app-hold-to-delete-button` (`holdMs`, `armedGlyph`, `armedName`, `tone`, `steppedFill`), без копии.

## 13. Уточнено реализацией (S2, OB-E2, 2026-10-17)

- **Пометка шаблона.** Команды «сделать шаблоном» нет: источник истины — `learning.onboarding.sample-deck.template-id`,
  Learning при старте (каждая роль, идемпотентно) ставит этой колоде `purpose=SAMPLE_TEMPLATE`; колода, созданная после
  старта, помечается при следующем перезапуске. `purpose` неизменяем, кроме этого перехода (`deck_head_guard`, V73).
- **Шаблон публикуется на `LINK`, не приватно.** Чтение источника копии (`ForkReadModel`) идёт по коду публикации, поэтому
  только опубликованный по ссылке шаблон доставляет копиям «Есть обновление» (CD-5). `LINK` не попадает в каталог и поиск;
  после пометки владелец шаблона может публиковать новые ревизии только на `LINK`.
- **Создание копии** — `ForkService.copyTemplate` (та же запись, что у обычной копии, с лимитами аккаунта, без проверки
  доступа гостя и без `FORK_OWN_DECK`), под advisory-lock аккаунта; частичный unique index — страховка. Повтор `commandId`
  возвращает тот же код (201/200) с `Idempotency-Replayed`.
- **Запреты** — `DeckPurposeGuard` после проверки владения (чужой по-прежнему получает непрозрачный 404): публикация и
  уровень доступа, выдача доступа, приглашения, одобрение запросов, копия по коду. Инвентарный тест перечисляет все
  изменяющие маршруты `library`/`fork` и падает на неклассифицированном.
- **Исключения** — одно условие `deck.purpose = 'USER'` на исходной колоде в `DeckEngagement`, `CatalogOutbox`,
  `CatalogEvents`, `InterestRepository.copyTopics`. Учебная колода не лишает аккаунт права на тур (`hasLiveDeck`).
- **Бесплатные проверки** — 0-кредитный `DEBIT` с операцией `ONBOARDING_SAMPLE_CHECK` (без бакетов) в той же транзакции,
  что и платный учёт; голосовой ответ с ИИ-оценкой расходует две бесплатные (распознавание не связано с попыткой).
  `GET /api/usage` не меняется: клиент читает `sampleDeck.freeChecksLeft`; кабинет показывает операцию в отчёте.
- **Редакция на local stack** — отдельный локальный аккаунт (учётные данные в state dir стека, 0600); в production
  аккаунт редакции и установка — отдельное поручение.

## 14. Уточнено реализацией (S3, OB-E3, 2026-10-18)

Контракт — [`contracts/onboarding/milestones.json`](../../../contracts/onboarding/milestones.json) (реестр) и
[`README`](../../../contracts/onboarding/README.md) («Путь Mnema»).

- **Схема (V74).** `account_milestone` как в §3, `progress` 1–3 (`CHECK` по реестру версии 1). В той же миграции CHECK
  `notification.route` (V24) заменён на список с `PATH` — без этого `PATH_MILESTONE` нарушал бы ограничение.
- **Recorder.** `MilestoneRecorder.reach(level)` (стадии и даты: `progress = max`), `increment` (счётчик `OWN_EXERCISES`),
  `reachInUserDeck` (условие `purpose='USER'` в том же выражении для writer'а, у которого есть только id колоды) и
  `sessionCompleted(mode, localStudyDate)`. Веха с целью 1 — `INSERT … ON CONFLICT DO NOTHING` (без блокировки существующей строки); стадии и счётчики — выражение §5, уточнённое: `WHERE achieved_at IS NULL AND progress < новый`, так что
  повтор ничего не пишет; «достигнуто этим выражением» = вернулась строка с `achieved_at`.
- **Уведомление в `beforeCommit`.** Recorder копит достигнутые вехи транзакции и публикует `PATH_MILESTONE` одной
  синхронизацией перед commit — последней записью транзакции, как требует `NotificationPublisher` (lock строки курсора), независимо
  от порядка writer'ов. Только при `learning.onboarding.enabled`; вехи, скрытые конфигурацией, записываются, но не объявляются.
  Вехи, достигнутые при выключенном флаге, позже не объявляются.
- **Стадии и даты.** `CAPTURE_NOTE` — цель 2: заметка сохранена (1), разобрана — превращена в материал или в архиве (2).
  `TWO_DAYS` — цель 2: число разных `local_study_date`; второй день — min/max дат по новому частичному индексу `study_session_study_dates`
  (V74, он же для ритма §7), только пока веха не достигнута.
  `RHYTHM_WEEK` в реестре v1 без writer'а и скрыта до OB-401.
- **Видимость** — операторские флаги (`learning.features.ai-assessment|speech-to-text|ai-generation.enabled`), не circuit breaker
  и не бюджет; `COMMUNITY_COPY` — копия из каталога возможна: `learning.community.catalog.enabled`, `public-routes` и `forks` включены и в каталоге хотя бы одна колода (кешированный счётчик).
- **Горячий путь** — [evidence](../evidence/onboarding/ob-e3/hot-path.md): после достижения обеих вех занятия — одно PK-чтение
  на завершение занятия (≈ 0,26 мс); ответы не платят ничего, кроме `AI_EXPLANATION` для оценки `ai-semantic`.
- **Backfill (OB-302)** — задача `onboarding.backfill` (`learning.onboarding.backfill.enabled`, по умолчанию выключено; пачка,
  бюджет slice и пауза — `learning.onboarding.backfill.*`), ставится раз на версию реестра (`onboarding.backfill:v1`). Курсор
  `{key, after}` по вехам и владельцам; граница пачки — loose index scan по индексу владельцев источника; слияние — одно
  `INSERT … SELECT … ON CONFLICT DO UPDATE` с `GREATEST(progress)`, `LEAST(first_at)`, сохранением `achieved_at` и `source` строки
  (§5 предполагал `DO NOTHING`, но тогда LIVE-строка с меньшим прогрессом не догнала бы историю); строки, которые история не меняет, в
  вставку не попадают (иначе `DO UPDATE` держал бы их блокировки), одна пачка — одна транзакция slice; пройденная версия реестра
  записывается в `onboarding_backfill_run` (V74), поэтому после очистки строки задачи она не запускается снова. Advisory try-lock, метрики
  `mnema_onboarding_backfill_seconds`, `…_rows_total{key}`, `mnema_path_milestone_total{source="BACKFILL"}`; уведомлений нет.
  [Evidence](../evidence/onboarding/ob-e3/backfill.md).
- **Inventory.** Тест классифицирует все изменяющие маршруты (маршрут → ожидаемые вехи) и падает на новом неклассифицированном;
  второй тест сверяет `writer` фикстуры с упоминаниями `MilestoneKey` в коде.
- **Frontend.** `AttentionCoordinator` получил поверхность `hint` между `first-entry` и `promo` (подсказка ждёт занятие, тур и
  «Начало пути»; промо и тосты вех ждут подсказку). «Путь» — `/profile/path` и блок в профиле; виден, пока `enabled`.
  Скрытие «Пути» и заметка «Новое» — настройки клиента (localStorage), не сервера. Тосты вех — сервис `PathToasts`: держит тост,
  пока активна QuietZone (независимо от настройки «Во время занятия», `BADGE_ONLY` — не показывает), не чаще одного за 30 с, сводный
  для нескольких. Неизвестный клиенту `route` уведомления читается как `NONE`.
- **Квесты** — определения того же движка с `quest: true`: шаги `quest.*` вне серверного реестра, `PUT /api/onboarding/journey`
  не отправляется; добавлены шаг-экран без якоря и событие завершения `completes`. Бюджет перед стартом клиент считает по
  `GET /api/usage` (своего «нет бюджета» у клиента не было).

## 15. Уточнено реализацией (S4, OB-E4, 2026-10-19)

Контракт — [`contracts/onboarding`](../../../contracts/onboarding/README.md) «Weekly rhythm», «Path reward»; [`promo.json`](../../../contracts/usage/promo.json); backend —
[guide](../../../backend/services/learning/guide.md) «Onboarding weekly rhythm», «Onboarding path reward».

- **Схема (V75).** §3 предполагал, что строки journey нет у аккаунта без тура, но ритм (цель, `weeks_met`, `last_met_week`) живёт в ней — значит, старым
  аккаунтам негде хранить цель и недели. Решение: состояние `NOT_OFFERED` хранится (без когорты и `offered_at`; CHECK связывают их с состоянием, `weeks_met` — с
  `last_met_week`, это понедельник). Строку создают первая выполненная неделя или `PUT /preferences` (теперь работает в любом состоянии); на проводе она читается как
  «нет строки». Первое чтение eligible-аккаунта превращает её в `OFFERED` (`ON CONFLICT DO UPDATE … WHERE state='NOT_OFFERED'`); `REPLAY` идёт обычным обновлением.
  Отдельная таблица ритма отвергнута: два места цели и версии вместо одного.
- **Ритм (§7).** Как в §7, без новой таблицы и индекса: `WeeklyRhythm` в транзакции завершения (рядом с `MilestoneRecorder.sessionCompleted`), даты — по
  `study_session_study_dates` (V74; EXPLAIN в тесте). Уточнено: advisory-lock аккаунта на подсчёт, пока неделя не засчитана, — две одновременные завершения,
  вместе выполняющие цель, засчитывают её ровно один раз, а не ни разу; `last_met_week` — ключ идемпотентности; `row_version` ритм не трогает (иначе следующая
  команда тура получала бы 412). Порядок блокировок: advisory-lock до записи строки и до первой публикации уведомления в транзакции (курсор); `PUT /preferences`
  берёт тот же lock до обновления строки, проверка ответа ИИ — до оплаты (учёт может опубликовать `USAGE_*` посреди транзакции). Стоимость завершения: 0 выражений при выключенном ритме и для REPLAY, 1 PK-чтение после засчитанной недели, 4–6 до.
  `today` — часы БД в поясе `zoneinfo` токена (UTC, как Study).
- **Награда (ADR-ONB-005).** Отклонение от «в той же транзакции последней вехи»: выдача — в `GET /api/onboarding` (причины — proposal S4), до транзакции
  чтения и в собственной транзакции (вложенная держала бы два соединения на чтение; ошибка не ломает чтение; без кампании или при лимите — только чтения без
  блокировок), с повторным чтением кампании `FOR SHARE`, под advisory-lock аккаунта, с пересчётом вех в этой транзакции и дневным лимитом кампании под lock
  кампании; `onboarding_reward.account_id` — PK. Код — обычный `promo_code` с `campaign_id` и `recipient_id` (V76): погасить может только получатель
  (`PROMO_INVALID` для остальных, без оракула); открытый код не хранится — выводится из id кода HMAC под секретом промокодов (≈ 59 бит, без смещения), действует с
  минуты до выдачи (расхождение часов БД и приложения) до `issued_at + 7 дней`. Кампании — `promo_campaign` (V76) с кабинетом и журналом
  `PROMO_CAMPAIGN_*`; коды кампаний не попадают в общий список промокодов. Выключатели: `learning.onboarding.reward.enabled` (глобальный) и `enabled` кампании;
  выданные коды не отзываются. Метрика `mnema_path_reward_total{outcome}`.
- **Frontend.** `JourneyStore.load({refresh})` во время идущего запроса теперь перечитывает после него (иначе итоги занятия могли получить вид, начатый до
  завершения). `AttentionCoordinator` получил поверхность `reward` между `hint` и `promo`. Тост цели недели — в `PathToasts` по той же политике.
