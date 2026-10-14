---
artifact:
  id: community-decks-epic-3-handoff-1
  type: handoff
  title: "Handoff: эпик 3, сессия 1"
  status: current
  created_at: "2026-10-12"
  updated_at: "2026-10-12"
  owners: ["project-owner"]
---

# Handoff: эпик 3, сессия 1

Текст для комментария в эпике [#421](https://github.com/MattoYuzuru/Mnema/issues/421): GitHub был недоступен (аккаунт заблокирован), поэтому
handoff лежит здесь. Когда доступ вернётся, его нужно опубликовать в эпике.

**База.** `origin/main` `ae556ece` (#496). Вся работа — локальная ветка `local/epic3`, коммит `1606e8cb` (worktree `/Users/yuzuru/Projects/Mnema-cd3-int`).
Полный gate и harness `--catalog`, `--community`, `--updates` зелёные на `74ac7dfd`; после него только переключатели локального стека и документы.

**Сделано (локально).** Community/0–8, миграции `V66`–`V69`. Подробности, ревью и проверки — [evidence эпика 3](README.md).
- Community/0 #447: проверка production (7 триграмм кириллицы), `unaccent` установлен в production, `V66`. PR #500 открыт.
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

**Следующий шаг** (когда GitHub доступен):
1. Восстановить `gh auth` и SSH. Отправить ветки и открыть PR по одной задаче или стопкой в порядке: #500 (Community/0) → #448 → #450 → #449/#453/#454 backend → #501 (styleguide)
   → страница (#451/#452) → профиль (#453) → кабинет (#454) → harness и документы (#455). Каждый PR — полный gate на своём коммите и CI. Либо, если владелец согласен, один PR
   эпика из `local/epic3` (после rebase не нужен: merge `origin/main` в ветку, проверить `git diff` по списку файлов).
2. Перед merge обновить ветку с `origin/main`, проверить номера миграций (`V66`–`V69` не должны совпасть с новыми из main) и тест-инвентарь `LearningApplicationIntegrationTest`.
3. Выкатка: сначала Identity и Learning одним релизом (Identity даёт утверждения доверия; при старом Identity копии получают NULL и дозаполняются). Установить на хост
   `deploy/production/nginx.conf` (локация `/sitemap-decks.xml`) до одобрения `prod`. `init-database.sh` на хосте тоже обновить (не участвует в drift-check).
4. Включение (отдельный PR после выкатки и проверки на production): `learning.community.catalog.enabled` — строка `LEARNING_COMMUNITY_CATALOG_ENABLED: "true"` в
   `deploy/production/compose.yaml` (установить на хост до одобрения); `COMMUNITY_LAUNCHED = true` во frontend; `COMMUNITY_ROUTE` в `features/public-deck/public-deck.text.ts`;
   строка `Sitemap: https://mnema.app/sitemap-decks.xml` в `frontend/src/robots.txt`; утверждённые предложения: в политике в «Колоды, которыми вы делитесь» — «Автор публичной колоды
   видит только общие цифры: сколько человек её добавили и сколько учат сейчас.», в соглашении в «Кто видит вашу колоду» — «Публичные колоды появляются в разделе «Сообщество» и в вашем
   профиле.»; поднять `LEGAL_DOCUMENT_VERSION` до 1.5 и дату вступления (`legal-pages.spec.ts`). Затем smoke на mnema.app: `/community`, поиск, профиль, `/sitemap-decks.xml`.
5. Перевести #447–#455 в Done с evidence-комментариями, закрыть эпик #421, перевести в Ready задачи эпика 4, у которых выполнены условия (Recs/1 #456 зависит от Community/4 и Share/16).
6. Попутная #462 — не раньше 2026-10-24 (две недели после запуска GC).

**Локальный стенд.** `mnema-local-v2` перезапущен из worktree `Mnema-cd3-int` (`1606e8cb`) с сохранёнными `MNEMA_LOCAL_STATE_DIR` и `MNEMA_LOCAL_OAUTH_ENV_FILE`,
`MNEMA_NPD_RECEIPTS=OFF`, и с включёнными для пробы публичными маршрутами, копиями, публичными медиа, каталогом и входом «Сообщество» (`MNEMA_LOCAL_COMMUNITY_LAUNCHED=true`).
Тома не удалялись. Провайдеры входа: google, yandex, github. Smoke зелёный.
