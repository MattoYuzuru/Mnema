---
artifact:
  id: learning-core-github-import
  type: workflow
  title: "Publish the local learning-core backlog without duplicates"
  status: proposed
  created_at: "2026-10-13"
  owners: ["project-owner"]
---

# Перенос на доску после восстановления доступа

Этот файл описывает будущую публикацию по явному поручению. Сейчас GitHub/доска не
изменялись. Два эпика local LC-E1/LC-E2 не являются существующими #421/#422.
Источник body — task/epic Markdown; [manifest](manifest.json) содержит только mapping/order.

1. Read [work item standard](../work-item-standard.md) и `github-workflow` skill.
   Проверить actual remote repository identity, доступ, Project и status поля. Не брать
   номер доски/label названия из старого historical setup как live факт.
2. Проверить свежие source/docs и совпадения существующих Issues по local ID, title,
   outcome и owning subsystem. Если близкая задача уже существует, update/link по scope
   поручения; если scope неясно пересекается, сохранить conflict в mapping.
3. Создать parent LC-E1/LC-E2, затем LC-101…104 и LC-201…204, затем decision spikes
   LC-301…304. Каждый body включает local ID и ссылки на canonical docs плюс source
   commit опубликованного пакета. Пока docs только локальны, сначала опубликовать
   docs PR в разрешённом GitHub scope либо вложить body и явно обозначить local-only references;
   не ссылаться на несуществующую main-версию файла как доступную.
4. Сохранить machine mapping localId→issue URL/number/repository/project item и дату.
   Unknown outcome creation требует read/deduplication перед retry; не создавать второй epic.
5. Связать sub-issues/dependencies через поддерживаемый live API; если функция недоступна,
   использовать явные reciprocal issue links, не утверждать native dependency созданной.
6. На доске Backlog по умолчанию. Ready только по actual DoR: accepted scope, актуальный
   baseline, доступные dependencies, reviewable slice. Human labels/clinical outcomes/
   prospective proof не заменять словом «исследовано». Сохранить unresolved gates в issue.
7. Проверить read-back body/links/parent/status, число unique local IDs и отсутствие duplicates.
   Обновить локальный manifest mapping отдельным commit/PR; current product contracts
   от создания Issues не становятся изменёнными requirements.

PR code delivery, community pending stack, production/environment approval и learner data
эксперименты — отдельный scope. Пользователь может дать совместный mandate, но это не
следует из доступности GitHub. Multiline bodies передавать structured arguments либо body-file,
не shell-interpolated JSON строки.

Если publication прервалась, partial mapping и completed read-back records достаточно
для следующего запуска. `Done` не ставится за наличие issues или подготовленных документов.
