---
artifact:
  id: community-decks-epic-3-evidence
  type: verification-evidence
  title: "Колоды сообщества, эпик 3: evidence задач"
  status: current
  created_at: "2026-10-12"
  updated_at: "2026-10-12"
  owners: ["project-owner"]
---

# Эпик 3: Сообщество и поиск

Evidence задач эпика [MattoYuzuru/Mnema#421](https://github.com/MattoYuzuru/Mnema/issues/421).
Канон: [продуктовый контракт](../../../../product/community-decks.md),
[архитектура](../../../../architecture/community-decks.md) (§9, §10, §12 и «Уточнено реализацией (эпик 3)»),
[refinement](../../../community-decks-refinement.md). Подробности реализации — в
[guide Learning](../../../../../backend/services/learning/guide.md), разделы Community/0–7.

Точные цифры gate и ревью — в теле и комментариях каждого PR.

## Задачи

### Community/0: pg_trgm и unaccent на production ([#447](https://github.com/MattoYuzuru/Mnema/issues/447))

**Read-only проверка production, 2026-10-12** (`ssh mnema`, `psql` суперпользователем в контейнере
`mnema-prod-postgres-1`, запрос в `default_transaction_read_only`, без данных пользователей):

| Что | Результат |
|---|---|
| Сервер | PostgreSQL 18.6 on x86_64-pc-linux-musl (образ `postgres:18.6-alpine3.24`) |
| Кодировка, локаль базы `mnema` | `UTF8`; `datcollate` и `datctype` `en_US.utf8`, провайдер `libc` |
| `show_trgm('Привет')` | 7 триграмм (непустой результат: эскалация не нужна) |
| `word_similarity('испанск', 'Испанские глаголы')` | 0,875 |
| `lower('ПРИВЕТ Ёж')` | `привет ёж` |
| `to_tsvector('russian', 'Испанские глаголы')` | `'глагол':2 'испанск':1` |
| Расширения | `app_learning.pg_trgm 1.6`; `unaccent 1.1` доступен, не установлен |
| `app_learning` может создавать расширения | нет (`CREATE` на базе не выдан) |

**Установка.** `unaccent` установлен в схему `app_learning` суперпользователем один раз
(`CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA app_learning`, 2026-10-12), до релиза с
миграцией `V66`. Для нового кластера то же делает `deploy/production/init-database.sh`.
Миграция `V66__catalog_search_dictionaries.sql` требует оба расширения (`IF NOT EXISTS`, без
перехвата ошибки) и создаёт конфигурацию `app_learning.simple_unaccent`.

**Проверка в CI.** `scripts/tests/test_vps_search_extensions.py` (шаг «Verify VPS runtime and
isolated local restore» в `pull-request.yaml`) поднимает production-образ PostgreSQL из
`deploy/production` с init-скриптом без сети и проверяет расширения в `app_learning`, кириллические
триграммы, а также выполнение `V66` ролью `app_learning` без права `CREATE`. Learning:
`CatalogSearchDictionariesIntegrationTest`.

### Community/2, /6, /7: каталог, поиск, колоды автора, полка владельца и sitemap ([#449](https://github.com/MattoYuzuru/Mnema/issues/449), [#453](https://github.com/MattoYuzuru/Mnema/issues/453), [#454](https://github.com/MattoYuzuru/Mnema/issues/454))

Маршруты `GET /api/public/community/{facets,shelves,decks,authors/{accountId}/decks}`, `GET /api/public/sitemap.xml` и `GET`/`PUT /api/admin/community/editorial`
(миграция `V69__editorial_shelf.sql`), контракт [`contracts/community/catalog.json`](../../../../../contracts/community/catalog.json). Реализация и правила — в
[guide Learning](../../../../../backend/services/learning/guide.md), раздел «Catalogue routes and search».

**Планы и время** на синтетическом каталоге (`CatalogScaleIntegrationTest`: `public_deck` и одно поколение ранга пишутся SQL, названия из трёх слов со Zipf-перекосом по словарю из 4 000
псевдослов в трёх письменностях, листовые темы справочника по кругу, 1 000 авторов; PostgreSQL 18 в контейнере на ноутбуке; страница и её подсчёт `total`, 5 прогревов и 30 замеров).
Воспроизведение: [`catalog-scale.sh`](catalog-scale.sh) `[число колод]`; полные `EXPLAIN (ANALYZE, BUFFERS)` каждого запроса — [10 000](catalog-scale-10000.txt) и [100 000](catalog-scale-100000.txt).
Бюджет архитектуры §12: p95 меньше 50 мс при 10⁴ и меньше 200 мс при 10⁵ (проверяется тестом).

| Запрос | p95, 10⁴ | p95, 10⁵ | План |
|---|---|---|---|
| popular / learning, первая и средняя страница; popular по листовой теме | 2–4 мс | 2–15 мс | `deck_rank_popular` / `deck_rank_learning` + ключ `public_deck`, без Seq Scan |
| new / updated (в том числе fresh, тема, уровень, страница из середины) | 1–2 мс | 1–4 мс | `public_deck_new` / `public_deck_updated` / `public_deck_topic` / `public_deck_parent_topic` + incremental sort по коду |
| popular + тема + уровень + медиа; редкое сочетание фильтров | 3–5 мс | 35–39 мс | кандидаты по фильтру и ключ `deck_rank` (порядок индекса недоступен) |
| поиск: редкое слово, латиница, японское название, опечатка | 7–12 мс | 8–12 мс | `UNION` трёх индексов: `public_deck_search_ru`, `public_deck_search_simple`, `public_deck_title_trgm` |
| поиск: тема по синониму, слово в нескольких процентах названий (с фильтром и без), вторая страница | 11–26 мс | 49–80 мс | то же + btree темы |
| полки, колоды автора, «Учат сейчас» автора | меньше 2 мс | меньше 5 мс | диапазоны индексов и ключи |
| фасеты (кэш 5 мин) / sitemap (кэш 1 ч, 50 000 строк) | 3 / 10 мс | 22 / 57 мс | один проход по листингу, `public_deck_new` |

**Находка.** Планировщик оценивает `@@` и `%>` как дешёвые операторы и до десятков тысяч строк предпочитает вычислять их на каждой строке (≈1,7 мкс на строку, 20–28 мс при 10⁴) вместо трёх индексов,
которые для этого созданы (те же запросы выполняются за 0,2–7 мс). Поэтому транзакция поиска ставит `enable_seqscan = off`, `plan_cache_mode = force_custom_plan` и порог триграмм (`set_config(…, true)`),
а кандидаты собираются `UNION` по одному индексному предикату на ветку: в одном `OR` планировщик обходит весь частичный индекс `listed`.

**Что должен проксировать веб-сервер.** `/sitemap.xml` → `/api/public/sitemap.xml` (тело и `Cache-Control: public, max-age=3600` отдаются как есть) и строка `Sitemap: https://mnema.app/sitemap.xml` в
`robots.txt`. Остальные маршруты каталога достижимы только через `/api/public/community/…` (SPA).
