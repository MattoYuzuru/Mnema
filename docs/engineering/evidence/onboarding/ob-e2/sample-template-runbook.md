---
artifact:
  id: onboarding-sample-template-runbook
  type: runbook
  title: "Onboarding: install and update the sample deck template"
  status: current
  created_at: "2026-10-17"
  owners: ["project-owner"]
---

# Шаблон учебной колоды: установка и обновление

Шаблон «Как устроена память» — обычная колода аккаунта редакции, собранная MnemaDeckStudio
(`/Users/yuzuru/Projects/MnemaDeckStudio/runs/onboarding-memory-sample`, студия вне git). Поведение — [ADR-ONB-003](../../../onboarding/adr/adr-onb-003-sample-deck.md),
контракт — [`contracts/onboarding`](../../../../../contracts/onboarding/README.md), настройки — [runtime policy index](../../../runtime-policy-index.md).

## Установка (local stack)

1. Стек запущен с `LEARNING_ONBOARDING_SAMPLE_DECK_ENABLED=true` и, для ИИ-заданий, с заглушками ИИ
   (`LEARNING_AI_PROVIDER=stub`, `LEARNING_FEATURES_AI_ASSESSMENT_ENABLED=true`, `LEARNING_FEATURES_SPEECH_TO_TEXT_ENABLED=true`).
   Без них студия пропускает ИИ-задания и импортирует их самопроверки (вариант A).
2. Токен редакции: локальный аккаунт редакции хранится в `~/.local/share/mnema/local-full-stack/onboarding-editorial-account.json`
   (0600); свежий токен — PKCE-вход по образцу `scripts/local-full-stack/smoke.py`, в файл 0600 для `MNEMA_ACCESS_TOKEN_FILE`.
   `studio whoami` должен вернуть UUID из `plan.json`.
3. Студия: `validate` → `approve` (по решению владельца, с цитатой) → `prepare`/`submit`/`poll` (озвучка) → `publish` (PRIVATE).
4. `LEARNING_ONBOARDING_SAMPLE_DECK_TEMPLATE_ID=<deckId>` и перезапуск стека, пока колода ещё **PRIVATE**: Learning помечает
   её `SAMPLE_TEMPLATE` (PUBLIC/INVITE-колоду не помечает и пишет ERROR). Затем владелец публикует шаблон на `LINK`
   (`PUT /api/decks/{deckId}/publication`) — после пометки другие уровни и копия по коду запрещены, поэтому обычных копий
   шаблона не появится.
5. Проверка: `GET /api/onboarding` нового аккаунта даёт `sampleDeck.available=true`; `POST /api/onboarding/sample-deck` — копия;
   `runs/base-tech-shared/verify_live_generic.py` — readback, preview и настоящее PRACTICE по шаблону и копии.

## Обновление шаблона

Редакция меняет pack в студии и публикует новую ревизию той же колоды (тот же deckId, уровень `LINK`). Копии
пользователей получают обычное «Есть обновление» (CD-5) и сами решают, принять ли его; ничего не применяется
автоматически. Новые копии делаются уже от новой ревизии. Сменить сам шаблон — новый `template-id` и перезапуск;
прежний остаётся `SAMPLE_TEMPLATE` (не шарится), его копии продолжают работать.

**Не удалять шаблон:** удаление необратимо, все копии теряют обновления, ветка «На примере» пропадает до нового шаблона.

## Факт установки

2026-10-17, local stack `mnema-local-v2` на ветке `feat/517-onboarding`: deck `d59520c8-d144-49f5-910b-4aaf833b8434`
(«Как устроена память», 11 материалов, 31 упражнение, 2 ИИ-задания, 7 медиа), владелец — локальный аккаунт редакции
`mnema_editorial_1a434184`, уровень `LINK` (код `xLZLdhEjrd`), помечен `SAMPLE_TEMPLATE`; digest плана `16d97a2b…dd0b`, pack
`9385c786…`, TTS $0.005241; проверка `PASSED_RUNTIME_API`. Подробности — [handoff S2](handoff-session-2.md).
