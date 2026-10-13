---
artifact:
  id: community-decks-epic-4-evidence
  type: verification-evidence
  title: "Колоды сообщества, эпик 4: локальная реализация и проверки"
  status: current
  created_at: "2026-10-13"
  updated_at: "2026-10-13"
  owners: ["project-owner"]
---

# Эпик 4: рекомендации и витрины

Эпик [#422](https://github.com/MattoYuzuru/Mnema/issues/422), задачи Recs/1–4 (#456–#459).
Рабочая ветка `local/epic4`, worktree `/Users/yuzuru/Projects/Mnema-cd4`, база `f033d533` из
`local/epic3`. GitHub отвечает `account suspended` (403, 2026-10-13); push, PR, hosted CI,
изменения доски, merge и production не выполняются. Основной checkout владельца не изменяется.

Канон: [продукт](../../../../product/community-decks.md),
[архитектура](../../../../architecture/community-decks.md),
[refinement](../../../community-decks-refinement.md),
[контракт и исполняемые примеры](../../../../../contracts/community/README.md).

## Состояние реализации

- Recs/1: реализованы политика 1.6, контракт событий/показов, backend/client запись и ограниченное обслуживание хранения.
- Recs/2: реализованы профиль интересов, optional chips, сброс и ночной пересчёт.
- Recs/3: реализованы ограниченные кандидаты, каноническая формула, строгое разнообразие, исследовательские места и полка.
- Recs/4: готов воспроизводимый [скрипт сравнения](evaluate-rankers.py),
  [фикстура](ranker-fixture.json), [синтетические логи](synthetic-logs.json) и
  [результат](ranker-comparison.json). Добавлен настоящий HTTPS браузерный сценарий `--recs` и регрессии каталога/публикации/обновлений.

## Сравнение ранкеров на синтетике

Воспроизведение из корня checkout:

```bash
python3 docs/engineering/evidence/community-decks/epic-4/evaluate-rankers.py \
  --synthetic docs/engineering/evidence/community-decks/epic-4/ranker-fixture.json \
  --write-logs /tmp/mnema-epic4-synthetic-logs.json
```

Фиксированный seed `422456459`, 3 000 одинаковых сессий для двух версий, 56 синтетических
колод по восьми темам и семи авторам. Ответы моделируются одинаковыми случайными числами
для двух ранкеров; вероятность открытия соответствующей интересу темы 0,55, другой 0,12,
позиция уменьшает вероятность. Это предположения модели, а не наблюдения пользователей.
`catalog-v1` сортирует по базовому score; `recs-v1` использует принятую формулу, пределы первых
10 и два исследовательских места. Этот replay проверяет воспроизводимость обработки логов;
корректность производственного алгоритма подтверждается отдельными backend-фикстурами. В модели каждому пользователю задан один явный интерес, исходный pool равен 56 строкам фикстуры: запросы кандидатов, top-3 профиль, parent-goal, языки реальных копий, ACL, reset и сохранение выдачи не моделируются. Правила score (включая отсутствующий target при уверенном языке), tie-break, строгое разнообразие и поиск допустимой исследовательской пары совпадают с производственным ранкером. Даты первого листинга имеют два фиксированных уровня: день симуляции для new и 40 дней до него для остальных.

| На 1 000 показов | catalog-v1 | recs-v1 |
|---|---:|---:|
| Открытия | 130,694 | 178,167 |
| Добавления | 45,722 | 61,417 |
| Начала занятий | 27,111 | 36,083 |
| Удаления копий | 5,889 | 8,222 |

У каждой версии 36 000 показов. Переходы из поиска в этой модели отсутствуют: это явно
нулевой счётчик, не замер поискового UX. Больше открытий и занятий в модели не доказывает
пользу на production; выше и удаления. Анонимные показы не позволяют считать конверсию
разных пользователей. Отношения могут быть больше 1 000 при повторных действиях и не
обрезаются. Скрипт отклоняет account_id, тексты запросов, содержимое колод и другие поля.

### Повторение на production после отдельного запуска владельцем

Экспортировать только дневные суммы показов по `rank_version`/`surface` и событий по
`rank_version`/`surface`/`kind`, без account_id, deck_id, revision_id и запросов. Формат входа
совпадает с [синтетическими логами](synthetic-logs.json). Команда:

```bash
python3 docs/engineering/evidence/community-decks/epic-4/evaluate-rankers.py --logs /absolute/path/to/anonymized-logs.json
```

Готовый read-only [SQL-экспорт](export-ranker-logs.sql) принимает параметры `from`/`until` (даты UTC, правая граница исключена); запускать только после отдельной авторизации владельца на production.

Сравнивать одинаковые интервалы, доступные аудитории и состав поверхностей. Переходы и
lifecycle считать отдельно; `FORK` наследует последнюю атрибуцию открытия за 30 минут,
`STUDY_START`/`DROP` атрибуцию добавления. Без сопоставимых когорт результат описывает
счётчики, а не причинный эффект. При одной версии в логах отчёт показывает только её,
ничего не придумывая. Сбросы удаляют личные сырые события, тогда как анонимные показы остаются; отчёт после сбросов может занижать исторические отношения. Для вывода о пользе нужен отдельно спланированный контролируемый
production-эксперимент; он не входит в этот локальный прогон.

### Пороги следующего шага

- Co-occurrence «Добавившие X добавили Y»: примерно 2–5 тыс. активных пользователей,
  достаточно пересечений по темам и устойчивые логи после запуска. Размер аудитории
  сам по себе не доказывает качество модели; сначала offline replay и контролируемое сравнение.
- Эмбеддинги/pgvector: больше 20–50 тыс. колод или подтверждённая потребность в межъязыковом
  поиске. До этого остаётся прозрачная модель по темам, без ML и внешних сервисов.
- Давление на диск событий: сначала измерить размер партиций; sampling `DETAIL_VIEW`
  возможен только по решению владельца. Хранение 13 месяцев не меняется.

## Критерии и evidence

| Критерий | Подтверждение |
|---|---|
| Не более двух авторов/темы в первых десяти; два исследовательских места; исключение авторских и живых копий | Backend ranker, real PostgreSQL/API integration; [браузерный результат](browser/recs.json) проверяет 12 карточек до и после настоящей копии |
| Главная тема объясняет полку и сетку; sparse персональная полка скрыта при непустом обычном каталоге | `recs_onboarding_and_shelf`, `recs_sorting_motion_reflow_a11y`, `recs_sparse`; component tests |
| Видимый и сбрасываемый профиль; старые сигналы не возвращаются | DB reset/goal/copy race tests, `recs_profile_reset`; cached profile refresh/version1→9/reset If-Match9 component regression |
| Приватность и сроки | Strict input/ACL/idempotency/partitions tests; guest401, no query/content schema, authenticated telemetry, rankVersion attribution в browser; публичная политика1.6 |
| Ноль/H, ограниченные запросы и задачи | [Исходные планы и размеры](scale-local-2026-10-13.txt), таблица архитектуры §12; bounds и выключатели в Learning guide |
| Сравнение версий и пороги | Воспроизводимая синтетика выше; семь тестов Python, read-only anonymous SQL export; причинная польза ещё не доказана |
| Поведение эпиков1–3 сохранено | [Каталог](browser/catalog.json), [публикация и обновления](browser/community-updates.json); финальные прогоны повторяются на итоговом HEAD |

### Коммиты, миграции, независимое ревью

База `f033d533`. `f1118581` контракт/политика; `e4cabd08` frontend; `e459290b` исправления UI;
`623f47cc` backend; `4465582b` ranker/explanation; `59235665` инвалидация выдачи по effective
copy/drop/goal; `88a19065` актуальный профиль перед сбросом. Последующий Recs/4 commit содержит
скрипт, browser harness, этот отчёт и документы. Полный диапазон: `f033d533..local/epic4`.
Миграции эпика4: `V70__catalog_events.sql`, `V71__topic_interests.sql`; предыдущие V66–V69
нужны вместе с эпиком3. Новых зависимостей нет.

Независимые backend/security и UI/a11y ревью нашли и повторно подтвердили исправления:
выбор допустимой exploration-пары при scarcity, live explanation после скрытия темы,
разделение telemetry navigation epoch, объяснение grid, заранее смонтированный status,
edit/cancel focus и сохранение explicit popular. Усиленный реальный browser выявил ещё
неинвалидированный кеш после копии с прежними весами: effective copy/drop/goal теперь
повышают version ровно один раз, а replay/existing-copy/unchanged night его не повышают.
После этой правки выполнены27 DB/API/ranker tests без skip; frontend correction375 tests
в20 файлах, lint/build/no-styleguide зелёные. Повторные независимые ревью замечанийP1/P2 не нашли.
Recs/4 review исправил missing-target language factor, scarcity/pair модель, duration после await
и добавил настоящее sparse browser coverage. Скрипты имеют7 и45 целевых тестов соответственно.

### Снимки и границы браузерной проверки

[Первый вход](browser/recs-desktop-first-entry.png), [полка1440](browser/recs-desktop-for-you.png),
[сетка320](browser/recs-mobile-320.png), [reduced motion](browser/recs-mobile-reduced-motion.png),
[сброс профиля](browser/recs-profile-reset.png), [sparse](browser/recs-mobile-sparse.png).
[720px reflow](browser/recs-zoom-200.png) соответствует доступной ширине desktop1440 при200%,
это не настоящий browser zoom/text scaling. Снимки получены после завершения конечных анимаций.
Все действия — реальный Chrome/HTTPS/Identity/Learning/PostgreSQL18 с disposable fixtures;
нет runtime ошибок и внешних сетевых обращений. JSON содержит только результаты; соседние
`*-digests.json` — SHA256 входных артефактов без tokens/accounts/env.

### Итоговый source-bound gate

После последнего commit выполняются полностью и последовательно:
`./gradlew clean quality` (JDK25, реальный Docker/PostgreSQL, coverage floors), frontend
`npm ci`, lint, coverage tests, production build, `verify-no-styleguide`; `verify_docs`,
полные `scripts/tests` и `browser-identity` unit tests, CI policy/release/security/delivery
contracts, smoke unit tests, реальный learning-security/cancellation и четыре browser modes
(`--recs`, `--catalog`, совместный `--community --updates`). Не используются старые green outputs
или skipped DB evidence. Фактические итоговые HEAD/counts/results и пути receipt указаны в
финальном отчёте владельцу; `~/.local/state/agent-dev/check-*/result.json` сохраняют HEAD,
fingerprint до/после, exit, свежие JUnit и log. Их намеренно не добавляем в Git: это позволяет
проверить именно окончательный commit без нового commit с его собственной SHA.

При будущем delivery после актуализации main gate повторяется перед push/merge. Hosted CI,
правила PR и deployment пока не проверены. Локальная реализация всех четырёх эпиков завершена;
эпики3–4 ещё не merged/applied в production. Следующие шаги — [handoff](handoff-session-1.md).

## Непроверенное и риски

Реальные скринридеры, Safari, Firefox, физический touch и production не проверялись.
Локальный H:5k public/200k copies/100k profiles,2M interests,1M events в одном месяце,
200k snapshots,100k telemetry receipts,140k exposure rows. Full47M/13months и весь nightly100k
не исполнялись:9,66GB и16,4min — экстраполяции, а не production capacity claim. На эффективный
равновесный copy/drop/goal добавлен один PK version UPDATE; отдельный H замер этогоO(1)
statement не делался. Account purge#409 остаётся отдельной задачей с индексами иTODO;
read-only production index#462 отложена до2026-10-24. Реальная польза для аудитории требует
отдельного контролируемого сравнения, не синтетического lift.
