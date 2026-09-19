# Epic #74 — продолжение на новом компьютере

Дата checkpoint: 2026-09-15. Это входной документ для агента **без памяти чата**.
Владелец меняет компьютер и попросил остановиться на ближайшей проверенной точке,
сохранить работу в GitHub и передать контекст. Это не закрытие Epic #74.

## 1. Сначала прочитать

1. [Сохранённые инструкции агента](agent-guide.md), корневой `AGENTS.md` (если есть)
   и применимые вложенные инструкции. Старый AGENTS был локально ignored; его полный
   текст сохранён в agent-guide.md, чтобы clone не зависел от памяти/настроек машины.
2. Этот документ, затем [оригинальное поручение](prompts/epic-74-end-to-end.md).
3. [Стандарт задач/PR](work-item-standard.md), [refinement #74](epic-74-refinement.md).
4. [Решения по зависимостям](epic-74-dependency-decisions.md).
5. [Local-only delivery](../operations/local-development-delivery.md).
6. Перед следующей реализацией — нужные канонические документы из раздела 5.

Сверить реальный GitHub, а не считать этот текст динамическим статусом:

- Репозиторий: https://github.com/MattoYuzuru/Mnema, default branch `main`.
- Epic: https://github.com/MattoYuzuru/Mnema/issues/74 — остаётся OPEN.
- Project: https://github.com/users/MattoYuzuru/projects/4.
- Ветки передачи: `epic-74/counted-pages`, `epic-74/browser-identity`,
  `epic-74/own-decks-ui`, `epic-74/hardware-handoff`.
- PR этих веток содержат точные head/merged SHA и финальные проверки.
  Исторические candidate SHA ниже нужны для трассировки, не для отката main.

## 2. Действующее поручение и запреты

После явного возобновления владельцем продолжать #74 end-to-end небольшими PR,
с backend/frontend lanes и независимым review. Не начинать весь discovery заново.
Владелец разрешил local implementation, disposable DB/browser resources, создание
child issues, feature commits/push/PR и squash-merge после обязательных проверок.
Разрешены максимум четыре агента, включая ведущего, без рекурсивной делегации.
Предпочтения: ведущий/storage/review Astra high; frontend Sol high, если доступны.
Ведущий владеет общей схемой/контрактами, integration Git и всеми GitHub writes.

**Сервер потерян. Только local + GitHub CI.** Ранее данное production-разрешение
отменено более поздним решением владельца. Не делать staging/production/SSH/rollout,
environment approval, публикацию образов или ожидание старого окружения. Не читать
старый `.env` ради разработки. Новый сервер, адреса, секреты, восстановление данных
и reactivation rollout — отдельная будущая задача с конкретным разрешением.
Не ослаблять #147 или отключённые operational guards.

Не пушить main напрямую, не force-push, не обходить protection, не менять ruleset,
Git identity или attribution trailers. Squash только через PR с актуальным base,
resolved threads, green `backend-quality`/`frontend-quality` и остальными checks.
Полный локальный gate на точном чистом commit — **до каждого push и до merge**.
Не ослаблять coverage, не превращать обязательные DB-тесты в skipped.

Новая dependency или новый upgrade вне зафиксированных approvals требует решения
владельца. Не трактовать MIT-зависимости как перелицензирование Mnema; сохранить
third-party notices и текущие LICENSE/NOTICE.

## 3. Что уже реализовано и что ещё нет

До этой передачи в main проверенно вошли:

| Часть | Issue / PR | Merged SHA (исторический) |
|---|---|---|
| Angular 22 migration | #175 | см. angular-migration evidence и GitHub |
| Learning auth boundary / real security fixture | #176 | см. learning-auth evidence |
| Local-only pipeline | #185 / #186 | `462e7d81161c06812e753cf3e1d0bb6e67b9ebc3` |
| Native content v1 contract | #178 / #184 | `e80cf93afdbd9214cc8a5b1adf0c9000c265b3a6` |
| K1 immutable storage | #179 / #189 | `b6c523b7c6177a5c4b43fe44c8c7aa8aa1e92b11` |
| K2 native codec/fragments | #181 / #190 | `6eba60d5a4e418fa1c6efcfccf6a3c4c5cd1bfe4` |
| Safe native renderer | #183 / #191 | `8c76daa7446de67863a5fb10dfa6154fb25f4481` |
| Private Deck metadata API | #188 / #193 | `cf698f957c5b66c45ae8829a6a688bda8a2a7b13` |
| K3 counted pages / structural editing | #187 / #196 | `2e0712af517c40f24cd6de04f45705e27214f3a0` |
| Browser Identity | #192 / #197 | `8fb93904dfedc2d78d11f1fe01d53fc2d9d5fa1c` |
| Canonical private Deck UI | #194 / #198 | `1b994d9b793eeb5a26b33d2c88ea9b4d2ddd843e` |

После исходной передачи завершены:

- **#187 K3**: counted pages и native structural editing доставлены через
  [PR #196](https://github.com/MattoYuzuru/Mnema/pull/196), protected squash
  `2e0712af517c40f24cd6de04f45705e27214f3a0`; research и implementation bounds
  остаются описаны в counted-page evidence.
- **#192 Browser Identity**: canonical login/register/PKCE/reload/logout доставлены
  через [PR #197](https://github.com/MattoYuzuru/Mnema/pull/197); protected squash
  `8fb93904dfedc2d78d11f1fe01d53fc2d9d5fa1c`. Auth backend не переписывался.
- **#194 Own Deck UI**: canonical lazy list/create/detail, paper shell, bounded
  tab-local recovery и настоящий API/conflict flow доставлены через
  [PR #198](https://github.com/MattoYuzuru/Mnema/pull/198); protected squash
  `1b994d9b793eeb5a26b33d2c88ea9b4d2ddd843e`. Это metadata slice, не editor.
- **#171 Storage research**: bounded research acceptance выполнен, owner storage
  choice явно записан, а K1/K2/K3 доставлены отдельными slices. Перед закрытием
  требуется только сверить checklist/evidence bookkeeping; не выдавать это за
  завершение LearningItem, fork product или всего Epic #74.
- **#172 Editor research**: остаётся открытым. Static ProseMirror evidence не заменяет
  обещанный Angular prototype; этот handoff не меняет его scope и не разрешает
  production adoption.

**Ещё не реализовано:** полноценная публикация LearningItem, member API и Browse,
серверные EditingDraft, долговечные CaptureNote «На потом» и атомарная conversion,
нативный rich editor/adapter и полный authoring E2E. Нет готовых study sessions,
scheduler, media lifecycle, AI, каталога или fork UI — соседние эпики не реализовывать.
Первый нужный E2E: создать колоду → «На потом» → оформить материал → сохранить →
reload → продолжить. Это milestone, не весь DoD #74.

## 4. Восстановить рабочую среду

Клонировать через настроенный GitHub credential helper/SSH, **без токена в URL**:

```sh
git clone https://github.com/MattoYuzuru/Mnema.git
cd Mnema
git fetch origin
git status --short --branch
git log -8 --oneline
```

Не нужны старые worktree paths, agent memory, `/tmp` или build caches. Они не
переносились как зависимости. Исходники, contracts, безопасные evidence и guide
должны браться из GitHub. Старые absolute paths в historical evidence — provenance.

Тестировалось: Node **22.23.2**, npm10.9.8, Java21 (Temurin21.0.11), Docker/Colima,
Python3, Bash, Chrome154; PostgreSQL18.6 в disposable контейнерах. Версии зависимостей
в package-lock/Gradle — истина; не выполнять `npm update`/`audit fix` при восстановлении.
Angular runtime22.1.5/build22.1.7, TS6.0.3, typescript-eslint8.58.0 уже migrated.
Локальный Node26 старой машины не использовался для воспроизводимых gates.
Дополнительно нужны Python >=3.10, AWS CLI v2 (synthetic MinIO tests, не реальный AWS),
`sha256sum` (GNU coreutils на macOS), OpenSSL с `req -addext` для browser fixture.
Gradle wrapper8.14.5. Не копировать старые `scripts/dev/*`: они отсутствуют в текущем
main и ссылались на удалённый `:services:auth`/Node20.

Проверить `node --version`, `npm --version`, `java -version`, `docker version`,
`python3 --version`, наличие Chrome и доступность Docker daemon. Gradle wrapper
лежит в backend; глобальный Gradle не требуется. Для macOS установить `JAVA_HOME`
через `/usr/libexec/java_home -v 21`, `CHROME_BIN` указывает на локальный Chrome.
Для Linux использовать свои установочные пути. Не переносить личный socket path
`/Users/m.ryabushkin/.colima/...` на новый компьютер.

Если используется Colima, настроить Docker context/socket своего пользователя и
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. Старый gate применял
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44` для совместимости Docker API. Проверить, нужен
ли override новой среде; не выключать обязательные Testcontainers tests.

Минимальный build/test (это **часть**, не весь gate):

```sh
./backend/gradlew -p backend quality
cd frontend
npm ci
npm run lint
npm test
npm run build
```

Полный gate — **все 33 shell `run`-шага** актуального
[pull-request workflow](../../.github/workflows/pull-request.yaml), включая security,
policy, contract, cancellation, backup/recovery и purge rehearsal integration.
Прочитать workflow целиком, выполнить команды с указанными working-directory;
setup/upload Actions не являются локальными shell-командами. После изменения
workflow заново вывести список, не доверять числу33 как вечной константе.
Названия `production/staging` у локальных **test-скриптов** не означают deploy:
они проверяют guard contracts в изоляции. Не заменять их operational helpers.
Backend static lint отдельно не настроен; compilation/tests/шесть JaCoCo LINE floors
обязательны: AI/import80%, core/media/identity-account/learning90%.
Не утверждать frontend coverage threshold, которого нет в конфигурации.

Параллелить можно разные clean worktrees и disposable resources. Karma default9876
нельзя занимать двумя full gates; targeted тесты использовали отдельный config9877
(CLI `ng test --port` не поддерживает эту настройку). Не запускать gate на дереве,
которое другой агент редактирует.

## 5. Канонические документы и код

- [Delivery dependency graph](v2-delivery-plan-2026-08.md).
- [Owner decisions](../decisions/owner-decisions-2026-08.md).
- [Authoring/Capture/draft semantics](../product/authoring-and-study-workflows.md).
- [Architecture](../architecture/content-platform-v2.md).
- [Native content semantics](../architecture/learning-content-format-v2.md).
- [Storage/revision/runtime boundaries](../architecture/revision-storage-and-runtime-boundaries.md).
- [Paper visual direction](../frontend/design-and-experience-2026-09.md) и
  [design prototype](../../design/prototype/README.md): visual evidence, не production code.
- Native wire/golden/lexical vectors: `contracts/content/native-v1/`.
- Deck shared DTO/error fixtures: `contracts/decks/{README.md,metadata.json}`.
- Learning: `backend/services/learning/src/main/java/app/mnema/learning/`.
  `catalog/content/storage` — K1/K2/native structural; `catalog/content/pages` — K3;
  `catalog/deck` — Deck API; `platform` — auth/errors/idempotency boundaries.
- DDL: `backend/services/learning/src/main/resources/db/learning/migration/`:
  V1 foundational, V2 immutable storage, V3 private Deck revisions. **V4 ещё не
  распределён**; один owner выделяет номер после сверки свежего main.
- Renderer: `frontend/src/app/content/rendering/`.
- Auth: `auth.service.ts`, `auth-protocol.ts`, `auth-browser.ts`, interceptor,
  login/callback, guard и runtime config; shared shell integration отдельна.
- UI checkpoint: `frontend/src/app/features/own-decks/`, shell `core/layout/` в draft ветке.
- Evidence: `docs/engineering/evidence/epic-74/` и README самих harness.

## 6. Не потерять принятые инварианты

K1: immutable scope/object/ordered FK edges/pins, 16KiB payload, fanout32,
one-call staging bounded64objects/1MiB; retain/release в caller transaction;
GC rechecks pin/children grace and never grants ACL through physical scope.
K2: scalar-safe512–2048byte fragments(target1024),16–32pages; exact opaque roundtrip,
flattened preorder; fixed-topology replace не является разрешением редактировать
future/opaque content. Decoder bounded32object batches/16kobjects/16MiB.
K3: members/exercises rank10, descriptor9→native8, nonroot16–32children;
ordinal expected-key operations, immutable old roots, reachable additions only.
Source root/frontier must remain pinned across multi-call work. Global member-key
uniqueness/ACL/publication belong domain caller, not hidden full-tree scans.

Native v1: strict1MiB UTF-8/duplicate-key rejection,10k nodes/depth32,32KiB scalar,
known11type baseline, opaque future lossless. Native node IDs UUIDv4 case-preserved
but unique by UUID value; command UUIDv4/v7 — другой контракт. Physical sharing
не делает LearningItem cross-deck entity и не делит progress.

Deck: POST/GET `/api/decks`, GET/PATCH `/api/decks/{deckId}`. Owner только verified
subject; foreign/absent404. Strict8192byte envelope; title200codepoints/800bytes,
description4096bytes, без trim/newline loss. PATCH full metadata, не merge-patch.
If-Match single quoted decimal,428missing/412stale/409changedcommand.
Replay original acknowledgement **без ETag**, `Idempotency-Replayed:true`, затем
GET актуальной detail. CAS/history/pins/receipt atomic. Scope не UNIQUE ownership.
Список keyset default20/max100, `(createdAt,deckId)` ordering и cursor, не N+1.

Identity: шесть scopes `openid profile account.read account.write learning.read learning.write`.
CSRF+cookie login/register, one-use PKCE, profile из real `/me`, не JWT decode.
Access max5min в sessionStorage; no refresh/ID-token persistence. Bearer logout/password
без ambient Cookie; distinct HTTPS Identity origin enforced. TabA logout не отзывает
TabB при общей cookie. Local clear не равен подтверждённой серверной ревокации.

## 7. Точный следующий шаг: #200

#194 завершён: route integration, paper shell, bounded account-bound `sessionStorage`
recovery, real API/reload/412 browser path и cleanup вошли в main. Recovery хранит только
точную pending-команду в пределах вкладки и не является серверным EditingDraft.

Следующий bounded slice — [#200](https://github.com/MattoYuzuru/Mnema/issues/200):

1. Зафиксировать deck-local `memberKey` identity и canonical
   `/api/decks/{deckId}/items` без `/v2`/legacy adapters.
2. Публиковать base `LearningItemRevision` и deck revision/head projection атомарно,
   сохраняя immutable K1/K2/K3 objects, pins, ACL, CAS и idempotency receipts.
3. Ограничить native body, request/response, item count и preparation work; не делать
   full-manifest scan, eager fork materialization или renumber-all.
4. Проверить own/foreign scopes, missing/stale preconditions, same-command replay,
   concurrent writers, rollback/disconnect и lossless K1/K2/K3 round-trip.
5. Independent review → exact full gate → PR ready → protected squash → main verification.

## 8. Затем backend Item → drafts/Capture → editor

Предыдущий read-only план следующего Item slice — предложение, не frozen wire:
append create/snapshot ordinal+memberKey read/saveone; identity(deckId,memberKey);
base ItemRevision + Deck snapshot/version; bounded native command body; descriptor
rank9 с FK edge→native8. Stage/encode вне короткой publication transaction, удерживать
source/frontier pins, ограничить batches/bytes/deadline и crash/retry receipt.
Финально ACL + receipt + headCAS + ready preparation + revision/pins/projection atomic.
Не вводить fullmanifest scan/renumberall или обязательную materialization всего fork.
До кода ведущий фиксирует shared fixtures, V4 allocation и scope отдельного child.

Далее server-backed EditingDraft с несколькими документами/вкладками/conflict,
CaptureNote без idleTTL с createdAt/archive/delete и idempotent conversion, native
editor adapter/preview/Browse. ProseMirror approval есть, production adoption ещё нет:
roundtrip stable IDs, opaque preservation, русские длинные тексты, paste/undo/redo,
IME/RTL/ruby/mobile должны быть проверены. Не persist ProseMirror state.

## 9. Финальная честность и безопасность передачи

Merge != deploy. Green unit tests != полный пользовательский outcome. В evidence
записывать exactcommit, command, counts/zero-skips, environment и unverified limits.
Синтетические100k measurements не production SLO; screenshots не real screen reader.
Known Dependabot/audit findings не были silently fixed; чужие dependency PR не входят
в аппаратную передачу #74. Не мержить их оптом из слова «всё».

Не копировать `.env`, SSH keys, GitHub tokens, keychain, cookies, private fixture logs,
node_modules, .angular, Gradle build dirs или production dumps в GitHub. Доступ к
GitHub настроить заново безопасно. Secrets нового сервера — отдельно.
Последнее уточнение владельца: **только push, merge он выполнит сам**. PR #196/#197
могут требовать обновления base после последовательного squash. Повторно проверить
exacthead/fullgate/rules/checks перед merge, не включать auto-merge на старом основании.

Архивные исследовательские материалы сохраняют статус evidence/prototype, не источник
production contracts. Старый execution checkpoint superseded этим документом.
