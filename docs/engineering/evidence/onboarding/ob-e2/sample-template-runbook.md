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
4. Публикация шаблона на `LINK` его владельцем (`PUT /api/decks/{deckId}/publication`), затем
   `LEARNING_ONBOARDING_SAMPLE_DECK_TEMPLATE_ID=<deckId>` и перезапуск стека: Learning помечает колоду `SAMPLE_TEMPLATE`.
5. Проверка: `GET /api/onboarding` нового аккаунта даёт `sampleDeck.available=true`; `POST /api/onboarding/sample-deck` — копия;
   `runs/base-tech-shared/verify_live_generic.py` — readback, preview и настоящее PRACTICE по шаблону и копии.

## Обновление шаблона

Редакция меняет pack в студии и публикует новую ревизию той же колоды (тот же deckId, уровень `LINK`). Копии
пользователей получают обычное «Есть обновление» (CD-5) и сами решают, принять ли его; ничего не применяется
автоматически. Новые копии делаются уже от новой ревизии. Сменить сам шаблон — новый `template-id` и перезапуск;
прежний остаётся `SAMPLE_TEMPLATE` (не шарится), его копии продолжают работать.

## Факт установки

Заполняется при установке (handoff S2).
