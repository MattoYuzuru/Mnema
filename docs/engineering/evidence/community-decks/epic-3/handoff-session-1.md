---
artifact:
  id: community-decks-epic-3-handoff-1
  type: handoff
  title: "Handoff: эпик 3, сессия 1"
  status: historical
  created_at: "2026-10-12"
  updated_at: "2026-10-14"
  owners: ["project-owner"]
---

# Handoff: эпик 3, сессия 1

Handoff сессии 1 эпика [#421](https://github.com/MattoYuzuru/Mnema/issues/421), опубликован в нём комментарием.

**База.** main `ae556ece` (#496). Весь эпик влит одним PR [#E3PR](https://github.com/MattoYuzuru/Mnema/pull/E3PR) (заменил #500 и #501) и выкачен выключенным.
Полный gate и harness `--catalog`, `--community`, `--updates` зелёные на `74ac7dfd`; после него только переключатели локального стека и документы.

**Сделано.** Community/0–8, миграции `V66`–`V69`. Подробности, ревью и проверки — [evidence эпика 3](README.md).
- Community/0 #447: проверка production (7 триграмм кириллицы), `unaccent` установлен в production, `V66`.
- Community/1 #448: проекция `public_deck` и `catalog_listing`, задачи проекции и перестройки.
- Community/3 #450: `deck_engagement`, ранг поколениями, флаги накруток, утверждения `mnema_trusted` и `mnema_new_account` в Identity.
- Community/2 #449, Community/6 #453, Community/7 #454: публичные маршруты каталога, поиск, колоды автора, «Выбор Мнемы», `/sitemap-decks.xml`; страницы `/community`, `/u/{username}`,
  раздел «Сообщество» в кабинете. Элементы сначала в styleguide (PR #501).
- Community/8 #455: сценарий `--catalog`, EXPLAIN на 10⁴ и 10⁵, документы канона.

**Изменено относительно канона** (записано в «Уточнено реализацией (эпик 3)» продуктового контракта и архитектуры): «Новое» по первому входу в каталог; снятие из каталога синхронно;
метрики пишутся синхронно, а не задачей по курсору; `Q` взвешена доверием, затухание по слагаемым; вход через Google/Яндекс/GitHub — подтверждённая почта; поиск — объединение трёх
индексных веток, `enable_seqscan=off` в его транзакции, до 2000 кандидатов на ветку; sitemap колод — отдельный `/sitemap-decks.xml`; превью `/u/` — общая карточка; раздел политики
«Сообщество и рекомендации» ставится с Recs/1 (эпик 4), а не в эпике 3.

**Риски.** Пока нет purge в Learning (#409), колоды удалённого автора остаются в каталоге без подписи; медиа, ставшее готовым после публикации, учитывается со следующей публикацией;
кеши фасетов (5 мин) и sitemap (до часа) на экземпляр; одно слово в нескольких процентах названий — самый медленный поиск (до ~60 мс при 10⁵).

**Не проверено.** Скринридеры, Safari, Firefox, touch; UI флагов накруток и полка «Учат сейчас» в браузере; production.

**Поставка (2026-10-14).** [#E3PR](https://github.com/MattoYuzuru/Mnema/pull/E3PR): Identity и Learning одним релизом, на хост установлены
`deploy/production/nginx.conf` (`/sitemap-decks.xml`) и `init-database.sh`; каталог выключен.

**Оставшиеся шаги.**
1. Включение (отдельный PR после проверки на production): `LEARNING_COMMUNITY_CATALOG_ENABLED: "true"` в
   `deploy/production/compose.yaml` (установить на хост до одобрения); `COMMUNITY_LAUNCHED = true` во frontend; `COMMUNITY_ROUTE` в
   `features/public-deck/public-deck.text.ts`; строка `Sitemap: https://mnema.app/sitemap-decks.xml` в `frontend/src/robots.txt`; тексты политики
   и соглашения уже входят в версию 1.6 эпика 4 (см. [handoff эпика 4](../epic-4/handoff-session-1.md)). Затем smoke на mnema.app: `/community`,
   поиск, профиль, `/sitemap-decks.xml`.
2. Попутная #462 — не раньше 2026-10-24 (две недели после запуска GC).
