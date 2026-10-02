---
artifact:
  id: epic-77-run-1-evidence
  type: evidence
  title: "Epic #77 run 1: platform upgrade and AI contracts"
  status: historical
  created_at: "2026-10-02"
  owners: ["project-owner"]
---

# Epic #77, прогон 1: платформа и контракты

Доказательство для конкретных commit'ов `main`; не заменяет текущий код и контракты.
Окружение: macOS arm64 (Colima), JDK 25.0.4.1 (Homebrew `openjdk@25`), Node 24.21.0
(`node@24`), Chrome 154; CI — GitHub Actions `ubuntu-latest`.

## #278 INFRA-01 — актуализация стека

| PR | Squash в `main` | Содержание |
|---|---|---|
| #306 | `64cf6750` | Angular 22.2.1 zoneless, Node 24.21.0 LTS, ESLint 10, Karma/Jasmine → Vitest 5 + jsdom, geometry-проверки перенесены в browser harness |
| #308 | `9f387d1f` | Java 25, Gradle 9.8.0, Spring Boot 4.1.1 (Framework 7.0.9, Security 7.1.1, Session 4.1.1, Jackson 3.1.5, JUnit 6.0.3, Testcontainers 2.0.5, Flyway 12.4.0), Tomcat 11.0.26, `-Xlint:all -Werror` |

Проверки на точных commit'ах (подробно — evidence-комментарий в #278):

- backend `./gradlew clean quality`: identity-account 100 тестов, learning 612 (2 skipped,
  docker-smoke-gated); coverage 92,30 % / 94,25 % при пороге 90 %;
- frontend `npm ci && lint && test && build`: 69 файлов / 582 теста, 0 skipped, пороги
  coverage 80/77/82/86 введены впервые; `npm audit` — 0;
- `scripts/learning-security/run.py` 25/25; `scripts/browser-identity/run.py --authoring
  --media --mechanics` — passed на обеих ветках (по два прогона);
- независимые проверки: zoneless-аудит и целостность миграции тестов; SRE/security review
  old-vs-new на одной БД; test-actor по auth-дельте (сессия до апгрейда → re-login, её код →
  `invalid_grant`, новый вход → `id_token` с `auth_time`).

Отклонения от плана research: OpenRewrite не применялся (импорты Jackson — скриптом,
остальное вручную); Tomcat закреплён выше BOM (11.0.26) ради исправлений безопасности;
сессии до апгрейда самоочищаются кодом, очистка таблиц стала опциональной; 10 000-узловой
DOM-тест рендера заменён проверкой на 1 000 узлах (граница — на модели), потому что вставка
DOM в jsdom растёт сверхлинейно.

## #279 AI-00 — контракты и prompt library skeleton

`contracts/generation/` (state machines, HTTP, события с per-session `seq`, ошибки RFC 9457,
MBM v1 с golden fixtures, strict output упражнений), `contracts/usage/` (rate card rc-v1,
allowances, reservations), `contracts/notifications/`, prompt library v1 в
`backend/services/learning/src/main/resources/ai/prompts/`. Fixtures проверяются
`GenerationContractFixtureTest` (реальные `NativeDocumentReader`, `ExerciseCommand.readCreate`,
межфайловая согласованность) и `ExerciseSelfEvaluationFixtureTest` (19 probe через реальный
`AttemptEvaluation`). Контракты прошли два независимых ревью (архитектурное и продуктовое),
замечания исправлены; продуктовые вопросы решены владельцем 2026-10-02 (накопление недельных
порций Free внутри месяца, daily burst по фактическим списаниям, явный отказ сверх лимитов
генерации упражнений, границы суток по Europe/Moscow).

Не проверено исполнением: компиляция MBM и lint упражнений (runtime появится в AI-03/AI-13;
golden-файлы получены непубликуемым эталонным компилятором и проверены чтением native-v1 и
структурными инвариантами).

## Не входит и остаётся

- #280 (H-01) — действие владельца.
- #307 — существующий overflow медиаплеера при 320 px / 2× шрифт, найденный новым сценарием
  harness.
- Читаемость OAuth2 JDBC-строк Security 6.5 проверена только на коротком сценарии; федеративный
  вход live не прогонялся.
