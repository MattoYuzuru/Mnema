---
artifact:
  id: onboarding-github-import
  type: workflow
  title: "Publish the local onboarding backlog without duplicates"
  status: proposed
  created_at: "2026-10-14"
  owners: ["project-owner"]
---

# Перенос онбординга на доску

Тот же порядок, что у [learning core import](../learning-core/github-import.md): live-проверка
репозитория и Project, дедупликация по local ID/outcome, создание parent → child → gated задач,
mapping в [manifest](manifest.json), reciprocal links, read-back. Только по явному поручению.

Отличия этого пакета:

1. Сначала OB-001/OB-002 (подготовка), затем OB-E1…E4 и их задачи по порядку manifest.
2. Связать OB-E1/OB-E2 с существующими issues эпиков Сообщества 3–4 (каталог, интересы) как
   dependency, если эти issues существуют на доске; номера не придумывать.
3. OB-402 и OB-202 сохраняют свои gates в теле issue; Ready для них — только после записи
   решения владельца.
4. Body ссылается на опубликованную версию docs; пока пакет не в main — явно пометить
   local-only references.
