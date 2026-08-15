---
artifact:
  id: v2-delivery-plan-2026-08
  type: implementation-plan
  title: "Mnema v2 delivery plan, August 2026"
  status: proposed
  created_at: "2026-08-15"
  updated_at: "2026-08-15"
  owners: ["project-owner"]
---

# Реалистичный план Mnema v2

## Ответ по срокам

Период 17–31 августа полностью отдаётся staging foundation и проверенному v2 vertical slice. Quotas, billing domain и T‑Bank начинаются только после staging go/no-go. Это снимает искусственную гонку между content rewrite и деньгами и позволяет сначала сделать delivery path, который не ломает production.

Ориентиры:

| Горизонт | Реалистичный outcome |
|---|---|
| 17–20 августа | GitHub/CI/CD governance, reproducible release, staging namespace/access, backup/restore contract и test harness |
| 21–27 августа | новый content contract, editor/preview, immutable revision API, Browse и basic media в staging |
| 28–31 августа | reveal/self-rating, typed answer, security/a11y/E2E/load gates, account rehearsal и staging demo |
| 1–7 сентября | provider-neutral plan/entitlement/trial/quota/usage domain; без реальных денег |
| 8–14 сентября | T‑Bank sandbox, human legal/IP tasks и payment E2E при готовых bank credentials/documents |
| 4–6 недель | узкая hosted beta с production payment после legal/bank gates, managed text AI, account-only cutover, observability/restore/load evidence |
| ещё 8–12+ недель после P0 | существенная часть collaboration/live updates, P1/P2 exercises, robust Anki compiler, offline/mobile sync и self-host separation |

Агенты ускоряют boilerplate, tests и независимые lanes, но не отменяют contract decisions, integration, migration rehearsal, accessibility/security review, банковский onboarding и юридические сроки.

## Почему неделя — это vertical slice, не rewrite

Текущий checkout содержит примерно 59,5 тыс. строк backend production-кода, 40,6 тыс. строк frontend TypeScript, шесть backend-сервисов, 50 Flyway migrations и около сотни eager frontend components/routes. Core построен вокруг `deck/template/card/review`; AI service сам по себе около 33 тыс. строк. Одновременная смена content model, editor, exercises, media, AI, billing, deployment и production data в одном cut создаёт не скорость, а непроверяемую интеграцию.

Размер P0 оценён в 16 / 29,5 / 49 agent-days для best/likely/safe scenarios. Три параллельных агента дают практическое ускорение примерно 2–2,4×, не 3×, потому что contracts, Flyway numbering, dependency changes и merge order сериализуются у одного интегратора.

## P0 cut: только сквозной учебный цикл

### Входит 17–31 августа

1. Сначала delivery foundation: protected PR flow, non-cancellable staging deploy, immutable release identity, post-deploy smoke и rollback/restore contract.
2. Staging namespace/host access и PostgreSQL 18 + MinIO E2E harness.
3. Существующий account/auth contract и rehearsal account-only export/import.
4. Приватная колода.
5. `learning_item` + immutable `item_revision(document JSONB)`.
6. Один простой authoring preset «вопрос/ответ», создающий native AST и objective без возврата templates/fields.
7. Узлы: text/paragraph/heading/list, ruby, math, code, image/audio; Mermaid только если security fixtures зелёные.
8. Desktop editor + live preview справа; mobile edit/preview switch.
9. Browse.
10. Reveal + self-rating.
11. Short typed answer с детерминированной нормализацией.
12. Basic media upload/reference с ACL и MinIO contract test.
13. Новый `/v2` API/route boundary; legacy data не преобразуются.
14. Renderer security fixtures, accessibility verification и первый load baseline.

### Stretch после зелёных gates

- cloze;
- несколько objectives у одного item;
- Mermaid;
- media dedup race handling.

### Не входит

- Anki import/compiler;
- public decks, merge/live updates и coauthors;
- production AI generation;
- production payments;
- offline/mobile sync;
- Kotlin migration и service consolidation;
- Angular major upgrade;
- production reset.

## Критический путь

```text
GitHub/CI/CD + staging access + restore contract
  → editor spike + dependency permission
  → AST schema + golden fixtures + renderer security policy
  → PostgreSQL v2 model + immutable revision API
  → editor/renderer end-to-end
  → objective + exercise definition
  → idempotent attempt + schedule update
  → PostgreSQL/MinIO E2E + a11y/security/load gates
  → account rehearsal + release safety
  → staging demo
```

`Golden fixtures/eval` — это обычный замороженный набор тестовых примеров с ожидаемым результатом. Для editor/renderer он проверяет ruby, RTL, math, code, media и XSS. Для AI он сравнивает модели на одних и тех же prompts по качеству, валидности JSON, цене принятого результата и p95 latency. Это не отдельная модель и не магия.

## План 17–31 августа

| Период | Обязательный результат |
|---|---|
| 17 августа | Freeze P0 и staging target; GitHub/CI/CD audit превращён в первый implementation epic |
| 18–20 | PR/release/staging contract, deployment inventory, PostgreSQL/MinIO harness и editor spike |
| 21–23 | AST schema/API, safe renderer и editor/preview prototype |
| 24–27 | create/edit/publish/browse end-to-end; auth/account path и basic media |
| 28–29 | reveal/self-rating, typed answer, attempt idempotency, ruby/RTL/math/code/media |
| 30 | full quality, renderer adversarial corpus, a11y, blackbox E2E и load baseline |
| 31 | staging demo; account/restore evidence; go/no-go на billing iteration; production не сбрасывать |

## После 31 августа

### 1–7 сентября — billing core без денег

- `plan`, `entitlement`, `trial`, `quota_grant`, `usage_ledger`;
- Free + одноразовый 14-day Trial без карты + Starter 299 ₽;
- reservation/commit/refund для AI credits;
- fake payment adapter и idempotent webhook state machine;
- quota/paywall/usage UI и cost telemetry.

### 8–14 сентября — bank/legal sandbox

- human epics по ИП/НПД, оферте, consent и Роскомнадзору;
- T‑Bank test terminal и hosted form;
- recurrent consent, webhook, cancellation, refund и receipt reconciliation;
- production payment остаётся выключенным до legal/bank gate.

## Параллельные lanes

### A — backend/data

Schema, revision API, optimistic concurrency, objectives/exercises/attempts, deck-scoped query, validation/errors.

### B — frontend

Изолированные `/v2` routes, editor/preview, safe renderer, Browse, reveal/typed, mobile/RTL/keyboard/a11y.

### C — platform/verification

GitHub/CI/CD hardening, staging access, PostgreSQL/MinIO E2E, media security, account rehearsal, load harness и DeepSeek eval.

Один интегратор владеет persisted contracts, Flyway numbering, dependency files, merge order и release configuration. Одновременно не более трёх delivery lanes; каждая задача 1–3 дня и отдельный reviewable PR.

## DeepSeek spike

Владелец исключил Yandex AI из product direction. Direct DeepSeek V4 Flash/Pro входит в eval вместе как минимум с одним не-Yandex fallback, например GigaChat. Официальный API OpenAI-compatible; текущие цены и peak/off-peak окна меняются, поэтому CI benchmark хранит timestamped price snapshot, а quota считает cache-miss peak как conservative case ([DeepSeek pricing](https://api-docs.deepseek.com/quick_start/pricing/)).

Проверить до production:

- регистрацию, пополнение и бухгалтерские документы для российского ИП;
- latency/429/5xx из production region, deadlines и circuit breaker;
- structured JSON/AST validity и cost per accepted item;
- cross-border data flow, API opt-out и retention;
- отсутствие PII в prompt и opaque `user_id`;
- fallback, потому что Terms дают API `as is/as available` без российской SLA-гарантии ([Open Platform Terms](https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html)).

Text LLM не закрывает image generation, STT, TTS и video: это отдельные adapters, providers и quota weights.

## Go/no-go gates

### 31 августа

`GO` на billing iteration, если staging delivery, P0 content/study loop, restore/account rehearsal и quality gates зелёные. `NO-GO` на production reset или payment integration, если delivery remains cancellable/non-rollbackable.

### После 14 сентября

Реальные платежи включаются только после T‑Bank sandbox→production evidence и [legal launch gates](../product/russia-legal-launch-checklist-2026.md). Direct DeepSeek не является единственным provider и не блокирует manual learning.

### Production beta

- immutable revisions и concurrency proven;
- renderer не исполняет raw JS/CSS и проходит XSS corpus;
- Study строго deck-scoped, attempt idempotency proven;
- shared media ACL/dedup/GC proven на MinIO и production-compatible storage;
- webhook/entitlement/quota/refund exactly-once effects proven;
- account export count/hash и restore rehearsal подписаны;
- backend quality/tests, frontend lint/test/build, E2E, accessibility, security/load review зелёные;
- observability, backup/restore и point-of-no-return runbook готовы.

## Что считать успехом двух недель

Не число сгенерированных файлов и не процент удалённого legacy-кода. Успех — один пользователь в staging создаёт rich item, видит точное preview, изучает его двумя способами, безопасно повторяет запрос и загружает media через воспроизводимый, проверенный и откатываемый delivery path. Billing ещё не обязан существовать. Этот сквозной срез снижает риск следующей итерации; «полная перепись» без таких доказательств его увеличивает.
