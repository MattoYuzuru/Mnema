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
