---
artifact:
  id: community-decks-epic-3-evidence
  type: verification-evidence
  title: "Колоды сообщества: каталог, поиск и замеры планов"
  status: current
  created_at: "2026-10-12"
  updated_at: "2026-10-19"
  owners: ["project-owner"]
---

# Каталог и поиск: расширения PostgreSQL и замеры планов

Воспроизводимые замеры каталога и поиска колод сообщества. Статус задач, PR и ревью живут
в Issue [#421](https://github.com/MattoYuzuru/Mnema/issues/421) и его PR. Поведение —
в [продуктовом контракте](../../../../product/community-decks.md), техника — в
[архитектуре](../../../../architecture/community-decks.md) (§9, §10, §12), реализация —
в [guide Learning](../../../../../backend/services/learning/guide.md), разделы Community/0–7.

## Расширения pg_trgm и unaccent

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

## Замеры планов и времени

**Планы и время** на синтетическом каталоге (`CatalogScaleIntegrationTest`: `public_deck` и одно поколение ранга пишутся SQL, названия из трёх слов со Zipf-перекосом по словарю из 4 000
псевдослов в трёх письменностях, листовые темы справочника по кругу, 1 000 авторов; PostgreSQL 18 в контейнере на ноутбуке; страница и её подсчёт `total`, 5 прогревов и 30 замеров).
Воспроизведение: [`catalog-scale.sh`](catalog-scale.sh) `[число колод]`; полные `EXPLAIN (ANALYZE, BUFFERS)` каждого запроса — [10 000](catalog-scale-10000.txt) и [100 000](catalog-scale-100000.txt).
Бюджет архитектуры §12: p95 меньше 50 мс при 10⁴ и меньше 200 мс при 10⁵ (проверяется тестом).

| Запрос | p95, 10⁴ | p95, 10⁵ | План |
|---|---|---|---|
| popular / learning, первая и средняя страница; popular по листовой теме | 2–4 мс | 2–15 мс | `deck_rank_popular` / `deck_rank_learning` + ключ `public_deck`, без Seq Scan |
| new / updated (в том числе fresh, тема, уровень, страница из середины) | 1–2 мс | 1–4 мс | `public_deck_new` / `public_deck_updated` / `public_deck_topic` / `public_deck_parent_topic` + incremental sort по коду |
| popular + тема + уровень + медиа; редкое сочетание фильтров | 3–5 мс | 35–39 мс | кандидаты по фильтру и ключ `deck_rank` (порядок индекса недоступен) |
| поиск: редкое слово, латиница, японское название, опечатка | 5–10 мс | 7–13 мс | `UNION` трёх индексов: `public_deck_search_ru`, `public_deck_search_simple`, `public_deck_title_trgm` |
| поиск: тема по синониму, слово в нескольких процентах названий (с фильтром и без), вторая страница | 8–18 мс | 33–63 мс | то же + btree темы |
| полки, колоды автора, «Учат сейчас» автора | меньше 2 мс | меньше 5 мс | диапазоны индексов и ключи |
| фасеты (кэш 5 мин) / sitemap (кэш 1 ч, 50 000 строк) | 3 / 10 мс | 22 / 57 мс | один проход по листингу, `public_deck_new` |

**Находка.** Планировщик оценивает `@@` и `%>` как дешёвые операторы и до десятков тысяч строк предпочитает вычислять их на каждой строке (≈1,7 мкс на строку, 20–28 мс при 10⁴) вместо трёх индексов,
которые для этого созданы (те же запросы выполняются за 0,2–7 мс). Поэтому транзакция поиска ставит `enable_seqscan = off`, `plan_cache_mode = force_custom_plan` и порог триграмм (`set_config(…, true)`),
а кандидаты собираются `UNION` по одному индексному предикату на ветку: в одном `OR` планировщик обходит весь частичный индекс `listed`.

**Что должен проксировать веб-сервер.** `/sitemap-decks.xml` → `/api/public/sitemap.xml` (статический `/sitemap.xml` остаётся для статических страниц; тело и `Cache-Control: public, max-age=3600` отдаются как есть) и строки `Sitemap: https://mnema.app/sitemap.xml` и `Sitemap: https://mnema.app/sitemap-decks.xml` в
`robots.txt`. Остальные маршруты каталога достижимы только через `/api/public/community/…` (SPA).

## Проекция и ранг: замеры

Проекция `public_deck`/`catalog_listing` (миграция `V67`) принимает «Новое» по первому входу в каталог
(`catalog_listing.first_listed_at`); метрика отставания — `mnema_catalog_projection_lag`. Ранг (`V68`:
`deck_engagement`, `rank_generation`/`deck_rank`, `catalog_anomaly`) пересчитывается поколением раз в час и через
минуту после входа новой колоды в каталог. В сценарии H (5 000 колод, около 300 000 строк вовлечённости) пересчёт
поколения занимает около 0,2 с (`CatalogRankScaleIntegrationTest`).

## Критерии каталога и чем они подтверждены

| Критерий | Чем подтверждён |
|---|---|
| Колоды ниже порога не в выдаче; цифры не считают автора и показываются от 10 | `PublicDeckProjectionIntegrationTest`, `CatalogRankIntegrationTest`, `AudienceCountsTest` |
| Поиск «испанский»↔«Spanish», опечатки; стабильная пагинация; гостевые лимиты | `CatalogSearchHttpIntegrationTest`, `CatalogCursorTest`, `CatalogRateLimitHttpIntegrationTest` |
| EXPLAIN на синтетике 10⁴–10⁵ в бюджете | таблица выше, [`catalog-scale-10000.txt`](catalog-scale-10000.txt), [`catalog-scale-100000.txt`](catalog-scale-100000.txt) |
| Ноль и сценарий H | разделы «Load» guide Learning для Community/0–3 и 2; замеры ранга и каталога выше |
| Пустое Сообщество, a11y, mobile | `community-page.component.spec.ts`, браузерный harness `--catalog` (`scripts/browser-identity`) |

**Не проверено.** Настоящие скринридеры, Safari, Firefox и touch-устройства; реальное совпадение фильтра
«Внутри: аудио» в браузере; UI флагов накруток в браузере; превью ссылок через nginx в браузере.
