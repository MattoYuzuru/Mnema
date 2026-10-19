---
artifact:
  id: onboarding-handoff-session-4
  type: handoff
  title: "Onboarding S4: OB-E4 — weekly rhythm and Path reward"
  status: current
  created_at: "2026-10-19"
  owners: ["project-owner"]
---

# Handoff S4 — OB-E4 «Ритм и награда»

Сессия S4 по [prompts](../../../onboarding/prompts.md#s4--ob-e4-ритм-и-награда): OB-401…OB-403
([эпик](../../../onboarding/epics/ob-e4-rhythm-and-reward.md), Issues #537–#540), [ADR-ONB-004](../../../onboarding/adr/adr-onb-004-rhythm.md),
[ADR-ONB-005](../../../onboarding/adr/adr-onb-005-path-reward.md).

## База и HEAD

| Поле | Значение |
|---|---|
| Ветка | `feat/537-onboarding-rhythm-reward` (worktree `.claude/worktrees/ob-e4`) от `origin/main` 1f708643 (squash #561, OB-E3) |
| Код | 3a0675f9…ca27aac7 (`git log 1f708643..`); handoff, progress и AC — последний коммит PR, только документы |
| Ведущий / саб-агенты | Claude Opus 5.5 (integrator: контракты, V75/V76, документы, harness, коммиты, перенос диффов). Research промокодов и Free-лимитов — Opus 5.5 medium (Explore); OB-401 backend и frontend — 2 × Sonnet 5.5 high параллельно; OB-402 backend (в отдельном worktree от коммита OB-401) и frontend — 2 × Sonnet 5.5 high; review — 2 × Opus 5.5 high (`sde-agent:implementation-reviewer`, backend и frontend); исправления review — 2 × Sonnet 5.5 high. Не больше двух одновременно |
| Main CI для 1f708643 | Всё зелёное до `deploy-production`, который ждёт approval окружения `prod` — решение владельца, не трогал |
| LC-E1/E2 | Не влиты (proposed); writers занятия не дублируются |

## Проверка предыдущей работы (S3) — «чистовая»

- 1f708643 в HEAD; owning tests S1–S3 (`onboarding.*`, `*Milestone*`, `*Backfill*`) на базе: 171 тест, 0 падений, 1 opt-in пропуск
  (`MilestoneHotPathEvidenceTest`); frontend-тесты базы — Main CI `frontend-tests` для 1f708643 зелёный.
- progress: OB-301…OB-305 стояли «local complete, delivery pending» при фактическом merge — исправлено на `merged` (squash 1f708643);
  `docs/README.md`, `onboarding/README.md` («OB-E3 — ветка…», двойной `updated_at` во frontmatter) — исправлено (3a0675f9).
- Kanban: #531 In review (влит за флагом, открытые AC), #532–#536 Done — верно; #537–#539 переведены в In progress.
- TODO без владельца нет (все — `#409`). Миграции: V75/V76 свободны в main и в открытых PR.
- Открытые AC S1–S3 (VoiceOver, квесты end-to-end/390 px, purge #409, OB-003 AC 2) — без изменений.

## Что сделано по ID

| ID | Результат | Коммиты |
|---|---|---|
| OB-401 | Ритм за `learning.onboarding.rhythm.enabled`: `WeeklyRhythm` в транзакции завершения (рядом с `MilestoneRecorder.sessionCompleted` в `StudySessionRepository.complete` и `AttemptRepository.completeSessionIfTerminal`), SCHEDULED/PRACTICE, ISO-неделя по сохранённой `local_study_date`, advisory-lock + `last_met_week` (раз в неделю при гонке), веха `RHYTHM_WEEK` (снято скрытие в `PathVisibility` и фикстуре), `rhythm` в `GET /api/onboarding`, `PUT /preferences` без строки; V75 (`NOT_OFFERED`-строки journey). Frontend: `app-rhythm-week`, блок на итогах занятия, блок «Недельный ритм» в профиле (цель и опыт — закрыт пробел S1), «Недель в ритме» только в профиле, тихий тост по политике S3; формулировки exercise catalog и Study про streak уточнены | adf8824f, b99f28d9, e9433e2c, 15a5d06c, d0fa355e |
| OB-402 | Награда за `learning.onboarding.reward.enabled`: кампании `PATH_REWARD` в `/manage/promos` (создание выключенной, одна включённая, журнал `PROMO_CAMPAIGN_*`, список выдач без аккаунтов), персональный код (`promo_code.recipient_id`, погасить может только получатель, открытый код не хранится — HMAC из id кода), `onboarding_reward` (PK `account_id`), выдача при чтении «Пути» verified-аккаунту на 100 % видимых вех, 7 дней, `PATH_REWARD_READY`, дневной лимит, метрика; V76. Frontend: поздравление (ночь S1, один раз на устройстве, поверхность координатора `reward`), карточка подарка в «Пути» и профиле, промокод с копированием, `/plans?promo=` (параметр убирается после заполнения) | 040dd5a3, 65cc0a09, be2a9441, 15a5d06c, d0fa355e |
| OB-403 | 2 независимых review и исправления; harness `--onboarding` с новым этапом `rhythm` ([results](onboarding-results.json), снимки `onboarding-rhythm-profile-*.png`); полный gate | ca27aac7 (+ этот коммит) |

## Отклонения от канона и почему

Записаны в [proposal S4](../../../onboarding/proposal.md#уточнено-реализацией-s4-ob-e4-2026-10-19), [architecture §15](../../../onboarding/architecture.md#15-уточнено-реализацией-s4-ob-e4-2026-10-19)
и уточнениях ADR-ONB-004/005:

- **Награда выдаётся при `GET /api/onboarding`, а не в транзакции последней вехи** (ADR-ONB-005, промпт S4). `mnema_trusted` есть только у запроса;
  последняя веха часто достигается в worker (проверка ИИ, распознавание), а новый аккаунт проходит «Путь» раньше, чем станет trusted (3 дня), — выдача в
  транзакции вехи отказала бы ему навсегда. Клиент перечитывает вид после каждого `PATH_MILESTONE`. Выдача — до транзакции чтения, своей транзакцией,
  под lock аккаунта (гонка последних вех и чтений → один код, `UNIQUE(account_id)`). Продуктовое поведение («системно, при 100 %, один раз») то же.
- **`NOT_OFFERED` хранится** (V75): ритм живёт в `onboarding_journey` (§3), а у старых аккаунтов строки не было — их цель нельзя было менять, недели
  считать. На проводе такая строка — «нет строки».
- **Недели считаются с момента включения флага** — история не пересчитывается (цель прошлой недели неизвестна); `RHYTHM_WEEK` без backfill.
- **Правило «100 %»** однозначно: 100 % видимых вех; на Free видимые ИИ-вехи достижимы в бесплатных лимитах (research: 50 проверок/мес, 60 мин STT,
  50 кредитов), скрытые оператором не нужны — вопрос владельцу не понадобился.
- **Код действует с минуты до выдачи** (расхождение часов БД и приложения), до `issued_at + 7 дней`.
- **AI-проверка берёт lock ритма до оплаты** (`AssessmentService`): учёт может опубликовать `USAGE_*` посреди транзакции; иначе — deadlock с писателем ритма.

## Решения владельца (не блокируют merge за флагами)

Нужны до PR включения флагов:

1. **Новые строки S4** — 24 ритма и 8 награды + строки кабинета: [список](texts-for-approval-s4.md). Строка тира изменена после review на
   «Ваш подарок: {plan} на {days}. Активировать до {date}.» (иначе дата читалась как конец тарифа).
2. Награда в production: создание кампании (−10 % по умолчанию владельца) и включение флага — после OB-002 (текст и условия, юрист).
3. Поздравление показывается один раз **на устройстве** (на втором устройстве — ещё раз, пока подарок действует) — принять или ограничить.
4. «Недель в ритме: 0» не показывается — принять.
5. Открытые решения S3 (72 строки, `VOICE_ANSWER` за любое распознавание, скрытые ИИ-вехи без тостов, «Скрыть «Путь»» на устройстве, L4 LINK,
   две строки S1) — ответов в Issues и чате нет, остаются открытыми.

## Независимое review

- **Backend** (Opus high): 0 blocker, 1 high, 2 medium, 5 low. Исправлено (d0fa355e): H1 выдача была вложена в транзакцию чтения (два соединения на
  чтение, возможный 500 под нагрузкой) — теперь до неё, с проверкой без блокировок, тест пула (`PathRewardConnectionsIntegrationTest`); M1 ADR не
  обновлён — добавлены уточнения S4; M2 порядок блокировок ритма в пути ИИ-проверки (deadlock 40P01) — lock до оплаты, тест; L2 кампания
  перечитывается `FOR SHARE`; L3 индекс списка промокодов по порядку запроса (V76). Известные: L1 истечение по часам БД (вид) и JVM (погашение) может
  расходиться на секунды у самого конца срока; L4 CHECK в V76 валидируются под коротким lock (таблицы малы, `lock_timeout 5s`); L5 смена секрета
  промокодов скрывает активный подарок навсегда (документировано).
- **Frontend** (Opus high): 0 blocker/high, 3 medium, 10 low. Исправлено (15a5d06c): M1 форма кабинета сбрасывала тариф после отказа; M2 невидимые
  кольца фокуса на ночи; M3 строка тира; L1 параметр `promo` убирается с `/plans`; L2–L3 возврат фокуса и `(close)` диалога; L6 живая область блока ритма;
  L8–L9. Известные: L4 «один раз на устройстве» (решение владельца 3), L5 тост `PATH_REWARD_READY` и поздравление почти одновременно, L7 вкладка с
  прошлой недели не даёт тоста цели.
- Предсуществующее (S3, не трогал): `MilestoneRecorder` в пути ИИ-проверки пишет вехи после `USAGE_*`, тот же класс deadlock'а возможен для строк вех.

## Результаты gate

Команда: `python3 scripts/checks.py run` (кандидат в отдельном worktree, `agent-dev`, блокировка `heavy`, JDK 25.0.4.1, Node 24.21.0, Colima), затем
`python3 scripts/checks.py verify`.

| Кандидат | Итог | Что нашёл |
|---|---|---|
| ca27aac7 | `docs` failed: ссылки на этот handoff, которого ещё не было | ожидаемо; остальные проверки не запускались |
| 66dafad4 | 7 из 8 проверок passed; `backend-quality` failed: 3488 тестов, 3 падения | инвентарные тесты, которые не входили в targeted-прогоны агентов: история миграций (V75, V76), реестр контроллеров и маршрутов `/admin` (`promoCampaignController`), список видов уведомлений в `GenerationContractFixtureTest` (`PATH_REWARD_READY`) — тесты дополнены |
| HEAD этого PR | см. описание PR (`checks.py run` + `verify` на точном коммите перед push) | — |

По ходу: targeted backend — 517 (OB-401), 361 + 29 (OB-402), 553 (исправления review) тестов, 0 падений; frontend полный прогон — 4964 теста,
пороги покрытия выполнены; harness `--onboarding` — 20 этапов passed (d0fa355e + этап `rhythm`). Предупреждения: сборка frontend — «not ESM» из
`mermaid` (как в main).

## Миграции и rollback

- **V75** — `onboarding_journey`: CHECK состояния с `NOT_OFFERED`, `offered_at` nullable, CHECK `NOT_OFFERED` ⇔ без `offered_at`/когорты,
  CHECK `weeks_met`/`last_met_week` (понедельник). Таблица пуста в production (онбординг выключен).
- **V76** — `promo_campaign`, `promo_code.campaign_id`/`recipient_id` (+ CHECK, частичный индекс списка), `onboarding_reward`, замена CHECK
  `admin_audit.action` (+ `PROMO_CAMPAIGN_*`). `lock_timeout 5s`.
- Rollback поведения — `learning.onboarding.rhythm.enabled=false` (ничего не пишется, `rhythm: null`, веха скрыта) и
  `learning.onboarding.reward.enabled=false` (нет выдачи и `reward: null`); выключение кампании — выключатель владельца. Выданные коды не отзываются.
  Откат образа до V75/V76 безопасен: старый код не читает новые колонки и таблицы; `NOT_OFFERED`-строки старый код прочёл бы как неизвестное
  состояние — но они появляются только при включённом ритме, а он выключен по умолчанию.
- Local stack (`compose.local-full-stack.yml`) включает оба флага; production-флаги не менялись.

## Что не проверено

- Награда в реальном браузере end-to-end (нужен trusted-аккаунт ≥ 3 дней — в одноразовом harness его нет): только specs и real-DB/HTTP тесты;
  кабинет кампаний в браузере не проверялся (specs).
- Ритм на итогах занятия в мобильной ширине и тост цели недели в браузере (specs); VoiceOver/NVDA.
- Retained local stack владельца на этой ветке не перезапускался.

## Статус

OB-401…OB-403 — local complete, delivery pending; после merge этого PR — merged behind flags. Открытые AC: OB-E4 «полный gate» закрывается этим PR.

## Следующий шаг

PR включения флагов онбординга в production после OB-002: дата вступления политики 1.7, `learning.onboarding.enabled`/`eligible-from`,
`rhythm.enabled`, решение по `reward.enabled` и созданию кампании, утверждение строк S1–S4.
