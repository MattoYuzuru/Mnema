---
artifact:
  id: community-decks-epic-4-handoff-1
  type: handoff
  title: "Handoff: эпик 4, сессия 1"
  status: current
  created_at: "2026-10-13"
  updated_at: "2026-10-13"
  owners: ["project-owner"]
---

# Handoff: эпик 4, сессия 1

Текст будущего комментария «Handoff: эпик 4, сессия 1» в [#422](https://github.com/MattoYuzuru/Mnema/issues/422).
GitHub отвечает `account suspended` (403). Push, PR, CI, доска, merge, закрытие issues и production
в этом прогоне не выполнялись. Основной checkout владельца `/Users/yuzuru/Projects/Mnema` не изменялся.

**База.** `local/epic4`, worktree `/Users/yuzuru/Projects/Mnema-cd4`, поверх `local/epic3` `f033d533`
(включает `d30c51a5` и весь эпик 3). Последняя известная база main эпика 3 — `ae556ece` (#496);
это локальное знание, не проверка свежести GitHub. База и отложенная поставка эпика 3:
[его handoff](../epic-3/handoff-session-1.md).

**Сделано локально.** Recs/1–4 (#456–#459), Learning `V70__catalog_events.sql` и
`V71__topic_interests.sql`; новые зависимости не добавлялись.

- Recs/1: UUIDv7/месячные события, анонимные дневные показы, lifecycle-атрибуция версии,
  обслуживание партиций и подтверждений телеметрии, политика 1.6 и сопутствующие таблицы.
- Recs/2: optional chips, выбранные/вычисленные темы, goal и копии, занятия/открытия,
  ночной пересчёт активных, профиль и сброс с новой границей сигналов.
- Recs/3: кандидаты до 100, принятая формула, строгое разнообразие первых десяти,
  исследовательские места 11/12, объяснения полки/сетки, исключение своих колод и живых копий,
  непротиворечивые курсоры и повторная проверка публичности.
- Recs/4: прозрачный [отчёт на синтетике](README.md#сравнение-ранкеров-на-синтетике), скрипт,
  fixture/logs, анонимный SQL-экспорт, пороги co-occurrence/эмбеддингов, `--recs` и регрессии
  `--catalog`, `--community`, `--updates`; документы отражают локальную реализацию всех четырёх эпиков.

**Коммиты до интеграционного evidence.** `f1118581` контракт/политика; `e4cabd08` frontend;
`e459290b` пять UI-правок ревью; `623f47cc` backend; `4465582b` две backend-правки ревью;
`59235665` инвалидация по effective signals; `88a19065` актуальный профиль перед reset.
Полный линейный диапазон эпика 4: `f033d533..local/epic4` (`git log` включает последующий Recs/4/evidence).
Все коммиты используют локальную Git identity владельца без дополнительных attribution trailers.

**Уточнено внутри канона.** Принятые формула, 13 месяцев, выключенный запуск и отсутствие ML сохранены.
Гости не записываются. Тексты поиска не сохраняются вообще. Текущий и 12 предыдущих календарных месяцев
UTC, два будущих заранее; DROP и cleanup продолжаются при выключенной записи. Телеметрия пакетируется
до 48 видимых карточек, без sampling; receipts этого scope живут семь дней. Interest receipts содержат
только ack, поэтому сброс не оставляет исторический профиль в результатах команд. Сброс/копия/цель/
пересчёт сериализуются строкой аккаунта и временем PostgreSQL. Цель даёт общих родителей справочника,
не выдуманный язык. Успешные SCHEDULED/PRACTICE дают интерес, REPLAY/EMPTY нет; scheduler/global
engagement не менялись. Равный нулевой score упорядочивается по интересу. B-matching в пределах 100
кандидатов выбирает допустимую пару exploration и наиболее приоритетные десять карточек; ограничения
не ослабляются. Effective copy/drop/goal повышают version ровно один раз даже при прежних normalized weights;
replay/existing-copy/unchanged night не повышают. `/profile` перечитывает известный профиль один раз
перед действиями, сохраняя unknown command retry; при412 сообщает, что действие ещё не выполнено.
Cache до 32 вопросов/аккаунт, cursor 24 часа, eviction →412. Подробности:
[контракт](../../../../../contracts/community/README.md), [архитектура](../../../../architecture/community-decks.md#уточнено-реализацией-эпик-4).

**Evidence.** [README](README.md) содержит критерии, проверки, ревью, снимки и замеры. Локальные
source-bound receipts хранятся вне Git в `~/.local/state/agent-dev`; итоговый отчёт владельцу указывает
финальный HEAD и результаты полного gate. Полный backend/frontend gate и scripts должны оставаться
зелёными на конкретном HEAD, до любого будущего push их необходимо повторить после актуализации main.
H: локально 5k public, 200k copies, 100k profiles/2M interests, 1M events. 47M≈9,66ГБ и nightly100k≈16,4мин
являются экстраполяциями; исходные планы/размеры сохранены, production не измерялся.

**Не проверено / риски.** Реальные скринридеры, Safari, Firefox, physical touch и production.
720 px при desktop1440 проверяет эквивалент reflow200%, не настоящий browser zoom/text scaling.
Полный объём 47M/13 месяцев и whole nightly100k не прогонялся; event-замер — в одном месяце.
Learning account purge #409 остаётся отдельным эпиком (поставлены actionable TODO и account indexes).
Сохраняются ограничения эпиков 1–3: удалённый автор до purge, поздно готовое медиа, кэши каталога;
польза рекомендаций на настоящей аудитории ещё не доказана.

**Точный следующий шаг после восстановления GitHub.** Владелец должен подтвердить отправку эпика 3
первым и отдельно разрешить production, если поручит выкатку агенту. Этот локальный мандат сам по себе
не даёт будущего разрешения на публикацию или prod approval.

1. Восстановить доступ, fetch актуального main, проверить правила/миграции и чужие изменения.
   Поставить эпик 3 до 4. Для эпика 3 сохранить порядок его handoff: #500/Community0 → #448 → #450 →
   #449/#453/#454 backend → #501 styleguide → #451/#452 page → #453 profile → #454 console → #455
   integration. Либо отдельный согласованный PR всего эпика 3. Каждый PR — обновлённая ветка,
   полный gate, backend-quality/frontend-quality, resolved review threads и protected squash;
   без force-push, обхода ruleset и прямого push main.
2. После squash эпика 3 создать новую delivery-ветку эпика 4 от свежего main и перенести **только**
   диапазон `f033d533..local/epic4` (смысловые коммиты Recs/1–4) либо подготовить последовательные PR:
   контракт/политика + события → интересы/styleguide → рекомендации → проверка/evidence.
   Не публиковать старую stacked-ветку с повторным diff эпика 3. Проверить полный delta и номера
   V66–V71: собственные невлитые миграции при конфликте перенумеровать вместе с тест-инвентарём.
   После каждого изменения и перед push — релевантный полный gate на точном коммите; перед merge
   полный repository gate, свежий main и зелёные защищённые checks.
3. Identity и Learning эпика 3 выкатить одним релизом (claims доверия); затем Learning эпика 4.
   До одобрения `prod` установить актуальные `deploy/production/nginx.conf`, `compose.yaml` и
   `init-database.sh` на хост. Nginx должен проксировать `/sitemap-decks.xml`; PostgreSQL18 nativeuuidv7,
   pg_trgm/unaccent и V66+ обязательны. Не изменять влитые миграции. Поставка через Main CI по
   [production delivery](../../../../operations/production-delivery.md), manual VPS job — только status/verify/rollback.
4. Каталог и рекомендации включать отдельными проверенными шагами. Для каталога:
   `LEARNING_COMMUNITY_CATALOG_ENABLED=true`, frontend `COMMUNITY_LAUNCHED=true`, `COMMUNITY_ROUTE`
   в public-deck.text и sitemap строка robots. Правки 1.5 (общие цифры автора/каталог в соглашении)
   уже входят в подготовленную **1.6**, поэтому две ступени текстов можно поставить одним релизом
   без возврата версии. Дату `LEGAL_EFFECTIVE_DATE` **13 октября 2026** заменить датой релиза вместе
   с legal-pages.spec. Затем проверить страницу политики, каталог/поиск/автора/sitemap.
5. После того как 1.6 реально доступна, отдельно включить `LEARNING_COMMUNITY_EVENTS_ENABLED=true`,
   проверить storage/maintenance/профиль/сброс, затем `LEARNING_COMMUNITY_FOR_YOU_ENABLED=true` и
   полку/разнообразие/свои копии/объяснения. Запись и полка по умолчанию false; эти environment
   строки добавить в production compose и установить на хост **до** соответствующего prod approval.
   Повторить export/evaluation на сопоставимых анонимных production-логах, не утверждая causal lift.
6. Опубликовать handoff/evidence в #421/#422 и задачах. Перевести завершённые #447–#459 в Done и
   закрыть эпики #421/#422 только после merged/applied behavior и проверки поставки. #462 не делать
   раньше **2026-10-24**, только в отдельно разрешённом GitHub/production scope. Не считать #409 закрытым.

**Локальный стенд владельца.** Финальный runtime запускается из `local/epic4` после проверок, сохраняя
`MNEMA_LOCAL_PROJECT_NAME=mnema-local-v2`, `MNEMA_LOCAL_STATE_DIR=/Users/yuzuru/.local/share/mnema/local-full-stack`,
`MNEMA_LOCAL_OAUTH_ENV_FILE=/Users/yuzuru/Projects/Mnema/.env`. `MNEMA_NPD_RECEIPTS=OFF` обязателен.
Для локальной пробы включены public-routes, forks, public-media, catalog, events, for-you и
`MNEMA_LOCAL_COMMUNITY_LAUNCHED=true`; production defaults не включались. Тома не удаляются.
После start — launcher smoke и providers endpoint (google/yandex/github). Результат финального запуска
и source-bound receipt фиксируются в итоговом отчёте владельцу.
