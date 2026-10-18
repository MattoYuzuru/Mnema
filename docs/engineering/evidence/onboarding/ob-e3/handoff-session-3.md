---
artifact:
  id: onboarding-handoff-session-3
  type: handoff
  title: "Onboarding S3: OB-E3 — «Путь Mnema»"
  status: current
  created_at: "2026-10-18"
  owners: ["project-owner"]
---

# Handoff S3 — OB-E3 «Путь Mnema»

Сессия S3 по [prompts](../../../onboarding/prompts.md#s3--ob-e3-путь-mnema): OB-301…OB-305
([эпик](../../../onboarding/epics/ob-e3-path.md), Issues #531–#536), [ADR-ONB-002](../../../onboarding/adr/adr-onb-002-milestones.md).

## База и HEAD

| Поле | Значение |
|---|---|
| Ветка | `feat/531-onboarding-path` (worktree `.claude/worktrees/ob-e3-path`) от `origin/main` 5c432a9b |
| Код | 1d7eef39…(review-2 fixes) — см. `git log 5c432a9b..`; handoff и progress — последний коммит PR, только документы |
| Ведущий / саб-агенты | Claude Opus 5.5 (integrator: контракт, V74, документы, интеграция backfill, harness, коммиты). Research точек записи — Opus 5.5 medium (Explore); backend OB-301/302 — Sonnet 5.5 high; frontend OB-303/304 и сценарии harness — Sonnet 5.5 high; review — 2 × Opus 5.5 high (`sde-agent:implementation-reviewer`). Не больше двух одновременно. Оба исполнителя были остановлены владельцем перед финальными прогонами; оставшееся (gate backfill, исправление плана, harness) доделал ведущий |
| LC-E1/E2 | Не влиты: `StudySessionRepository`/`AttemptRepository` с 2026-10-14 менял только cf68b152 (Сообщество); writers не дублируются |

## Проверка предыдущей работы (S1, S2) — «чистовая»

- PR #554 влит squash-коммитом 1e4f764e (2026-10-17); исходные SHA ветки `feat/517-onboarding` в main не входят (ветка удалена) —
  записано в progress. Строка progress «Последний handoff … ветка не запушена» устарела — исправлена.
- `docs/README.md`, `onboarding/README.md`, architecture (шапка) говорили «не реализовано / не влита» — исправлено.
- Kanban: эпики #520 и #526 стояли в Backlog при закрытых задачах — переведены в In review (влиты за флагом, есть открытые AC).
- Main CI для 5c432a9b ждёт approval окружения `prod` — решение владельца, не трогал.
- **Пробел S1, найден review S3:** утверждённый текст опроса обещает «изменить их можно в профиле», но в профиле не было блока цели —
  добавлен блок «Цель» (см. OB-303). Опыт и дни в неделю в профиле по-прежнему нельзя менять — это OB-401.
- Открытые AC S1/S2 (VoiceOver, resize/scroll, purge #409, «ИИ-бюджет» в браузере, OB-003 AC 2) — без изменений.

## Что сделано по ID

| ID | Результат | Коммиты |
|---|---|---|
| OB-301 | Реестр 13 вех ([`milestones.json`](../../../../../contracts/onboarding/milestones.json), `MilestoneKey`); V74 `account_milestone` + CHECK `notification.route` с `PATH` + индекс `study_session_study_dates`; `MilestoneRecorder` (`reach`/`increment`/`reachInUserDeck`/`sessionCompleted`, MANDATORY, одно выражение; цель 1 — `ON CONFLICT DO NOTHING`); `PATH_MILESTONE` в `beforeCommit` только при `learning.onboarding.enabled`; writers в 11 доменных местах; `milestones`/`path` в `GET /api/onboarding`; inventory маршрутов и скан writer'ов; [горячий путь](hot-path.md) | 09953172, 870a8117 |
| OB-302 | Задача `onboarding.backfill` (выключена по умолчанию), слияние без понижения и без уведомлений, курсор, try-lock, индексные планы; [evidence](backfill.md) | 870a8117 |
| OB-303 | `/profile/path`, `app-path-milestone` (сначала в стайлгайде), блок в профиле, блок «Цель», тосты вех (`PathToasts`), неизвестный `route` → `NONE`, подсказка на месте без вуали с fallback 4 с, переходы по route key | d0519bdd, d550caa4 |
| OB-304 | Четыре клиентских квеста на движке тура (не пишут journey), выбор колоды, проверки возможностей и бюджета до старта, экран микрофона до записи; квест «Картинка» открывает редактор с `ordinal` (найдено браузером) | d0519bdd, d550caa4, 181794c1 |
| OB-305 | 2 независимых review, исправления, real-browser harness `--onboarding` с четырьмя этапами «Пути» ([results](path-results.json), [a11y](path-a11y.json), снимки `onboarding-path-*.png`), полный gate | 181794c1 |

## Отклонения от канона и почему

Записаны в [architecture §14](../../../onboarding/architecture.md#14-уточнено-реализацией-s3-ob-e3-2026-10-18) и
[proposal «Уточнено реализацией (S3)»](../../../onboarding/proposal.md#уточнено-реализацией-s3-ob-e3-2026-10-18):

- **Уведомление в `beforeCommit`**, не сразу после upsert: `NotificationPublisher` требует быть последней записью транзакции.
- **Backfill сливает, а не `DO NOTHING`** (§5): иначе LIVE-строка с меньшим прогрессом не догонит историю.
- **`CAPTURE_NOTE` — стадии** (сохранена / разобрана: превращена в материал или в архиве), **`TWO_DAYS` — даты**; `RHYTHM_WEEK` скрыта
  до OB-401 (нет writer'а). Сейчас в production-конфигурации видно 8 вех (ИИ выключен, каталог закрыт).
- **Скрытые вехи** записываются, но не объявляются и не входят в «N из M»; вехи, достигнутые при выключенном флаге, не объявляются.
- **Новый индекс в V74** (`study_session_study_dates`, частичный): проба второго дня на большой таблице иначе шла seq scan;
  индекс же нужен ритму (architecture §7).
- **«Скрыть «Путь»» и «Новое»** — настройки устройства (localStorage), не сервера.
- **`AttentionCoordinator`**: новая поверхность `hint` между `first-entry` и `promo`.
- **Квесты**: голос — через микрофон заметки (у клиента нет способа гарантированно получить голосовое задание в занятии);
  «Объяснить» не знает заранее, проверит ли ИИ упражнение (честный текст шага). Бюджет до старта клиент считает по `GET /api/usage`.

## Решения владельца (не блокируют merge за флагом)

Предложены в чате 2026-10-18; по умолчанию принято, как выше. Нужны до PR включения флагов:

1. `VOICE_ANSWER` за любое распознавание (не только ответ в занятии).
2. Скрытые ИИ-вехи без тостов задним числом после включения ИИ.
3. «Скрыть «Путь»» только на устройстве.
4. Запись фактов вех в production с момента деплоя (до вступления политики 1.7), без показа; backfill — в PR включения.
5. Квест «Объяснить» может не дойти до вехи, если в колоде нет упражнения с проверкой ИИ (нужен серверный признак — отдельная задача).
6. **72 новые строки** — [список](texts-for-approval-s3.md); плюс 2 строки S1 и L4 (шаблон на `LINK`) из S2.

## Независимое review

- **Первое (5c432a9b..d0519bdd)**: 1 high, 2 medium, 13 low — все исправлены (d550caa4, 870a8117): H1 цель-тупик → блок «Цель»; M1
  неизвестный ключ вехи больше не ломает вид; M2 квесты голос/объяснение; L1 блокировка строки на no-op → `DO NOTHING` для цели 1; L2–L3
  тесты inventory/scan строже; L4 evidence-тест горячего пути — opt-in; L6 видимость «Готовой колоды» по флагам копии; L7–L9, L13 —
  frontend; L10 «медиа появилось»; L11 — контракт приведён к поведению; L12 — тесты REPLAY и отказа.
- **Второе (d0519bdd..181794c1)**: 0 blockers/high, 4 medium, 10 low. Исправлено: M-A backfill больше не держит блокировки неизменяемых
  строк (они не попадают во вставку) и делает одну пачку на транзакцию (нет цикла блокировок с живыми writer'ами); M-B пройденная
  версия реестра записывается в `onboarding_backfill_run` (V74) — после очистки строки задачи backfill не повторяется; M-C harness
  ждёт, что клиент получил уведомление, прежде чем считать «тишину»; M-D фокус в блоке «Цель» не теряется; L1 «Готовая колода» в
  backfill — по purpose самой копии; L2 индекс в V74 строится до блокировки `notification`, комментарий исправлен; L5 harness строже
  (`?ordinal=` обязателен, рамка в forced colors); L10 javadoc. Записаны как известные: L3 заголовок в `<summary>` может не читаться
  VoiceOver (AC скринридера открыт), L6 повторная вставка того же медиа проходит шаг квеста, но вехи не даёт, L7 `GOAL_TOLD` по истории
  берёт время последнего ответа, L8 `slice-budget` не сверяется с `lease` очереди (по умолчанию 3 с ≪ 60 с), L9 гонка backfill с живым
  событием исправляется следующим событием.

## Результаты gate

См. раздел ниже (заполнен после прогона на точном коммите).

## Миграции и rollback

V74: таблицы `account_milestone` и `onboarding_backfill_run`, частичный индекс `study_session_study_dates` (строится первым, в
транзакции миграции, `lock_timeout 5s`; `study_session` в production мала), затем замена CHECK `notification.route` (скан таблицы
уведомлений под коротким lock). Всё additive.
Rollback поведения — `learning.onboarding.enabled=false` (нет «Пути», нет `PATH_MILESTONE`; запись вех продолжается и безвредна);
backfill — `learning.onboarding.backfill.enabled=false` / `learning.jobs.onboarding.backfill.enabled=false`. Откат образа на версию
до V74 безопасен: Flyway игнорирует будущую миграцию, старый клиент читает `route` как строку, а неизвестный kind не показывает.
Production-флаги не менялись (по умолчанию off).

## Что не проверено

- VoiceOver/NVDA для «Пути», подсказки и квестов; квесты на 390/320 px; полное прохождение любого квеста до вехи в браузере
  (в браузере — старт, шаг, пауза и выход квеста «Картинка»; завершение по событию — specs).
- Сводный тост «отмечено N пунктов» в браузере (только specs); тост вне занятия — только через сценарий занятия.
- Backfill на объёме H (синтетика 3000 аккаунтов, время на H — экстраполяция).
- Retained local stack владельца на этой ветке не перезапускался (harness — одноразовый).

## Статус

OB-301…OB-305 — merged behind flag после merge этого PR (до merge — local complete, delivery pending). Открытые AC: OB-303 «скринридер»,
OB-304 «end-to-end с вехой» и «a11y/mobile» — см. task-файлы.

## Следующий шаг

S4 (OB-E4): OB-401 «Ритм» — writer `RHYTHM_WEEK` в транзакции завершения занятия (вызов `MilestoneRecorder.reach` рядом с
`sessionCompleted`, чтение дат по индексу `study_session_study_dates`), снять скрытие `RHYTHM` в `PathVisibility` и фикстуре, добавить
опыт и дни в неделю в профиль; затем OB-402 награда за 100 % видимых вех.
