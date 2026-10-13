---
artifact:
  id: local-workspace-audit-2026-10-13
  type: audit-evidence
  title: "Local branches, worktrees and documentation recovery audit"
  status: historical
  created_at: "2026-10-13"
  updated_at: "2026-10-13"
  owners: ["project-owner"]
---

# Где сохранены main, эпики 3 и 4

Read-only audit исходных branches/worktrees на 13.10.2026. GitHub состояние не обновлялось;
`origin/main` ниже — последнее локальное знание. Никакие worktrees/refs/volumes/assets
не удалялись, не reset/rebase/merge. New learning-core docs и точечные исправления лежат
в основном checkout; они не являются реализацией эпиков.

## Основные состояния

| Каталог / ref | HEAD при аудите | Роль |
|---|---|---|
| `/Users/yuzuru/Projects/Mnema`, `docs/community-decks-rfc` | `b45cc99a5c5d5d052f7496c2be09cf7cee0a6292` | Открытый основной checkout; старая docs-ветка, не actual main |
| local branch `main` | `284b4664` | Старый локальный branch pointer, не последний cached main |
| cached `origin/main` | `ae556ece4e4d3e9b54df6fb7b01b61639d64895c` | Последний сохранённый main, community эпики 1–2 |
| `/Users/yuzuru/Projects/Mnema-cd3-int`, `local/epic3` | `f033d533dce453f86f5eba4dae0ee7a9ea808ebe` | 27 коммитов поверх cached main; каталог/поиск/профили и handoff |
| `/Users/yuzuru/Projects/Mnema-cd4`, `local/epic4` | `f4f5e6369df667bf4c692ea58dbea4ccbe2c309c` | Ещё 8 коммитов поверх epic3; интересы/рекомендации и evidence |
| Вложенный `Mnema/Mnema-cd2`, detached | `11d115e832dfa123198139a276eec061c73fdd6e` | Отдельный зарегистрированный worktree, не мусорный untracked каталог |
| Вложенный `Mnema/Mnema-cd3`, detached | `ae556ece4e4d3e9b54df6fb7b01b61639d64895c` | Снимок cached main, отдельный worktree |

`origin/main` — предок epic3, epic3 — предок epic4: `rev-list --left-right --count`
дал `0 27` и `0 8`. Root HEAD против origin/main: `4 29` (история разошлась из-за
docs-RFC и следующих main-коммитов). Это не 4 обязательно уникальных новых features:
смысл RFC уже может быть включён squash-коммитами. Не переносить его целиком без diff review.

Git worktree — отдельные рабочие файлы и index/HEAD при общей object/ref базе `.git`;
это не три независимых clone/backups. Вложенные worktree `.git` — pointer files.
Факт подтверждён [Git documentation](https://git-scm.com/docs/git-worktree) и локальными
`git worktree list --porcelain`, `.git` pointers, ancestry и `git status`.

## Сохранность и runtime

Epic3 и epic4 worktrees чистые: их tracked implementation committed. Большинство старых
task worktrees тоже чисты. В admin-console worktree обнаружено 81 dirty/untracked status
entry — их нельзя считать сохранёнными одним HEAD или удалять как старый checkout.
В основном checkout до этого поручения 8 untracked entries: два вложенных worktrees
и шесть assets/provenance файлов прототипа. Они сохранены; этот пакет не добавляет их в commit.
Полный bounded inventory: [worktrees snapshot](local-worktrees-2026-10-13.json).

Read-only Docker labels подтвердили: retained frontend/identity/learning обслуживаются
из `/Users/yuzuru/Projects/Mnema-cd4`, project `mnema-local-v2`. Media runner имеет старый
checkout label `Mnema-cd1-main`. Это наблюдение provenance контейнеров, не команда
перезапустить/удалить старую папку. Runtime, его volumes и credential files не менялись.

Общая `.git` на одном диске не является независимым backup. Bundle сохраняет committed refs,
но не dirty/untracked/ignored файлы, secrets или данные PostgreSQL/media. Для полного recovery
нужны отдельно эти категории и проверка restore; содержимое secrets не помещать в report.
В этом audit не заявляется создание/проверка полного backup.

## После возвращения GitHub

1. Read actual доступ/remote/rules, затем fetch и новый inventory/diff. Сохранить исходные
   local refs/dirty файлы до любой перестройки. Не использовать filesystem copy поверх main.
2. Deliver epic3 первым по его existing handoff в `local/epic3`:
   `docs/engineering/evidence/community-decks/epic-3/handoff-session-1.md`.
   Уточнить live existing #500/#501 и выбор одного epic PR или reviewable child PRs.
3. После protected squash эпика3 начать новую epic4 delivery branch от свежего main
   и перенести только `f033d533..f4f5e636` (проверить linear commit list).
   Старую stacked epic4 branch не публиковать с повторным epic3 diff; shared-file delta
   сверить полностью, без indiscriminate ours/theirs и без force-push.
4. Сверить V66–V71 с fresh main; pending/unapplied migrations можно перенумеровать вместе
   с inventory tests, applied migrations не изменяются. Full gates после конфликта и перед push,
   hosted checks/up-to-date/resolved reviews до protected squash.
5. Learning-core docs этого поручения переносить отдельным выбранным diff/commit,
   не четырьмя старыми RFC-коммитами. Добавления новых files и navigator rows объединять
   с latest docs, не заменять весь README старым.
6. Production release/flags/host configuration/approval — только named mandate по
   [delivery](../operations/production-delivery.md). Local completed не равно merged/deployed.

Это осуществимый порядок восстановления, а не обещание отсутствия будущих конфликтов:
remote main и чужие изменения сейчас неизвестны. Existing latest epic4 handoff содержит
production/legal/flag нюансы; брать его из соответствующего ref, а не root старой docs-ветки.

## Проблемы документации и безопасная очистка

| Finding | Классификация | Действие этого поручения |
|---|---|---|
| Exercise catalog пишет AI future/deferred/absent, хотя ai-semantic реализован | Stale canonical prose | Исправлено, availability/calibration отделены от implementation |
| MATCH MEDIUM и multi-assessed pair group описаны как current | Противоречие wire contract | Current MATCH LOW, group отмечена future possibility |
| ON_TRACK в progress fixture при enabled2/introduced1 | Неверный executable пример | State LEARNING + shared consumer expectations; runtime не менялся |
| Progress prose считает только introduced subset | Неточное правило | All enabled objectives, precedence и denominator уточнены |
| AI temporary response «until presentation expires» | Неточный retention runbook | Terminal cleanup/raw30d/short feedback quotes разделены |
| Original v1 cutover назван current delivery | Опасный stale execution context | Исторический scope явно отмечен; неприменим к текущим ученикам |
| Sharing wording в latest Study ещё «future» | Branch-specific stale prose | Зафиксировано; поправить в актуальном интеграторе, не утверждать latest в old root |
| Proposed product-direction ждёт #77 после его reactivation | Stale roadmap | Current AI route указать, будущие specialized evaluators оставить гипотезами |
| Root README старее epic3/4 docs | Разные branches | Не «чистить» как duplicates; сверять статус/HEAD |
| Старые evidence, legacy docs, worktrees и assets | История/неизвестные local artifacts | Не удалять; доказанная устарелость не равна разрешённому deletion scope |

Синтаксическая проверка до изменений прошла: 848 Markdown files. Она проверяет
links/status/anchors, но не обнаружила семантически неверный progress example.
Final verification и фактическое сохранение нового пакета фиксируются в отчёте поручения.

## Воспроизведение read-only audit

```bash
git status --short
git worktree list --porcelain
git rev-list --left-right --count HEAD...origin/main
git rev-list --left-right --count origin/main...local/epic3
git rev-list --left-right --count local/epic3...local/epic4
git show local/epic4:docs/engineering/evidence/community-decks/epic-4/handoff-session-1.md
```

Inventory — evidence на дату, не automatic cleanup list. No fetch/push/SSH/prod был частью audit.
