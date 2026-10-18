---
artifact:
  id: onboarding-ob-e3-hot-path
  type: evidence
  title: "OB-301: milestone writes on the study hot path"
  status: current
  created_at: "2026-10-18"
  owners: ["project-owner"]
---

# Вехи на горячем пути занятия (OB-301)

Что добавляет запись вех `FIRST_SESSION` и `TWO_DAYS` к завершению занятия
(`StudySessionRepository.complete`, `AttemptRepository.completeSessionIfTerminal`). Число выражений считается на JDBC-соединении и **проверяется** в обычном gate
(`MilestoneHotPathIntegrationTest`); времена и планы снимает opt-in тест `MilestoneHotPathEvidenceTest`
(`MNEMA_PATH_EVIDENCE=1 ./gradlew :services:learning:cleanTest :services:learning:test --tests '*MilestoneHotPathEvidenceTest'`):
Testcontainers PostgreSQL, JDK 25, Colima, таблица `study_session` в 100k строк, 300 итераций после 100 прогревочных, аккаунт с
2000 завершёнными занятиями. Прогон 2026-10-18 после исправлений review (вехи с целью 1 — `INSERT … ON CONFLICT DO NOTHING` без
блокировки строки; проба второго дня — min/max по частичному индексу `study_session_study_dates`, V74). Машина была нагружена
другими проверками, поэтому времена — порядок величины.

## Добавочные выражения и время

| Случай | Выражений | Медиана | p95 |
|---|---|---|---|
| REPLAY | 0 | ≈ 0 | ≈ 0 |
| Обе вехи уже достигнуты (установившийся режим) | 1 (чтение по PK) | 0,31 мс | 0,58 мс |
| Второго дня ещё нет, все 2000 занятий сегодня | 2 | 0,86 мс | 1,37 мс |
| Первое завершение, 400 учебных дней | 4 | 1,28 мс | 2,17 мс |
| Для масштаба: существующий `DeckEngagement.studied` в том же прогоне | 1 | 0,46 мс | 0,96 мс |

Вся первая транзакция с двумя уведомлениями `PATH_MILESTONE` в `beforeCommit` — ≈ 3,3 мс (p95 4,1 мс), 14 выражений;
это разовая цена первого занятия аккаунта. После достижения обеих вех добавка — одно чтение по первичному ключу
`account_milestone` (≈ 0,3 мс), сравнимо с уже существующей записью engagement.

## Планы (`EXPLAIN (ANALYZE, BUFFERS)`)

| Выражение | План | Время |
|---|---|---|
| Проверка достигнутых (`account_id`, `milestone_key IN (…)`) | Index Scan по PK, 2 buffer hits | 0,02 мс |
| Веха с целью 1 уже есть (`ON CONFLICT DO NOTHING`) | без записи и без блокировки строки | 0,03 мс |
| Upsert стадии/счётчика, прогресс не растёт | 0 строк записано | 0,03 мс |
| Upsert, новая строка или рост | 1 строка | 0,02–0,03 мс |
| Проба второго дня (min/max по `study_session_study_dates`), 400 дней и 2000 занятий за один день | Index Only Scan + Index Only Scan Backward | 0,015–0,02 мс |
| Чтение «Пути» для `GET /api/onboarding` | Index Scan по PK | 0,01 мс |

Первая версия пробы (`EXISTS … local_study_date <> :date`) на таблице в 100k строк планировалась как seq scan (≈ 6 мс): это нашёл
evidence-тест, отсюда индекс в V74 (он же нужен ритму OB-401, architecture §7) и форма min/max.

## Вывод

Критерий OB-301 «горячий путь: измерено добавочное время» выполнен: в установившемся режиме +1 PK-чтение на завершение
занятия, на ответы (попытки) запись вех не добавляет ничего, кроме `AI_EXPLANATION` для ответа, проверенного
`ai-semantic` (один upsert). Существующие тесты Study зелёные в полном gate.
