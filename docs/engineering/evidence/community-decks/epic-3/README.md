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
| поиск: редкое слово, латиница, японское название, опечатка | 5–10 мс | 7–13 мс | `UNION` трёх индексов: `public_deck_search_ru`, `public_deck_search_simple`, `public_deck_title_trgm` |
| поиск: тема по синониму, слово в нескольких процентах названий (с фильтром и без), вторая страница | 8–18 мс | 33–63 мс | то же + btree темы |
| полки, колоды автора, «Учат сейчас» автора | меньше 2 мс | меньше 5 мс | диапазоны индексов и ключи |
| фасеты (кэш 5 мин) / sitemap (кэш 1 ч, 50 000 строк) | 3 / 10 мс | 22 / 57 мс | один проход по листингу, `public_deck_new` |

**Находка.** Планировщик оценивает `@@` и `%>` как дешёвые операторы и до десятков тысяч строк предпочитает вычислять их на каждой строке (≈1,7 мкс на строку, 20–28 мс при 10⁴) вместо трёх индексов,
которые для этого созданы (те же запросы выполняются за 0,2–7 мс). Поэтому транзакция поиска ставит `enable_seqscan = off`, `plan_cache_mode = force_custom_plan` и порог триграмм (`set_config(…, true)`),
а кандидаты собираются `UNION` по одному индексному предикату на ветку: в одном `OR` планировщик обходит весь частичный индекс `listed`.

**Что должен проксировать веб-сервер.** `/sitemap-decks.xml` → `/api/public/sitemap.xml` (статический `/sitemap.xml` остаётся для статических страниц; тело и `Cache-Control: public, max-age=3600` отдаются как есть) и строки `Sitemap: https://mnema.app/sitemap.xml` и `Sitemap: https://mnema.app/sitemap-decks.xml` в
`robots.txt`. Остальные маршруты каталога достижимы только через `/api/public/community/…` (SPA).

### Community/1: проекция `public_deck` ([#448](https://github.com/MattoYuzuru/Mnema/issues/448))

Миграция `V67__public_deck.sql` (`public_deck`, `catalog_listing`), очереди `catalog.project` и `catalog.rebuild`, бэкфилл уже публичных колод задачей из миграции.
Ревью `architecture-agent:adversarial-architect` дало пять правок, все внесены: «Новое» по первому входу в каталог (`catalog_listing.first_listed_at`), синхронное снятие строки при
скрытии, понижении и удалении, результат сканирования механик и медиа по ревизии, без блокировки строки колоды, метрика `mnema_catalog_projection_lag`. Тесты
`PublicDeck*IntegrationTest`, `CatalogVocabularyTest`.

### Community/3: «Добавили / Учат сейчас», ранг v1, флаги накруток ([#450](https://github.com/MattoYuzuru/Mnema/issues/450))

`V68__deck_engagement_and_rank.sql`: `deck_engagement` (пишется в транзакции копии и завершения SCHEDULED-сессии копии), `rank_generation`/`deck_rank` (поколение раз в час и через
минуту после входа новой колоды в каталог, хранение 24 часа), `catalog_anomaly`; утверждения Identity `mnema_trusted` и `mnema_new_account` в `/userinfo`. Ревью безопасности
(`sde-agent:implementation-reviewer`): `Q` взвешена доверием, затухание по каждому слагаемому, вход через Google/Яндекс/GitHub считается подтверждённой почтой, неизвестное доверие
хранится как NULL и дозаполняется. Замер в сценарии H: 5 000 колод и около 300 000 строк вовлечённости — пересчёт поколения около 0,2 с (`CatalogRankScaleIntegrationTest`).

### Community/4–6 frontend: страница «Сообщество», книга-поиск, фильтры, профиль автора ([#451](https://github.com/MattoYuzuru/Mnema/issues/451), [#452](https://github.com/MattoYuzuru/Mnema/issues/452), [#453](https://github.com/MattoYuzuru/Mnema/issues/453))

Сначала элементы в `/styleguide` (`app-book-search`, `app-typewriter-hint`, `app-catalog-filters`, `app-deck-shelf`, `app-deck-grid`, `app-community-empty`, `app-author-header`,
`app-order-row`, заметка карточки), затем маршруты `/community` и `/u/:username`, вход в шапке и ссылка в пустых «Моих колодах» за флагом запуска `COMMUNITY_LAUNCHED` (выключен).
Три ревью (`frontend-agent:accessibility-reviewer` дважды, `sde-agent:implementation-reviewer`), все замечания внесены.

### Community/8: интегрированная проверка ([#455](https://github.com/MattoYuzuru/Mnema/issues/455))

**Gate на точном коммите `74ac7dfd`** : backend `clean quality` (покрытие learning 96,15 %, identity-account 93,08 %), frontend `npm ci`, lint, test (262 файла),
build, `verify-no-styleguide`; `verify_docs.py`; `scripts/tests` (417), тесты harness (43). Browser harness на том же коммите: `--catalog` (13 этапов, desktop и mobile), `--community`,
`--updates` — все зелёные. Коммит `1606e8cb` после него меняет только переключатели локального стека, генератор `app-config.js` и документацию; его тесты (`test_browser_identity_config.py`,
`test_verify_local*.py`) зелёные.

Сценарий `--catalog` нашёл и закрыл три дефекта: браузер не передавал токен вошедшего в маршруты каталога («Доступна вам» не появлялась), горизонтальная прокрутка страницы на 390 px из-за
полки, потеря фокуса после «Все». Проверка требований (`product-agent:requirements-critic`) дала правки: быстрый ранг новой колоды, сортировки вместе с запросом, опечатки в синонимах
тем, «Учат сейчас» автора по разным ученикам, порог в шагах пустого Сообщества, честная подпись полки в кабинете, заметка о снятом фильтре, заметка автору со скрытым профилем.

| Критерий завершения #421 | Чем подтверждён |
|---|---|
| Колоды ниже порога не в выдаче; цифры не считают автора и показываются от 10 | `PublicDeckProjectionIntegrationTest`, `CatalogRankIntegrationTest`, `AudienceCountsTest`; `--catalog` этапы `seed`, `counts`, `author` |
| Поиск «испанский»↔«Spanish», опечатки; стабильная пагинация; гостевые лимиты | `CatalogSearchHttpIntegrationTest`, `CatalogCursorTest`, `CatalogRateLimitHttpIntegrationTest`; `--catalog` этапы `search`, `scroll`, `limits` |
| Пустое Сообщество объясняет ситуацию; полки с малым числом колод скрыты | `--catalog` этапы `empty`, `shelves`; `community-page.component.spec.ts` |
| EXPLAIN на синтетике 10⁴–10⁵ в бюджете | таблица выше, [`catalog-scale-10000.txt`](catalog-scale-10000.txt), [`catalog-scale-100000.txt`](catalog-scale-100000.txt) |
| a11y и mobile | ревью доступности; `--catalog` этапы `a11y`, `motion`, `masthead` (390/768/900/1280 px) |
| Ноль и сценарий H | разделы «Load» guide Learning для Community/0–3 и 2; замеры ранга и каталога выше |
| Styleguide первым; существующие компоненты | `/styleguide` раздел «Сообщество»; `app-auto-load`, `app-mnema-select`, `app-segmented-choice`, `.check-row` |
| Sub-issues в Project #4, Done после merged/applied | [#502](https://github.com/MattoYuzuru/Mnema/pull/502) влит и выкачен выключенным; задачи #447–#455 закрыты; включение каталога — отдельный шаг |

**Не проверено.** Настоящие скринридеры, Safari, Firefox, touch и телефон; реальное совпадение фильтра «Внутри: аудио» в браузере; полка «Учат сейчас» и дни занятий в браузере (есть
в интеграционных тестах); UI флагов накруток в браузере; 412 по истёкшему поколению и 503 в браузере (есть в интеграционных тестах); превью ссылок через nginx в браузере; production.

## Состояние поставки

- Влит одним PR [#502](https://github.com/MattoYuzuru/Mnema/pull/502) (2026-10-14); он заменил [#500](https://github.com/MattoYuzuru/Mnema/pull/500) и
  [#501](https://github.com/MattoYuzuru/Mnema/pull/501), коммиты которых вошли без изменений.
- Production: `unaccent` установлен в `app_learning` 2026-10-12; `V66`–`V69` выкачены 2026-10-15 одним релизом
  с эпиком 4 ([#541](https://github.com/MattoYuzuru/Mnema/pull/541)), каталог выключен (`learning.community.catalog.enabled=false`,
  `COMMUNITY_LAUNCHED=false`). Включение — отдельный PR по [handoff](handoff-session-1.md), «Оставшиеся шаги».

