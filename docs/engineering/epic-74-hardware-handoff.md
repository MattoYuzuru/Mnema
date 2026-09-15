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

Текущие срезы передачи:

- **#187 K3**: counted pages и native structural editing, готовый независимо
  проверенный kernel. Candidate `88f123fd8839dbbee3afd2d2c3843d45086a4dd5`.
  [PR #196](https://github.com/MattoYuzuru/Mnema/pull/196), merge оставлен владельцу.
- **#192 Browser Identity**: canonical login/register/PKCE/reload/logout, готовый
  независимо проверенный срез. Candidate после API integration `aa5cde7`, затем
  `d25be79` добавил fingerprints повторного browser-run.
  [PR #197](https://github.com/MattoYuzuru/Mnema/pull/197), merge оставлен владельцу;
  auth backend не переписывался.
- **#194 Own Deck UI**: сохранённый **незавершённый checkpoint**, draft PR ветки
  `epic-74/own-decks-ui`. Initial implementation commit `9c330c7`, затем main merge.
  Страницы list/create/detail НЕ подключены к router; новый shell уже меняет общий
  layout этой ветки. Не называть его готовым пользовательским интерфейсом и не
  закрывать #194 до оставшейся интеграции/проверок.
- **#171/#172**: исходные storage/editor research имеют историю и незавершённые
  критерии. Не закрывать их из одного kernel merge или dependency approval.

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
git worktree add ../Mnema-own-decks -b resume/epic-74-own-decks origin/epic-74/own-decks-ui
```

Не нужны старые worktree paths, agent memory, `/tmp` или build caches. Они не
переносились как зависимости. Исходники, contracts, безопасные evidence и guide
должны браться из GitHub. Старые absolute paths в historical evidence — provenance.

Тестировалось: Node **22.23.2**, npm10.9.8, Java21 (Temurin21.0.11), Docker/Colima,
Python3, Bash, Chrome153; PostgreSQL18.4 в disposable контейнерах. Версии зависимостей
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

## 7. Точный следующий шаг: #194

1. Checkout remote draft ветку; ознакомиться с `own-decks-ui.md` целиком и diff.
2. Merge свежий main с #192. Сохранить новый paper shell, убрать legacy `init()`;
   примирить app.config без дублирования `learningApiBaseUrl`, дополнить mock AuthUser
   всеми required полями нового auth. Не выбирать blind ours/theirs.
3. Подключить lazy `/decks`, `/decks/new` (перед ID), `/decks/:deckId` к новым страницам.
   Без `/v2`, alias, compatibility wrapper. Shared runtime/media/AI не удалять целиком.
4. Удалить заменённые legacy list/profile и только их недостижимые модалки/tests:
   `features/decks/decks-list`, `deck-profile`, `add-cards-modal`, `ai-add-cards-modal`,
   `ai-enhance-deck-modal`, `ai-import-modal` (все component.ts + существующие specs).
   При снятии browse/review/duplicates-review освобождаются все14файлов папки;
   shared API/renderer ещё нужны public-decks/my-study/templates/wizard/settings.
5. Старый Home делает guest PublicDeck API и auth legacy N+1/review/media; заменить
   честным стартом своих колод, без fake due/progress/catalog/study. Global CSS имеет
   heading overrides, glass input blur, glow body,13pxmobile font; сделать paper base.
   Убрать внешние Google Fonts из index.html, lang=ru. Не менять JSON-LD без обновления
   связанных CSP hashes в runtime-generator/security verifier.
6. Применять canonical paper#f4f0e5/sheet#fbf8ef/ink#281378/body#342e44/muted#625c70;
   Georgia fallback headings/systemsans controls16px, один main/h1,44pxactions/focus.
   ThemeService сохраняет inline tokens после Settings; учесть, не удалять вслепую.
7. Прогнать реальный browser Identity+Learning+PostgreSQL: create/list/detail/save/reload,
   два writer tabs412, lost acknowledgement→same command retry, replay GET failure,
   cross-owner404/403/401/503. Расширять harness без настоящих credentials.
8. 320/390/1440px,200%zoom, keyboard/focus, long Russian text, reduced motion/contrast,
   route chunks/first useful screen/request fanout. Не называть Chromium manualAT/IME.
9. Independent review → exact fullgate → PRready → protected squash → main verification.

Исправления уже доказаны RED→GREEN: store admission против второго UUID, блокировка
незавершённого draft, «Создание не подтверждено», multiline title,20rows/10cursorwindow.
33componenttests и независимый120pageprobe PASS. **Но state page-local**, не durability:
reload/navigation теряют draft/unknown command; это нужно явно обработать до продуктовой
приёмки. Не выдавать этот store за серверный EditingDraft.

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
