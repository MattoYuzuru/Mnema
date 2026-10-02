---
artifact:
  id: platform-speech-search-research-2026-10
  type: research
  title: "Platform upgrade, self-host speech and web search research"
  status: historical
  created_at: "2026-10-02"
  owners: ["project-owner"]
---

# Mnema: platform & speech research — стек, STT, TTS, web search

Дата исследования и доступа ко всем ссылкам: **2026-10-02**. Код — read-only worktree
`origin-main` (Spring Boot 3.5.16, Java 21, Angular 22.1.5). Это исследование, не
решение: цены, terms и санкционные оговорки меняются, юридические выводы требуют юриста.
Предыдущие исследования, на которые отчёт опирается и которые частично обновляет:
`docs/reviews/ai-layer-research-2026-10/economics.md`, `docs/product/ai-layer-2026-10.md`,
`docs/engineering/epic-77-refinement.md` (INFRA-01).

Курс для пересчёта: **1 USD = 83,2454 ₽** (ЦБ РФ на 02.10.2026,
[cbr.ru XML_daily](https://www.cbr.ru/scripts/XML_daily.asp)). Для округлённых оценок ниже — 83 ₽/$.

## TL;DR

1. **Стек.** Цель INFRA-01 на 2026-10-02: Java **25 LTS** (Temurin 25.0.4.1), Gradle **9.8.0**,
   Spring Boot **4.1.1** (Framework 7.0.9, Security 7.1.1, Session 4.1.1, Jackson 3.1.5,
   JUnit 6.0.3, Testcontainers 2.0.5, Flyway 12.4.0, Tomcat 11.0.24), Angular **22.2.1**
   (23 ещё не вышел), TypeScript **6.0.x** (не 7), Node **24 LTS** (26 станет LTS 2026-10-28),
   Karma → **Vitest**, ESLint **10**. Главный риск — не компиляция, а **семантика Jackson 3**:
   `textValue()`/`asText()` теперь бросают `JsonNodeException` там, где Jackson 2 возвращал
   `null`/`""`, а `JacksonException` больше не `IOException`. При механической миграции 11
   парсеров команд и `ContentJsonReader` начнут отдавать 500 вместо 400. Второй риск — **тихие** регрессии
   модульных стартеров Boot 4 (Flyway и Spring Session JDBC перестают автоконфигурироваться
   без новых стартеров). Объём — ≈ **6–9 agent-days** в 6–7 PR.
2. **STT на 4 vCPU / 8 GB без GPU.** Whisper large-v3-turbo и medium на таком CPU дают
   10–25 s на 10-секундный клип: энкодер всегда считает 30-секундное окно. Рекомендация —
   отдельный контейнер (2 vCPU / 2,5 GB) с очередью и маршрутизацией по языку:
   RU → **GigaAM-v3** (MIT), остальные → **Qwen3-ASR-0.6B** int8 (Apache-2.0, все 7 языков,
   ≈ 3–7 s на клип на 2 vCPU — оценка, нужен замер). Этого хватит на 400–1 000 пользователей,
   наговаривающих ~20 мин/мес; дальше — отдельный CPU-узел (≈ 2,8 тыс. ₽/мес), GPU — только для «live» или
   large-v3-качества. Внешние API дёшевы (Groq ≈ $13/мес при 20 тыс. мин), но легальный
   доступ — главный блокер: ЕС с 19-го пакета прямо запрещает API-доступ к моделям
   транскрибации для российских юрлиц (Art. 5n(1)(g)), для KO/JA/ZH российского API нет.
3. **TTS.** Уровня Google легально и из РФ — **только русский: Yandex SpeechKit API v1**
   (1 342 ₽ за 1M символов с НДС; terms разрешают кэшировать и переиспользовать
   аудио). FR/ES/JA/ZH/KO у Yandex нет. Для них — MiniMax ($60–100/1M, #18–20 в
   англоязычной арене) или Alibaba Qwen-Audio-3.0 (#4) **при легальной оплате**, иначе
   self-host Qwen3-TTS / CosyVoice3 / Chatterbox (Apache/MIT) пакетно на почасовом GPU —
   качество заметно ниже. Главный рычаг — общий кэш по
   `(нормализованный текст, язык, голос, модель, версия, формат)`.
4. **Web search.** Primary — **Yandex Search API** (0,488 ₽/запрос sync, 0,0305 ₽ deferred;
   российский контрагент, оплата ИП; для иностранных языков — тип COM). Exa и Tavily прямо
   исключают Россию; Bing API закрыт, Google Custom Search закрыт для новых клиентов.
   Brave/Perplexity ($0.005) — только при легальной оплате. Извлечение страниц — self-host
   jsoup за SSRF-guard.

---

## 1. План актуализации стека (INFRA-01)

### 1.1 Что сейчас в репозитории (факты из worktree)

| Область | Текущее состояние | Где |
|---|---|---|
| Gradle | wrapper **8.14.5** | `backend/gradle/wrapper/gradle-wrapper.properties` |
| Boot / BOM | `org.springframework.boot` **3.5.16**, `io.spring.dependency-management` 1.1.7 | `backend/settings.gradle.kts` |
| Kotlin | плагины `kotlin("jvm"/"plugin.spring"/"plugin.jpa")` **2.4.20** объявлены, но в `backend/` **0 файлов `.kt`**; `libs.versions.toml` нет | `settings.gradle.kts`, `build.gradle.kts` (`jvmToolchain(21)` для Kotlin) |
| Overrides BOM | `netty.version=4.1.137.Final`, `postgresql.version=42.7.13`, `tomcat.version=10.1.59` | `backend/build.gradle.kts` |
| JaCoCo | `toolVersion = "0.8.11"`; gate — `coverage-baseline.json`: 0.90 line coverage на сервис | `build.gradle.kts`, `scripts/check_coverage.py` |
| Линтеры backend | не настроены (нет Checkstyle/SpotBugs/Error Prone/`-Xlint`) | — |
| Сервисы | `learning` (158 main / 83 test Java), `identity-account` (61 / 17); toolchain Java 21 | `services/*/build.gradle.kts` |
| Стартеры | web, jdbc, actuator, validation, oauth2-resource-server; в identity ещё security, oauth2-client, **oauth2-authorization-server**, `spring-session-jdbc`; `flyway-core` + `flyway-database-postgresql` напрямую; AWS SDK `s3:2.55.5` | там же |
| Тесты | JUnit Jupiter, Mockito (`mock/when/verify`, без `@MockBean`), MockMvc (183 упоминания), 28 `@SpringBootTest`, Testcontainers `PostgreSQLContainer` (`org.testcontainers.containers.*`), MinIO `GenericContainer` | `src/test` |
| JSON | Jackson 2 используется широко: **77 main + 62 test файлов**; `textValue()` — 162 main / 336 test вызовов, `isTextual()` — 52 / 6, `asText()` — 13 / 53, `JsonProcessingException` — 14 main; streaming: `ContentJsonReader` (`JsonFactory.builder()`, `StreamReadConstraints`, `STRICT_DUPLICATE_DETECTION`), `CanonicalJsonHasher` (`JsonGenerator.writeFieldName`) | `learning/.../platform/json/*` |
| HTTP-клиенты | JDK `HttpClient` + `JdkClientHttpRequestFactory`; `RestClient`; `RestTemplate` в `ProviderUsers` для `DefaultOAuth2UserService.setRestOperations`; уже `RestClientAuthorizationCodeTokenResponseClient` | `identity-account/.../federation/ProviderUsers.java` |
| Security | lambda-DSL, строковые `requestMatchers`, `.access((auth, ctx) -> AuthorizationDecision)`; AS через `OAuth2AuthorizationServerConfigurer.authorizationServer()`, `JdbcOAuth2AuthorizationService`, `JdbcRegisteredClientRepository`, public client `NONE` + PKCE; `NimbusJwtDecoder(processor)` с собственным JWT processor | `SecurityConfiguration.java`, `AuthorizationConfiguration.java`, `LearningSecurityConfiguration.java` |
| Runtime | `spring.threads.virtual.enabled=true` в обоих сервисах; 7 `@Scheduled`-воркеров; `spring.jackson.deserialization.fail-on-unknown-properties=true` (identity) | `application.properties` |
| Docker | `gradle:8.14.5-jdk21`, `eclipse-temurin:21.0.12_8-jre-resolute`, `node:22.23.2-alpine`, `nginx:1.31.4-alpine`, `docker:29.5.2-cli`, `postgres:18@sha256…`; media-worker — Python + Ubuntu 24.04 FFmpeg (JVM нет) | `backend/Dockerfile`, `deploy/local-full-stack/*.Dockerfile`, `frontend/Dockerfile` |
| CI | `actions/checkout` v7.0.1, `setup-java` v6.0.1 (temurin 21), `setup-node` v7.0.0 (node 22.23.2) — все уже **последние релизы** и запинены по SHA | `.github/workflows/pull-request.yaml` |
| Frontend | Angular 22.1.5 (cli/build 22.1.7), TS 6.0.3, `zone.js` 0.16.3 + `provideZoneChangeDetection()`, 47 OnPush + 1 `Eager` (`AppComponent`), `NgZone.runOutsideAngular` в `learner-media.component.ts`, `withXhr()`; Karma 6.4.4 + Jasmine 5.4 (69 spec, 34 `fakeAsync`); ESLint 9.15.0, angular-eslint 22.2.0, typescript-eslint 8.58.0; `@angular/cdk` в dependencies, но **ни одного импорта** в `src` | `frontend/package.json`, `angular.json`, `src/main.ts` |
| Harness | `scripts/browser-identity` требует «Java 21 on PATH, Node 22» (встроенный WebSocket + CDP), `scripts/learning-security` — black-box | README harness |

### 1.2 Последние стабильные версии на 2026-10-02

| Компонент | Сейчас | Цель | Факты и источник |
|---|---|---|---|
| Java | 21 (Temurin 21.0.12) | **25 LTS** (Temurin `25.0.4.1+1`, следующий CPU-апдейт 25.0.5 — октябрь 2026) | Java 25 — LTS, поддержка Temurin как минимум до сентября 2031; 21 — как минимум до декабря 2029; 27 вышел 2026-09-15, не LTS, EOL Mar 2027; LTS каждые два года → следующий LTS — 29 (сентябрь 2027, вывод из каденса) — [Adoptium support](https://adoptium.net/support/), [OpenJDK JDK projects](https://openjdk.org/projects/jdk/) |
| Gradle | 8.14.5 | **9.8.0** (2026-09-24) | Java 25 toolchain/running — с 9.1.0, Java 27 — с 9.8.0; Gradle 9 требует JVM 17–27 — [Gradle releases](https://gradle.org/releases/), [compatibility](https://docs.gradle.org/current/userguide/compatibility.html) |
| Spring Boot | 3.5.16 | **4.1.1** (2026-08-20) | 4.1 OSS до 2027-07-31, 4.0 OSS до 2026-12-31, 3.5 OSS закончилась 2026-06-30; 4.1 поддерживает Java 17–26; 4.2.0-M2 — milestone — [GitHub releases API](https://api.github.com/repos/spring-projects/spring-boot/releases), [endoflife.date](https://endoflife.date/spring-boot), [Maven Central](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/maven-metadata.xml) |
| Spring Framework | 6.2.19 | **7.0.9** (через BOM 4.1.1) | 7.1.0 — пока M2 — [Boot 4.1.1 BOM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom) |
| Spring Security (+AS) | 6.5.11 + Authorization Server 1.5.8 | **7.1.1** (AS теперь часть Security) | [BOM 4.1.1](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom), [What's new 7.0](https://docs.spring.io/spring-security/reference/7.0/whats-new.html) |
| Spring Session | 3.5.7 | **4.1.1** | BOM 4.1.1 |
| Jackson | 2.21.4 | **3.1.5** (BOM; последний 3.2.3, `jackson-2-bom` 2.21.5 остаётся для совместимости) | Jackson рекомендует 3.1 как долгосрочную цель — [MIGRATING_TO_JACKSON_3](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md) |
| JUnit | Jupiter 5.12.2 | **6.0.3** (BOM; последний 6.1.3) | BOM 4.1.1 |
| Mockito | 5.17.0 | **5.23.0** (BOM; последний 5.24.0) | BOM 4.1.1 |
| Testcontainers | 1.21.4 | **2.0.5** | артефакты с префиксом `testcontainers-`, классы в `org.testcontainers.<module>`, без JUnit 4 — [2.0.0 release](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0) |
| Flyway | 11.7.2 | **12.4.0** (BOM) — **не** поднимать до 13.x отдельно | 13.x есть (13.9.0), но выбирать вне BOM нет причины — [Maven Central](https://repo1.maven.org/maven2/org/flywaydb/flyway-core/maven-metadata.xml) |
| PostgreSQL JDBC | 42.7.13 (override) | 42.7.13 (= BOM 4.1.1, override удалить) | [Maven Central](https://repo1.maven.org/maven2/org/postgresql/postgresql/maven-metadata.xml) |
| Tomcat | 10.1.59 (override) | **11.0.24** (BOM; override удалить) | BOM 4.1.1 |
| Netty | 4.1.137 (override ради AWS SDK) | см. §1.3: исключить `netty-nio-client` | AWS SDK 2.55.10 сам сидит на Netty `4.1.138.Final`, Boot 4.1.1 управляет `4.2.17.Final` — [AWS SDK pom](https://repo1.maven.org/maven2/software/amazon/awssdk/aws-sdk-java-pom/2.55.10/aws-sdk-java-pom-2.55.10.pom) |
| AWS SDK s3 | 2.55.5 | 2.55.10 | Maven Central |
| Kotlin | 2.4.20 (не используется) | удалить плагины | BOM 4.1.1 управляет Kotlin 2.3.21; последняя стабильная — 2.4.20 |
| JaCoCo | 0.8.11 | **0.8.15** | 0.8.14 — официальная поддержка Java 25, 0.8.15 — Java 26 + experimental 27; 0.8.11 Java 25 не знает — [JaCoCo changes](https://www.jacoco.org/jacoco/trunk/doc/changes.html) |
| Angular | 22.1.5 | **22.2.1** (2026-09-30) | v22 active до 2027-06, LTS до 2028-06; v23 ещё не вышел — [angular.dev releases](https://angular.dev/reference/releases), npm registry |
| TypeScript | 6.0.3 | **6.0.x** | Angular 22 требует `>=6.0 <6.1`; TS 7.0.2 существует, но несовместим — [versions](https://angular.dev/reference/versions) |
| Node | 22.23.2 | **24.21.0 LTS** (или 26.x после 2026-10-28) | v24: maintenance с 2026-10-20, EOL 2028-04-30; v26: LTS с 2026-10-28, EOL 2029-04-30; v22 EOL 2027-04-30; Angular 22 engines `^22.22.3 \|\| ^24.15.0 \|\| >=26.0.0` — [nodejs/Release schedule.json](https://raw.githubusercontent.com/nodejs/Release/main/schedule.json) |
| Тест-раннер | Karma 6.4.4 + Jasmine | **Vitest 5.0.3** + jsdom 30 | Karma deprecated (последний релиз 2024-07); `@angular/build` 22.2.1 peer `vitest ^4.0.8 \|\| ^5.0.0` — [migrating to Vitest](https://angular.dev/guide/testing/migrating-to-vitest), npm registry |
| ESLint | 9.15.0 / angular-eslint 22.2.0 / ts-eslint 8.58.0 | **10.11.0 / 22.5.0 / 8.71.0** | peer: angular-eslint `eslint ^9 \|\| ^10`, ts-eslint `typescript <6.1` — npm registry |
| zone.js | 0.16.3 | удалить после zoneless | zoneless — default для новых приложений с v21, OnPush — default с v22 — [Ninja Squad 22.0](https://blog.ninja-squad.com/2026/06/03/what-is-new-angular-22.0) |
| Docker images | см. 1.1 | `gradle:9.8.0-jdk25(-resolute)`, `eclipse-temurin:25.0.4.1_1-jre-resolute`, `node:24.21.0-alpine3.24`, `nginx:1.31.6-alpine3.24`, `postgres:18.6` (digest-pin), `docker:29.8.x-cli` | теги проверены через Docker Hub API |
| GitHub Actions | checkout v7.0.1, setup-java v6.0.1, setup-node v7.0.0 | без смены версии action; менять только `java-version: '25'`, `node-version` | последние релизы — GitHub API (setup-java 2026-09-09, setup-node 2026-07-14) |
| Chrome в CI | системный Chrome `ubuntu-latest` для Karma `ChromeHeadless` (не запинен) | после Vitest+jsdom unit-тестам браузер не нужен; browser harness — локальный Chrome | — |

### 1.3 Breaking changes, которые реально заденут этот код

**Spring Boot 4.0/4.1** ([4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide),
[4.0 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes),
[4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)):

| # | Изменение | Что сломается в Mnema | Тип риска |
|---|---|---|---|
| B1 | Модульные стартеры. `spring-boot-starter-web` → `-webmvc`; `oauth2-client/resource-server/authorization-server` → `spring-boot-starter-security-oauth2-*` (старые имена deprecated) | оба `build.gradle.kts` | компиляция/deprecation |
| B2 | **Flyway требует `spring-boot-starter-flyway`** вместо прямого `flyway-core` | без него автоконфигурации Flyway нет — миграции, по всей видимости, **молча не запустятся** (вывод из модульности, подтвердить тестом) | **тихая регрессия** — ловится Testcontainers-тестами, если они есть на обоих сервисах |
| B3 | Spring Session JDBC — `spring-boot-starter-session-jdbc` | без стартера автоконфигурации Spring Session нет → `spring.session.jdbc.*` игнорируется, сессии уходят в память Tomcat (identity; вывод, подтвердить тестом) | **тихая регрессия, security-relevant**; нужен тест «`SessionRepository` — `JdbcIndexedSessionRepository`» |
| B4 | Не объявлять `spring-boot-starter-test` напрямую; нужны технологические `*-test` стартеры (`webmvc-test`, `security-test`, `jdbc-test` …) | `testImplementation` в обоих сервисах | компиляция тестов |
| B5 | `@SpringBootTest` больше не даёт MockMvc без `@AutoConfigureMockMvc`; пакет `org.springframework.boot.test.autoconfigure.web.servlet.*` → `org.springframework.boot.webmvc.test.autoconfigure.*` ([javadoc 4.1.1](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/webmvc/test/autoconfigure/AutoConfigureMockMvc.html)) | 5 импортов `AutoConfigureMockMvc`, `MockMvcPrint`, `WebMvcTest` | компиляция тестов |
| B6 | `@MockBean`/`@SpyBean` удалены; `@Mock` без `MockitoExtension` не работает | в коде **0** `@MockBean` — изменений нет | — |
| B7 | Jackson 3 — предпочтительная библиотека; `ObjectMapper`-бины Jackson 2 больше не автоконфигурируются (есть deprecated `spring-boot-jackson2`); `spring.jackson.read/write.*` → `spring.jackson.json.read/write.*`; все модули с classpath регистрируются автоматически | 5 конструкторов инжектят `com.fasterxml…ObjectMapper` (`AccountErrors`, `PostboxMail`, `ApiSecurityErrors`, `AuthoringRepository`, `MediaManifestCatalog`) → «No qualifying bean» | компиляция/старт |
| B8 | Liveness/readiness probes включены по умолчанию | у нас уже `probes.enabled=true` — без изменений | — |
| B9 | Удаление overrides: `tomcat.version=10.1.59` **понизит** Tomcat 11 до 10.1 (несовместим с Servlet 6.1) | `build.gradle.kts` | старт |
| B10 | Netty: Boot 4.1.1 управляет 4.2.17, а AWS SDK тестирован с 4.1.138. В коде только синхронные `S3Client`/`S3Presigner` (0 `S3AsyncClient`), а `netty-nio-client` — лишь runtime-зависимость для async ([services pom](https://repo1.maven.org/maven2/software/amazon/awssdk/services/2.55.10/services-2.55.10.pom)) | рекомендация: `exclude(module = "netty-nio-client")` и удалить override — минус целая сетевая библиотека и её CVE | build-файл (обоснование есть) |
| B11 | Gradle 9 поддерживается; минимум для Boot 4 — Gradle 8.14 | — | — |
| B12 | JDK-`HttpClient`-клиенты, **автоконфигурированные** Boot, при `spring.threads.virtual.enabled=true` используют virtual threads | наши клиенты собраны вручную — эффекта нет; при желании — общий `ClientHttpRequestFactoryBuilder` | — |

**Spring Framework 7** ([7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes)):
Jakarta EE 11 / Servlet 6.1, JUnit 6, JSpecify вместо JSR-305 nullness; `HttpHeaders` больше
не `MultiValueMap` (наш `headers.putAll(entity.getHeaders())` компилируется — `putAll(HttpHeaders)`
есть в 7.0.9); `RestTemplate` помечен устаревшим в документации с 7.0 и получит `@Deprecated` в 7.1;
поддержка Jackson 2 deprecated, автодетект планируют выключить в 7.1 и удалить в 7.2;
`SpringExtension` использует test-method-scoped `ExtensionContext` (риск для `@Nested`).

**Spring Security 7.0/7.1** ([What's new 7.0](https://docs.spring.io/spring-security/reference/7.0/whats-new.html),
[migration](https://docs.spring.io/spring-security/reference/migration/index.html),
[OAuth2 migration](https://docs.spring.io/spring-security/reference/migration/servlet/oauth2.html)):

- `and()`/non-lambda DSL, `authorizeRequests()`, `AntPathRequestMatcher`/`MvcRequestMatcher`
  удалены — **у нас уже 0 использований**, DSL лямбдовый.
- `AuthorizationManager#check` удалён в пользу `authorize`: наши `.access((auth, ctx) -> new AuthorizationDecision(...))`
  должны скомпилироваться (`AuthorizationDecision` реализует `AuthorizationResult`) — проверить.
- Authorization Server влился в Spring Security; `spring-authorization-server.version` больше не работает.
  PKCE включён по умолчанию — наш client `NONE`+PKCE уже соответствует.
- `LoginUrlAuthenticationEntryPoint` по умолчанию делает **относительные** редиректы — наш entry point
  `/login` для `text/html`; проверить browser harness.
- `NimbusJwtDecoder`: default connect/read timeout 30 s и миграционный пункт про
  `JwtTypeValidator` (`typ`) — у нас свой processor, но black-box должен подтвердить, что
  learning принимает access token, выпущенный AS 7.1.
- `DefaultOAuth2UserService.setRestOperations(...)` в 7.1.1 **не deprecated**
  ([javadoc](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/client/userinfo/DefaultOAuth2UserService.html)),
  поэтому `RestTemplate` в `ProviderUsers` пока остаётся — с targeted TODO на момент, когда
  Framework 7.1 пометит его `@Deprecated`.
- Сериализация: `JdbcOAuth2AuthorizationService`/`JdbcRegisteredClientRepository` хранят JSON,
  а Spring Session JDBC — JDK-serialized `SecurityContext`. Совместимость строк, записанных
  6.5, не гарантирована → при апгрейде очистить `oauth2_authorization`, `oauth2_authorization_consent`
  и `spring_session*` (локальные данные одноразовые по owner decision), пользователи
  перелогинятся. Тот же шаг — в rollback.

**Jackson 3** ([migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md),
[`JsonNode` 3.x source](https://github.com/FasterXML/jackson-databind/blob/3.x/src/main/java/tools/jackson/databind/JsonNode.java)):

| # | Изменение Jackson 3 | Влияние на Mnema |
|---|---|---|
| J1 | `com.fasterxml.jackson.*` → `tools.jackson.*` (кроме `com.fasterxml.jackson.annotation`) | 139 файлов, механически (OpenRewrite `org.openrewrite.java.jackson.UpgradeJackson_2_3`) |
| J2 | **Все исключения unchecked**: `JacksonException extends RuntimeException`, **не** `IOException`; `JsonProcessingException` → `JacksonException`, `JsonParseException` → `StreamReadException` | **Критично.** `ContentJsonReader.read` ловит `IOException \| ArithmeticException` — дубликат ключа, превышение `StreamReadConstraints`, trailing tokens больше не превратятся в `IllegalArgumentException("Invalid content JSON")`. Компиляция пройдёт (в `try` есть `CharacterCodingException`), а 11 парсеров команд с `catch (IOException \| IllegalArgumentException)` отдадут **500 через `@ExceptionHandler(Exception.class)` вместо 400**. То же — `CurrentIdentityFilter` (ожидаемый 503 станет 500). |
| J3 | `textValue()`/`asText()`/`isTextual()` — deprecated алиасы `stringValue()`/`asString()`/`isString()`; **семантика изменилась**: `stringValue()` бросает `JsonNodeException` для любого не-String узла, **включая `MissingNode`** (Jackson 2 возвращал `null`); `asString()` бросает для object/array (Jackson 2 давал `""`), а для `null` даёт `""` (Jackson 2 — `"null"`) | 498 вызовов `textValue()`; паттерны вида `switch (value.path("kind").textValue())`, `UUID.fromString(x.path("id").textValue())`, `name.equals(node.path("sub").textValue())` меняют тип ошибки (NPE/false → `JsonNodeException`). Точный эквивалент старого поведения — `stringValue(null)`. AGENTS.md запрещает deprecated API → переименовывать всё, но **не** механическим `textValue()`→`stringValue()`. |
| J4 | `JsonGenerator.writeFieldName` → `writeName`; `JsonFactory` — builder, immutable; `createGenerator(OutputStream)` сохранился | `CanonicalJsonHasher`: байты должны совпасть до и после — **хэши уже лежат в command receipts** (idempotency) |
| J5 | Defaults: включены `FAIL_ON_TRAILING_TOKENS`, `FAIL_ON_NULL_FOR_PRIMITIVES`, `SORT_PROPERTIES_ALPHABETICALLY`; выключены `FAIL_ON_UNKNOWN_PROPERTIES`, `WRITE_DATES_AS_TIMESTAMPS`; RecyclerPool по умолчанию deque-based | порядок полей в JSON-ответах из record/POJO станет алфавитным (контракты по схеме не страдают, snapshot-сравнения строк — да); `null` в примитивное поле request record → 400 вместо `0` (строже, ок); identity явно держит `fail-on-unknown-properties=true` — оставить |
| J6 | `StreamReadFeature.STRICT_DUPLICATE_DETECTION` и `INCLUDE_SOURCE_IN_LOCATION` остались (последний по умолчанию `false`), `ESCAPE_NON_ASCII` — `false` по умолчанию | `ContentJsonReader`/`AccountTransferCodec` переносятся почти 1:1 |

### 1.4 Последовательность (PR-цепочка) и оценка

Порядок владельца (Java → Gradle → Boot → Jackson → Security → Angular/Node → Docker/CI)
стоит чуть переставить: **Gradle раньше Java** (Gradle 8.14 не умеет toolchain 25), а Boot 4.1,
Security 7 и Jackson 3 разделить нельзя — Boot 4.1.1 BOM сразу тянет Security 7.1 и Jackson 3,
а `spring-boot-jackson2` — deprecated мост, который противоречит AGENTS.md §2.1. Поэтому
вместо отдельных «Jackson» и «Security» шагов — подготовительный PR на Boot 3.5 и один
крупный, но механизированный PR Boot 4.1.

| PR | Содержание | Оценка | Gate / доказательства |
|---|---|---|---|
| **P0. Подготовка на Boot 3.5.16 / Java 21** | Gradle 9.8.0 wrapper (+ checksum); JaCoCo 0.8.15; удалить неиспользуемые Kotlin-плагины; **characterization-тесты до миграции**: golden SHA-256 для фиксированного payload `CanonicalJsonHasher`; 400 для дубликата ключа / лимитов / trailing tokens во всех командах; 503 в `CurrentIdentityFilter` при битом userinfo; round-trip `OAuth2Authorization` через JDBC; `SessionRepository` — JDBC; Flyway применил миграции в обоих сервисах. Добавить явный `catch (com.fasterxml.jackson.core.JacksonException …)` рядом с `IOException` вокруг Jackson-вызовов (в 2.x он существует с 2.12 и пока наследует `IOException`), чтобы после смены пакета перехват не потерялся | 1–1,5 d | `./gradlew quality`, coverage ≥ 0.90 |
| **P1. Java 25** | toolchain 25 (оба сервиса), `setup-java` `'25'`, `gradle:9.8.0-jdk25-resolute`, `eclipse-temurin:25.0.4.1_1-jre-resolute` (digest), Mockito как `-javaagent` ([Mockito §0.3](https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html)); обновить живые документы с «Java 21» (`AGENTS.md` §1, `README.md`, `backend/services/*/guide.md`, `docs/deploy/selfhost-local.md`, `docs/engineering/capability-inventory.yaml`, README harness), исторические evidence-файлы не трогать. Boot 3.5.16 поддерживает 17–25 | 0,5 d | quality gate, browser harness, learning-security black-box |
| **P2. Boot 4.1.1 (+Framework 7.0.9, Security 7.1.1, Session 4.1.1, Jackson 3.1.5, JUnit 6, Testcontainers 2.0.5)** | OpenRewrite через **Gradle init script** (без правки build-файлов): `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` → `org.openrewrite.java.jackson.UpgradeJackson_2_3` → `SpringBootProperties_4_1` ([recipes](https://docs.openrewrite.org/recipes/java/jackson/upgradejackson_2_3), [Boot 4.0 CE](https://docs.openrewrite.org/recipes/java/spring/boot4/upgradespringboot_4_0-community-edition)); полного open-source рецепта 4.1 нет. Вручную: стартеры B1–B4, удалить overrides tomcat/postgresql/netty (B9–B10), `ObjectMapper` → `JsonMapper`-бины, J2–J4 (`stringValue(null)` где нужна старая семантика), Testcontainers-артефакты и пакеты, очистка `oauth2_authorization*`/`spring_session*` | 2,5–3,5 d | quality gate; P0 characterization-тесты зелёные **без изменения ожиданий**; browser harness (`--authoring`), black-box |
| **P3. Frontend runtime** | `ng update` → 22.2.1; Node 24.21.0 (CI, `frontend/Dockerfile`, `deploy/local-full-stack/frontend.Dockerfile`, harness README); ESLint 10.11 + angular-eslint 22.5 + typescript-eslint 8.71; удалить `@angular/cdk`; prosemirror patch-апдейты; оценить снятие `withXhr()` (fetch — default с v22; прямой `XMLHttpRequest` для presigned upload остаётся) | 0,5 d | `npm run lint/test/build`, release-contract скрипты |
| **P4. Karma → Vitest** | `@angular/build:karma` → `@angular/build:unit-test` (runner vitest, jsdom); `ng g @schematics/angular:refactor-jasmine-vitest --fake-async`; ручная доводка 104 `jasmine.*` и 46 `spyOn` | 1–1,5 d | все 69 spec, coverage не ниже прежней |
| **P5. Zoneless** | убрать `zone.js` и `provideZoneChangeDetection()`, переписать таймер в `learner-media.component.ts` без `NgZone`, аудит `AppComponent` `Eager` → OnPush | 0,5–1 d | unit + browser harness (reduced motion, keyboard Study) |
| **P6. Images/CI/warnings** | `nginx:1.31.6`, `postgres:18.6` digest, `docker:29.8.x-cli`; `javac -Xlint:all -Werror` (после чистки deprecations Jackson 3) | 0,5 d | `verify_production_image_pins.py`, full gate |
| **Итого** | | **≈ 6,5–9 agent-days** | |

**Риски.** (1) Jackson 3 семантика (J2/J3) — главный, именно поэтому P0 пишет
characterization-тесты *до* миграции. (2) Тихие регрессии B2/B3. (3) Хэши idempotency (J4).
(4) Сериализованные OAuth2/сессии (решается очисткой). (5) `@Nested` + `SpringExtension`.
(6) AWS SDK + Netty 4.2 (снимается исключением `netty-nio-client`). (7) Coverage 0.90 после
удаления/переписывания кода — добавлять тесты, а не снижать baseline.

**Что проверить сверх gate.** `scripts/browser-identity/run.py --authoring` (OIDC login, PKCE,
относительный редирект `/login`, CSRF, Study); `scripts/learning-security` black-box (JWT
`typ`/`aud`, 401/403 коды, отсутствие утечек в ошибках Jackson); ручной `docker compose`
local full stack.

**Rollback.** Каждый PR — отдельный squash-коммит и откатывается `git revert`. Схема БД
от апгрейда не меняется (предположение, что Flyway 12 читает history-таблицу 11.x, см. §5);
при откате P2 повторить очистку `oauth2_authorization*`/`spring_session*`. P1 и P2
независимы: Boot 3.5 и 4.1 оба работают на Java 25, поэтому откат одного не требует отката
другого.

### 1.5 Поднимать ли Java до 25 сейчас — да

- **Совместимость подтверждена**: Boot 3.5 (17–25) и 4.1 (17–26) — [endoflife.date](https://endoflife.date/spring-boot);
  Gradle ≥ 9.1; JaCoCo ≥ 0.8.14; Temurin 25 образы есть для `resolute`/`noble`/`alpine`;
  `setup-java` поддерживает temurin 25; Testcontainers JVM-нейтрален (общается с Docker API).
- **Польза именно для Mnema**: оба сервиса на virtual threads, а с JDK 24 `synchronized`
  больше не пинит carrier thread ([JEP 491](https://openjdk.org/jeps/491)); на маленьком VPS
  важны Compact Object Headers ([JEP 519](https://openjdk.org/jeps/519), в JDK 25 — product
  feature, включается флагом `-XX:+UseCompactObjectHeaders`) и AOT cache для старта
  ([JEP 514/515](https://openjdk.org/projects/jdk/25/)).
- **Не брать 26/27**: не LTS (Temurin EOL 26 — Sep 2026, 27 — Mar 2027). Следующий LTS
  (29) ожидается осенью 2027.
- Отдельным PR P1 до Boot 4 — чтобы JVM-регрессии не смешивались с фреймворковыми.

---

## 2. Self-host STT на VPS 4 vCPU / 8 GB без GPU

### 2.1 Профиль нагрузки

- Клипы **5–15 s** (ответы в упражнениях); в composer/«На потом» диктовка может быть длиннее,
  поэтому лимит клипа — 30–60 s.
- Языки: RU (диктовка на родном) и целевые EN, FR, ES, JA, ZH, KO.
  **Язык почти всегда известен заранее** (язык колоды/упражнения), поэтому можно
  маршрутизировать по языку и не платить за language ID.
- Лимиты из `docs/product/ai-layer-2026-10.md`: Free 60 мин/мес (≤ 10/день), Plus 300,
  Pro 600, Max — fair-use (≤ 120/день).
- На том же VPS работают два JVM-сервиса, PostgreSQL 18, nginx и one-shot media-worker
  (в README он запускается с `--cpus 2 --memory 3g`).

### 2.2 Публичные CPU-бенчмарки

**Главная ловушка Whisper:** энкодер всегда считает 30-секундное окно
([openai/whisper](https://github.com/openai/whisper)). Поэтому 5-секундный клип стоит
почти как 30-секундный. В whisper.cpp это частично лечится параметром `audio_ctx`:
на 200 клипах Common Voice по 5,7 s время упало с 204 s до 60 s, WER 20,06 → 19,2
([whisper.cpp #1855](https://github.com/ggml-org/whisper.cpp/issues/1855)). В faster-whisper
такой опции нет: запрос закрыт как not planned ([#171](https://github.com/SYSTRAN/faster-whisper/issues/171)).

| Модель / runtime | CPU и потоки | Результат | Источник |
|---|---|---|---|
| faster-whisper `small` int8, beam 5, 13 мин аудио | i7-12700K, 8 потоков | 1m42s (**RTF ≈ 0,13**), RAM 1477 MB; `batch_size=8` — 51 s, 3608 MB | [faster-whisper README](https://github.com/SYSTRAN/faster-whisper) |
| то же | Xeon Gold 6226R, 8 потоков | 2m04s (RTF ≈ 0,16), 995 MB | [README v0.10.0](https://github.com/SYSTRAN/faster-whisper/blob/v0.10.0/README.md) |
| whisper.cpp `small` fp32 | i7-12700K, 8 потоков | 2m05s, 1049 MB | faster-whisper README |
| whisper.cpp large-v3-turbo INT8, клип 20 s | AMD EPYC 7V13; 1/2/**4**/8 потоков | 102,1 / 53,3 / **28,4** / 15,5 s (на 4 потоках RTF ≈ 1,4) | [arXiv 2607.21075, Table 5](https://arxiv.org/html/2607.21075) |
| transcribe.cpp (ggml), Q8_0, клип 11 s, **CPU-backend** | AMD Ryzen 7 PRO 4750U (8C/16T, число потоков не указано) | base 0,65 s; small 2,17 s; medium 6,93 s; **turbo 9,85 s**; **Qwen3-ASR-0.6B 1,86 s**; Qwen3-ASR-1.7B 4,10 s; Parakeet-TDT-0.6B-v3 0,73 s; GigaAM-v3 (RU) 0,82 s | [whisper-large-v3-turbo](https://github.com/handy-computer/transcribe.cpp/blob/main/docs/models/whisper-large-v3-turbo.md), [qwen3-asr-0.6b](https://github.com/handy-computer/transcribe.cpp/blob/main/docs/models/qwen3-asr-0.6b.md) и соседние страницы `docs/models/` |
| sherpa-onnx Qwen3-ASR-0.6B int8, greedy | 2 потока, машина не указана | RTF 0,103 (334 s EN), 0,077 (272 s ZH), 0,146 (клип 20,8 s) | [sherpa docs](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html) |
| Parakeet-TDT-0.6B-v3, ONNX Runtime int8 | AMD EPYC 9V74, 8 vCPU | RTF 0,038 (dynamic int8), 0,018 (static QDQ); параллельные сессии ухудшали результат | [heyneo case study](https://heyneo.com/blog/parakeet-cpu-optimization-case-study) |

Turbo на CPU медленнее medium, потому что сохраняет полный 32-слойный энкодер large;
выигрыш turbo — в декодере, а для коротких клипов это не узкое место.

### 2.3 Перенос на 4 vCPU (оценка, не замер)

vCPU облака — это обычно гиперпоток серверного Xeon/EPYC. Четыре таких vCPU я оцениваю
примерно в 0,4–0,65 от всего 4750U, два — в 0,25–0,35. Масштабирование по потокам
сублинейное: на EPYC переход 4 → 8 потоков дал лишь 1,83×. Отсюда:

| Модель (Q8/int8), клип ≈ 10 s | Латентность, 4 vCPU на один запрос | Латентность, 2 vCPU (лимит контейнера) | RSS (ориентир) | Вывод |
|---|---|---|---|---|
| Whisper base | 1,0–1,6 s | 2–2,6 s | ~0,4 GB | быстро, но для RU/JA/KO/ZH качество слабое (см. 2.4) |
| Whisper small | 3,3–5,4 s | 6,5–9 s | 0,9–1,5 GB | на грани интерактива; KO/ZH слабые |
| Whisper medium | 10–17 s | 20–28 s | ~2,1 GB | не интерактивно |
| Whisper large-v3-turbo | 15–25 s (с EPYC-замером сходится: 28 s на 20-секундный клип) | 30–40 s | 1,6–2 GB | **на этом VPS не годится** |
| **Qwen3-ASR-0.6B** | 2,8–4,7 s (по sherpa-onnx возможно и 1,5–3 s) | 3–7 s | 1,5–3 GB (антирез-порт, bf16: 2,8–3,2 GiB) | **лучший универсальный кандидат** |
| Qwen3-ASR-1.7B | 6–10 s | 12–15 s | 6,7–7,1 GiB (антирез-порт) | **не помещается** рядом с приложением |
| Parakeet-TDT-0.6B-v3 | 1,1–1,8 s | 2,2–3 s | ~2 GB | RU/EN/FR/ES, без CJK |
| GigaAM-v3 CTC | 1,2–2 s | 2,5–3,3 s | ~0,3 GB | только RU, лучшее RU-качество |

Числа — экстраполяция. **Перед решением нужен замер на реальном VPS** (как и требует
economics research): в faster-whisper есть отчёт, где на Xeon Gold 6230 4 потока работали
хуже одного ([#526](https://github.com/SYSTRAN/faster-whisper/issues/526)).

### 2.4 Качество по языкам

Whisper, FLEURS (paper, Table 13; для JA/ZH фактически CER) —
[arXiv 2212.04356](https://arxiv.org/pdf/2212.04356):

| Язык | base | small | medium | large-v2 |
|---|---|---|---|---|
| EN | 8,9 | 6,1 | 4,4 | 4,2 |
| ES | 9,9 | 5,6 | 3,6 | 3,0 |
| FR | 28,5 | 15,0 | 8,7 | 8,3 |
| RU | 20,5 | 11,4 | 7,2 | 5,6 |
| JA | 22,8 | 12,0 | 7,1 | 5,3 |
| KO | 27,8 | 19,6 | 16,4 | 14,3 |
| ZH | 34,1 | 20,8 | 12,1 | 14,7 |

large-v3, по OpenAI, ошибается на 10–20% меньше large-v2
([discussion #1762](https://github.com/openai/whisper/discussions/1762)); turbo — примерно
уровень large-v2 ([#2363](https://github.com/openai/whisper/discussions/2363)).

Альтернативы, FLEURS. Это **замеры авторов**, метрики WER/CER между отчётами могут не
совпадать — особенно для KO:

| Язык | Qwen3-ASR-0.6B | Qwen3-ASR-1.7B | Parakeet-v3 | GigaAM-v3 |
|---|---|---|---|---|
| EN | 4,39 | 3,35 | 4,85 | — |
| ES | 4,94 | 3,36 | 3,45 | — |
| FR | 7,72 | 4,75 | 5,15 | — |
| RU | **9,91** | 5,99 | 5,51 | CV19: 1,3 (vs Whisper-large-v3 5,5) |
| JA | 8,33 | 5,20 | — | — |
| KO | 3,72 | 2,57 | — | — |
| ZH | 2,88 | 2,41 | — | — |

Источники: [Qwen3-ASR report](https://arxiv.org/html/2601.21337v1),
[Parakeet v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3),
[GigaAM evaluation](https://raw.githubusercontent.com/salute-developers/GigaAM/main/evaluation.md).

Выводы: `small`/`base` для CJK недостаточны. Qwen3-ASR-0.6B хорош для ZH/KO, приемлем для
EN/ES/JA, **слаб для RU**. RU лучше отдать GigaAM.

**Оговорка про учебную речь.** Декодеры с языковой моделью (Whisper, Qwen) склонны
«исправлять» неправильно произнесённое слово, а CTC-модели (GigaAM-CTC, T-one) транскрибируют
буквальнее. Для упражнения с устным ответом это означает риск ложного «верно». Нужен
собственный eval на речи учащихся (метрика — доля ответов, оценённых так же, как оценил бы
человек), а не только WER.

### 2.5 Лицензии и языки альтернатив

| Модель | Лицензия | Языки из набора | Комментарий |
|---|---|---|---|
| Qwen3-ASR 0.6B/1.7B | Apache-2.0 ([HF](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)) | все 7 (30 языков + 22 диалекта) | CPU через sherpa-onnx (есть Java-биндинги) или ggml-порты |
| NVIDIA Parakeet-TDT-0.6B-v3 | CC-BY-4.0 (нужна атрибуция) | RU, EN, FR, ES (25 европейских) | без CJK |
| NVIDIA Canary-1B-v2 | CC-BY-4.0 | те же 25 | ≥ 6 GB RAM, GPU-ориентирована |
| GigaAM v3 (Sber) | MIT ([repo](https://github.com/salute-developers/GigaAM)) | только RU | short-form до 25 s, ONNX |
| T-one (T-Bank) | Apache-2.0 ([repo](https://github.com/voicekit-team/T-one)) | только RU | streaming, 71M, телефония |
| Moonshine | MIT — EN, а также JA/ES/ZH streaming; KO — некоммерческая Community License | без RU и FR | [models](https://huggingface.co/api/models?author=moonshine-ai&limit=100) |
| Vosk | Apache-2.0 | все 7 | точность заметно ниже Whisper/Qwen ([models](https://alphacephei.com/vosk/models)) |
| Silero STT | CC-NC-BY; коммерция — по запросу | публично en/de/es | для нас не подходит ([repo](https://github.com/snakers4/silero-models)) |
| whisper.cpp / faster-whisper / sherpa-onnx | MIT / MIT / Apache-2.0 | — | runtime |

### 2.6 Как не уронить Spring-процесс на том же VPS

- **Отдельный долгоживущий контейнер `stt-worker`.** Не one-shot: загрузка модели занимает
  секунды. Лимиты `cpus: 2`, `mem_limit: 2.5g` = `memswap_limit` (без swap),
  `cpu_shares` ниже, чем у JVM и Postgres, `pids_limit`; сеть `internal: true` без egress;
  read-only FS; модели монтируются с SHA-256 pin.
- **Очередь с backpressure в learning.** Семафор на N=1–2 запроса в STT и ограниченная
  очередь (например, 20). При переполнении — `503` с `Retry-After` в стабильном формате
  ошибок; в UI — «распознаём…». Таймаут клипа ~20 s. Минуты Free списываются только после
  успешной транскрипции. Если нужна долговечность — таблица `stt_job` с
  `FOR UPDATE SKIP LOCKED` (новая инфраструктура не нужна).
- **Предобработка на клиенте.** Браузер отдаёт 16 kHz mono PCM/WAV (10 s ≈ 320 KB) или
  Opus; серверу не нужен FFmpeg (у media-worker это GPL-сборка — см. его README).
  Silero VAD (MIT) обрезает тишину: меньше CPU и меньше галлюцинаций Whisper на тишине.
- **Изоляция тяжёлых задач.** media-worker (до 3 GB / 2 CPU) и STT не должны
  выполняться одновременно на 8 GB. Нужен общий «heavy-job» семафор или уменьшенный лимит
  media-worker.
- **Бюджет RAM на 8 GB** (ориентир): ОС + Docker ~0,5 GB; Postgres ~1,5 GB; два JVM
  ~1,3–1,7 GB (Java 25 + `-XX:+UseCompactObjectHeaders` экономит heap); nginx ~0,05 GB;
  STT 1,5–2,5 GB (Qwen3-ASR-0.6B + GigaAM). Итого ≈ 5–6,3 GB без media-worker.
- **Приватность.** Аудио не сохранять: после транскрипции удалять, в логах — только
  длительность и язык. Нужно отдельное согласие на голос (уже в legal gates).

### 2.7 Ёмкость и пороги

Клип в среднем 10 s, т. е. 6 клипов на минуту аудио; в пиковый час — 15% дневных клипов.

| Сценарий | Аудио/мес | Клипов/мес | Пик, клипов/ч | Qwen3-0.6B на 2 vCPU (≈ 500–1 200 клипов/ч) |
|---|---|---|---|---|
| 100 MAU × 10 мин | 1 000 мин | 6 000 | ≈ 30 | запас > 15× |
| 1 000 MAU, 30% пользуются, по 20 мин | 6 000 мин | 36 000 | ≈ 180 | запас 3–6× |
| 1 000 MAU × 20 мин | 20 000 мин | 120 000 | ≈ 600 | **на пределе** |
| 1 000 MAU × 60 мин (весь Free cap) | 60 000 мин | 360 000 | ≈ 1 800 (дневной лимит 10 мин сглаживает) | **нужен отдельный узел** |

- **Старт:** STT на том же VPS в контейнере с 2 vCPU — до ~400 пользователей, реально
  наговаривающих ~20 мин/мес.
- **Порог переезда на отдельный CPU-узел:** p95 ожидания в очереди > 3 s или загрузка
  STT-контейнера > 50% в пиковый час. Это примерно 250–600 клипов в пиковый час, то есть
  400–1 000 пользователей по ~20 мин/мес (при доле таких 30% — 1,3–3,3 тыс. MAU). Узел 8 vCPU / 12 GB — около 2 790 ₽/мес (Timeweb,
  economics research).
- **GPU:** нужен при «live»-STT, при желании иметь large-v3-класс для CJK на тысячах MAU,
  или выше ~3 000 часов аудио в месяц. Зарубежный GPU (Hetzner и т. п.) — вне игры: EU
  запрещает HPC/GPU-доступ российским юрлицам (Art. 5n(1)(h), FAQ 39 ниже).

### 2.8 Внешние STT-API и санкционные оговорки

| API | Цена | Языки | Доступ для оператора из РФ |
|---|---|---|---|
| Groq whisper-large-v3-turbo | **$0.04/ч**, минимум **10 s** на запрос; large-v3 — $0.111/ч ([docs](https://console.groq.com/docs/speech-to-text)) | multilingual | Services Agreement: Export Control Laws = EAR/OFAC/ITAR; запрещено использование, нарушающее их (§6.3), немедленное расторжение (§11.4); списка стран нет ([agreement](https://console.groq.com/docs/legal/services-agreement)). Оплата картой, данные в US → трансграничная передача голоса |
| Mistral Voxtral Mini Transcribe V2 | $0.003/мин ([news](https://mistral.ai/news/voxtral-transcribe-2/)) | 13, включая все 7 | ToS §14.13 (с 2026-09-25): клиент не находится в странах под комплексными санкциями EU/US/Singapore; Россия прямо не названа ([terms](https://legal.mistral.ai/terms/commercial-terms-of-service)). **Решающее — Регламент ЕС 833/2014 Art. 5n(1)(g)**: FAQ 38 Еврокомиссии (обновлён 2026-01-22) прямо называет перевод и транскрибацию голоса среди примеров запрещённого API-доступа к моделям для российских юрлиц. Физлиц запрет не касается (FAQ 13), но статус ИП — вопрос к юристу ([EC FAQ](https://finance.ec.europa.eu/publications/provision-services_en)) |
| OpenAI transcribe | $0.003–0.006/мин | — | Россия не в списке поддерживаемых стран ([supported countries](https://developers.openai.com/api/docs/supported-countries)) |
| **Yandex SpeechKit STT** | sync/streaming **0,1626 ₽ за 15 s** (с НДС, < 15 s округляется вверх); async 0,1515 ₽/15 s; deferred 0,0381 ₽/15 s ([pricing](https://aistudio.yandex.ru/docs/ru/speechkit/pricing)); sync ≤ 30 s и 1 MB | ru, en, de, es, fr, it, pt, tr… — **нет KO/JA/ZH** ([models](https://aistudio.yandex.ru/docs/ru/speechkit/stt/models)) | российский контрагент; для коротких клипов дорого: при 15-секундных клипах 0,65 ₽ за минуту аудио, при 10-секундных 0,98 ₽, при 5-секундных 1,95 ₽ |
| VK Cloud Voice | 0,50 ₽/мин ([tariffs](https://cloud.vk.ru/docs/en/ml/cloud-voice/tariffication)) | вероятно, только RU (не подтверждено) | российский; статус и НДС уточнить |
| SaluteSpeech | — | ru, en, kk, ky, uz | **закрыт для новых клиентов с 2026-07-15** ([auth docs](https://developers.sber.ru/docs/ru/salutespeech/api/authentication)) |

Стоимость при 20 000 мин/мес (120 тыс. клипов по 10 s): Groq ≈ $13–15 (≈ 1,1–1,25 тыс. ₽),
Yandex sync ≈ 19,5 тыс. ₽, Voxtral ≈ $60. **Деньги не проблема — проблема в праве и оплате.**
Для KO/JA/ZH легального российского API нет вовсе, поэтому self-host для CJK — не опция,
а необходимость.

### 2.9 «Live»-STT (streaming) — на будущее

| Вариант | Языки | Лицензия | Стоимость |
|---|---|---|---|
| sherpa-onnx streaming zipformer | EN, ZH, KO, zh-en (RTF 0,06–0,16 на 1–2 потоках) | Apache-2.0 | ≈ 0,1–0,2 ядра на поток → 2 vCPU ≈ 10–20 потоков ([docs](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)) |
| T-one в sherpa-onnx | RU | Apache-2.0 | то же |
| Moonshine streaming | EN, JA, ES, ZH | MIT | малые модели, CPU |
| Vosk | все 7 | Apache-2.0 | дёшево, качество ниже |
| WhisperLive / whisper_streaming (→ SimulStreaming) | все | MIT | повторно кодирует 30-s окна: > 1 ядра на поток, по факту GPU ([WhisperLive](https://github.com/collabora/WhisperLive), [whisper_streaming](https://github.com/ufal/whisper_streaming)) |

Практичная схема — two-pass: streaming-модель даёт частичный текст, а на конце фразы
финальный результат считает offline-модель (Qwen3/GigaAM). Для FR хорошей streaming-модели,
кроме Vosk, нет.

### 2.10 Рекомендация по STT

1. **Self-host, маршрутизация по языку:** RU → **GigaAM-v3** (MIT); EN/FR/ES/JA/ZH/KO →
   **Qwen3-ASR-0.6B** int8 (Apache-2.0) через sherpa-onnx; позже, по итогам eval, возможно
   **Parakeet-v3** для FR/ES. Runtime — отдельный контейнер на 2 vCPU / 2,5 GB с очередью.
2. **Не запускать** turbo/medium/1.7B на этом VPS; Whisper `small` — только как запасной
   вариант, если Qwen3 провалит eval.
3. **Обязательный бенчмарк** на целевом VPS: 50–100 клипов на язык, 3–60 s, речь учащихся,
   шум. Метрики — p50/p95 латентности при 1 и 2 параллельных запросах, RSS, WER/CER и
   «согласие оценки упражнения с человеком».
4. **Fallback:** для RU/EN/FR/ES — Yandex SpeechKit (по деньгам дорого, но легально);
   для CJK внешнего легального fallback нет — деградация до «повторите позже» и очередь.
   Groq — только после юриста и при легальной оплате; Mistral — не использовать.

---

## 3. Качественный TTS, доступный оператору из РФ

### 3.1 Российские вендоры

| Вендор | Цена | Языки из набора | Возможности | Доступ и terms |
|---|---|---|---|---|
| **Yandex SpeechKit** (AI Studio) | **API v1: 1 342 ₽ за 1M символов с НДС** (≈ $16,1; пробелы и знаки считаются); **API v3 `general`: 0,1626 ₽ за запрос** до 250 символов, `livetts` и Realtime API — 0,25 ₽ за блок 250 символов; Brand Voice Lite — 101 666 ₽/мес + 9 150 ₽ разово ([pricing](https://aistudio.yandex.ru/docs/ru/speechkit/pricing)) | **RU** (~18 голосов, часть — только v3), **EN — один голос `john`**; ещё de, he, kk, uz. **FR, ES, JA, ZH, KO — нет** ([voices](https://aistudio.yandex.ru/docs/ru/speechkit/tts/voices)) | SSML (v1) и TTS-разметка: ударение `+`, фонемы `[[…]]`, паузы; WAV / OGG Opus / MP3 / LPCM; 40 RPS; v1 до 5 000 символов на запрос, v3 — 250 символов / 24 s (unsafe mode и streaming — 5 000) ([limits](https://aistudio.yandex.ru/docs/ru/speechkit/concepts/limits.html)) | платёжный аккаунт для юрлица или ИП ([billing](https://github.com/yandex-cloud/docs/blob/master/ru/billing/concepts/billing-account.md)). Terms (ред. 2026-04-01, [cloud_terms_speechkit](https://yandex.ru/legal/cloud_terms_speechkit/)): §5.1 — клиент вправе использовать сгенерированный контент любым способом, не противоречащим условиям и закону (кэш и показ многим пользователям допустимы); §1.3.1 — доступ конечных пользователей разрешён; §4.4 — данные идут в обучение **только если клиент сам включил** эту возможность; логирование запросов по умолчанию выключено (`x-data-logging-enabled`, [support headers](https://aistudio.yandex.ru/docs/ru/speechkit/concepts/support-headers)) |
| SaluteSpeech (Sber) | раньше 186 ₽/1M для юрлиц | RU, EN | — | **закрыт для новых клиентов с 2026-07-15**: ни пакетов, ни pay-as-you-go ([legal tariffs](https://developers.sber.ru/docs/ru/salutespeech/tariffs/legal-tariffs), [auth](https://developers.sber.ru/docs/ru/salutespeech/api/authentication)) |
| VK Cloud Voice | 1 ₽ за 1 000 символов (≈ 1 000 ₽/1M; НДС не указан) ([tariffs](https://cloud.vk.ru/docs/en/ml/cloud-voice/tariffication)) | RU (голоса katherine/maria/pavel — по сводке поиска) | mp3/opus/pcm, до 2 000 символов | российский; актуальный статус сервиса и НДС уточнить |
| T-Bank VoiceKit | индивидуально, только юрлица, договор | RU (Anna, Dima) | — | страницы не загрузились, данные — из сводки поиска |
| MWS AI Audiogram | по запросу | RU | SSML, SaaS gRPC или on-prem | по сводке поиска |
| Silero | коммерческая лицензия — по запросу (hello@silero.ai) | RU (v5); EN/FR/ES — старые v3; **нет JA/ZH/KO** | v5 на CPU ≈ 37–42 s аудио за 1 s на одном потоке ([Habr](https://habr.com/ru/articles/961930/)), омографы | открытые модели — CC-NC-BY, кроме `v5_cis_base*` под MIT ([repo](https://github.com/snakers4/silero-models)) |

**Нюанс цены Yandex.** API v3 тарифицирует каждый начатый блок в 250 символов. Слово из
10 символов в v1 стоит 0,0134 ₽, в v3 — 0,1626 ₽ (в 12 раз дороже). Для слов и коротких
фраз — **только v1**; v3 имеет смысл для streaming и длинных текстов.

### 3.2 Зарубежные API (качество выше, но оплата из РФ — главный барьер)

Карты российских банков за рубежом не работают с марта 2022
([Visa](https://usa.visa.com/about-visa/newsroom/press-releases.releaseId.18871.html)). Любой
вариант ниже требует легального зарубежного способа оплаты, а посредники могут нарушать
terms вендора.

| Вендор | Цена за 1M символов | Языки | AA Speech Arena (EN, US/UK) | Terms |
|---|---|---|---|---|
| **MiniMax** speech-2.8 HD / Turbo | **$100 / $60** (≈ 8 325 / 4 995 ₽) ([paygo](https://platform.minimax.io/docs/guides/pricing-paygo)) | все 7; синхронно < 10 000 символов; mp3/wav/flac/pcm/opus ([T2A](https://platform.minimax.io/docs/api-reference/speech-t2a-http.md)) | **#18** (Elo 1170) / **#20** | контрагент — Nanonoble Pte. Ltd. (Сингапур), app/web terms требуют соблюдать экспортное законодательство ([terms](https://www.minimax.io/audio/doc/terms-of-service.html)); API terms рендерятся JS и не прочитаны |
| **Alibaba Model Studio** (international, Сингапур) | qwen3-tts-flash ≈ $10 (**не подтверждено**, сниппет официальной страницы); Qwen-Audio-3.0-TTS-Plus — 1,49884 CNY за 10k символов | Qwen3-TTS-Flash: zh/en/de/it/pt/es/ja/ko/fr/ru ([TTS models](https://www.alibabacloud.com/help/en/model-studio/tts-model/)); Qwen-Audio-3.0: 16 языков | Qwen-Audio-3.0-TTS-Plus **#4** (Elo 1259); Qwen3 TTS Flash #79 | §3.6 — export control и санкции, приостановка по усмотрению Alibaba ([product terms](https://www.alibabacloud.com/help/en/legal/latest/alibaba-cloud-international-website-product-terms-of-service-v-3-8-0)); URL аудио живёт 24 h → копировать к себе ([qwen-tts](https://www.alibabacloud.com/help/en/model-studio/qwen-tts)) |
| Fish Audio S2.1 Pro | $15 за 1M **UTF-8 байт** → RU ≈ $30, CJK ≈ $45 за 1M символов ([pricing](https://docs.fish.audio/developer-guide/models-pricing/pricing-and-rate-limits)) | все 7 (ru/ko/es/fr — tier 2) | #22 | Hanabi AI Inc. (Делавэр); запрет экспорта в страны под эмбарго США ([terms](https://fish.audio/terms/)); открытые веса S2-Pro — некоммерческая лицензия |
| ElevenLabs v4 | $22–80 | все | **#1** (Elo 1320) | §15: пользователь заверяет, что не находится в стране или на территории под экономическими санкциями ([terms](https://elevenlabs.io/terms-of-use)); Россия в restricted-списке — исключено |
| Google Chirp 3 HD / Gemini 3.8 Flash TTS | $30 / токены | все | #57 / **#3** | Google Cloud не принимает российских клиентов с 2022; Gemini — Россия не в регионах — исключено |
| Azure Neural | ≈ $15 | все | — | продажи в РФ приостановлены в 2022 — исключено |

Рейтинг: [Artificial Analysis TTS leaderboard](https://artificialanalysis.ai/text-to-speech/leaderboard),
92 модели. Он **только англоязычный** (акценты US/UK), поэтому качество RU и CJK не
измеряет. Независимого русского рейтинга нет; Yandex и Silero в нём отсутствуют.

### 3.3 Self-host с коммерческой лицензией

| Модель | Лицензия (код + веса) | Языки из набора | Качество | Железо |
|---|---|---|---|---|
| **Qwen3-TTS** 0.6B / 1.7B | Apache-2.0 ([repo](https://github.com/QwenLM/Qwen3-TTS)) | все 7 (10 языков) | AA EN-arena **#82** (Elo 930), т. е. заметно ниже Chirp 3 HD; у авторов RU WER 3,21 против 4,28 у MiniMax ([report](https://arxiv.org/html/2601.15621v1)); **нет управления русским ударением**, омографы (замок) читаются неверно ([discussion #185](https://github.com/QwenLM/Qwen3-TTS/discussions/185)) | GPU; по сторонним данным 4–6 GB VRAM, около real-time без оптимизаций |
| Fun-CosyVoice 3 (0.5B) | Apache-2.0 ([HF](https://huggingface.co/FunAudioLLM/Fun-CosyVoice3-0.5B-2512)) | все 7 | в AA нет | GPU |
| Chatterbox Multilingual (0.5B) | MIT ([HF](https://huggingface.co/ResembleAI/chatterbox)) | все 7 (23 языка) | Chatterbox HD — AA **#34** (EN); каждый файл несёт водяной знак Perth | GPU рекомендуется |
| Kokoro-82M | Apache-2.0 | EN, FR, ES, JA, ZH (у ZH-голосов низкие оценки); **нет RU и KO** ([VOICES.md](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md)) | AA **#50** | **CPU**, быстро |
| Silero v5 | CC-NC-BY → нужна коммерческая лицензия (кроме MIT `v5_cis_base`) | RU | сильный RU на CPU (по отзывам; независимого замера нет) | CPU |
| Piper (`piper1-gpl`) | GPL-3.0 + отдельная лицензия у каждого голоса; в docs проект позиционируется для личного использования и исследований ([VOICES](https://github.com/OHF-Voice/piper1-gpl/blob/main/docs/VOICES.md)) | RU, EN, FR, ES; нет JA/KO | низкое | CPU |
| Не годятся | XTTS-v2 (CPML, Coqui закрыт), Fish S2-Pro / OpenAudio S1-mini (некоммерческие), F5-TTS (CC-BY-NC, только zh/en), IndexTTS-2.5 (нужно разрешение bilibili), Higgs Audio (некоммерческая), VibeVoice (research-only), Orpheus (нет RU) | | | |

**Железо.** Модели класса Qwen3-TTS / CosyVoice / Chatterbox практичны только на GPU
(≥ 8 GB VRAM). На VPS 4 vCPU / 8 GB реально работают лишь Kokoro (без RU/KO) и Silero (RU,
нужна лицензия). Скорость Qwen3-TTS на CPU не нашлась — считать её неинтерактивной.
Поскольку озвучка детерминирована и кэшируется, GPU не нужен постоянно: достаточно
**почасовой аренды GPU в российском облаке под пакетную генерацию** (тарифы
Selectel / Yandex Cloud / Cloud.ru / Timeweb не проверялись). Зарубежный GPU для
российского юрлица закрыт: Art. 5n(1)(h), FAQ 39 Еврокомиссии.

Наследие v1 — `scripts/local-audio-gateway` и `docs/deploy/model-matrix.md` (Piper,
Qwen3-TTS, Kokoro). Новый дизайн на нём строить не стоит: у Piper GPL и лицензии голосов,
у Kokoro нет RU/KO.

### 3.4 Матрица primary / fallback / self-host

| Язык | Primary | Fallback | Self-host |
|---|---|---|---|
| **RU** | **Yandex SpeechKit API v1** (1 342 ₽/1M с НДС; ударения через `+`; terms §5.1 разрешают кэш и переиспользование) | VK Cloud Voice (≈ 1 000 ₽/1M) после проверки статуса; Silero по коммерческой лицензии | Silero v5 (лицензия) на CPU; Qwen3-TTS — только с собственным словарём ударений |
| **EN** | Yandex `john` — **только если пройдёт слепой eval**; иначе MiniMax или Alibaba при легальной оплате | Kokoro (CPU, Apache-2.0, #50) | Kokoro; Chatterbox (GPU) |
| **FR, ES** | MiniMax / Alibaba при легальной оплате | — | Chatterbox-ML / CosyVoice3 / Qwen3-TTS, пакетно на почасовом GPU; Kokoro (CPU) — запасной вариант |
| **JA, ZH, KO** | MiniMax / Alibaba при легальной оплате | — | Qwen3-TTS / CosyVoice3 (китайские модели, в CJK должны быть сильнее — проверить eval), пакетно на GPU; Kokoro — JA/ZH, но не KO |

**Честный вывод.** Озвучку уровня Google можно легально получить из РФ **только для
русского** (SpeechKit). Для FR/ES/JA/ZH/KO два пути: (a) решить вопрос легальной оплаты
MiniMax или Alibaba (сингапурские контрагенты, явного исключения России в найденных terms
нет, но есть оговорки об экспортном контроле и праве приостановить аккаунт) или
(b) принять качество self-host (Qwen3-TTS — #82 в англоязычной арене против #57 у Chirp 3 HD).
Нужен **слепой MOS-eval носителями**: 30 фраз на язык, 3–5 голосов-кандидатов.

**Масштаб затрат при кэше** (пример агента-исследователя, моя проверка арифметики):
7 языков × (20k слов × 8 символов + 20k предложений × 60 символов) ≈ 9,5M символов —
разовая генерация словаря. Стоимость: MiniMax HD ≈ $950 (≈ 79 тыс. ₽), Turbo ≈ $570,
qwen3-tts-flash ≈ $95 (если цена подтвердится); русская часть в Yandex v1 (1,36M символов)
≈ 1 825 ₽. После этого почти все воспроизведения — cache hit.

### 3.5 Дизайн кэша озвучки

Цель: один и тот же текст одним и тем же голосом синтезируется **один раз на всю
платформу**, а credits списываются только при cache miss (как уже принято в
`docs/product/ai-layer-2026-10.md`).

**Ключ.** `cache_key = SHA-256(canonical JSON)` от:

| Поле | Пример | Зачем |
|---|---|---|
| `schema` | `tts-cache/1` | смена правил нормализации → новый ключ, а не порча старого |
| `text` | нормализованный текст | см. ниже |
| `lang` | BCP-47 `ru-RU`, `ja-JP`, `zh-CN` | у некоторых вендоров язык задаётся отдельно от голоса |
| `provider` / `model` / `modelVersion` | `yandex` / `speechkit-v1-general` / дата или версия голоса | вендор меняет модель — меняется звук |
| `voice` + параметры | `alena`, `role=neutral`, `speed=1.0` | любые параметры синтеза |
| `format` | `ogg-opus-48k-32kbps` (или `mp3-44k-64kbps`) | один текст — разные контейнеры |
| `lexiconVersion` | `1` | собственные правила ударений/исключений |

Каноникализацию JSON можно сделать уже существующим `CanonicalJsonHasher` — он даёт
стабильные байты и SHA-256.

**Нормализация текста — консервативная.** Unicode NFC, trim, схлопывание пробелов,
единые кавычки и тире. **Не** складывать `ё`→`е`, не менять регистр (аббревиатуры
читаются по-разному), не трогать знаки ударения и разметку вендора (`+` у Yandex), не
снимать пунктуацию — она влияет на интонацию. Пользовательский текст в SSML
экранировать; сырой SSML от пользователей не принимать (иначе SSML-injection: паузы на
минуты, чужие голоса, внешние `<audio>`).

**Хранение.**
- Аудио — в уже существующем S3-совместимом storage (MinIO локально, Yandex Object Storage
  в проде) по контент-адресу `tts/{key[0:2]}/{key}.{ext}`, immutable; при желании —
  через существующий media-pipeline (variants, SHA-256, `READY` только после проверки).
- Метаданные — таблица в схеме learning, например `tts_clip(cache_key PK, provider, model,
  model_version, voice, lang, format, object_key, byte_length, duration_ms, sha256,
  status PENDING|READY|FAILED, lease_until, created_at, last_hit_at, hit_count)`.
  **Без `account_id`**: учёт расхода — в отдельном usage-ledger, чтобы кэш не превращался в
  журнал того, кто что озвучивал.
- **Single-flight:** `INSERT … ON CONFLICT (cache_key) DO NOTHING` + lease. Параллельные
  запросы того же текста ждут `READY`, а не платят вендору дважды. Вызов вендора — с
  idempotency и retry на 5xx/429 с backoff.
- **Переиспользование между пользователями** безопасно, потому что ключ — хэш, а
  содержимое детерминировано. При копировании публичной колоды аудио не синтезируется
  заново: LearningItem ссылается на `cache_key`/asset.
- **Pre-warm:** при публикации LearningItem или каталожной колоды — фоновая озвучка
  (очередь с лимитом RPS вендора), чтобы ученик не ждал.
- **GC:** удалять только клипы, на которые не ссылается ни один LearningItem, и по
  `last_hit_at` старше N дней. Клипы с ссылками закреплены.
- **Права.** Для Yandex SpeechKit хранение и показ многим пользователям прямо разрешены
  (terms §5.1, см. 3.1); для любого другого вендора это нужно подтвердить до включения.
  При смене вендора старые клипы остаются валидны, пока лицензия это позволяет.
- **Метрики:** hit ratio по языкам, стоимость на 1k воспроизведений, p95 времени до
  `READY`, доля `FAILED` по вендору.

---

## 4. Web search для фактчека с мировым покрытием

### 4.1 Кандидаты (цены на 2026-10-02, 83,25 ₽/$)

| Провайдер | Цена за запрос | Покрытие и языки | Оплата / terms для оператора из РФ | Вердикт |
|---|---|---|---|---|
| **Yandex Search API** (Yandex AI Studio) | sync день **0,488 ₽** (488 ₽/1k с НДС; $4/1k без НДС), ночь 0,366 ₽; **deferred 0,0305 ₽** (ночь 0,0254 ₽); generative 5,08 ₽; ошибки сервера/авторизации не тарифицируются — [pricing](https://aistudio.yandex.ru/docs/ru/search-api/pricing) | типы поиска RU, TR, **COM («международный»)**, KK, BE, UZ; у COM — только локализация EN, `region` работает только для RU/TR; до 250 результатов; XML/HTML; generative-ответ — только ru/kk/uz — [web search](https://aistudio.yandex.ru/docs/ru/search-api/concepts/web-search.html), [generative](https://aistudio.yandex.ru/docs/en/search-api/concepts/generative-response); квоты sync 10 RPS / 10 000 в час, deferred 10 RPS / 35 000 в час, запрос ≤ 400 символов / 40 слов — [limits](https://aistudio.yandex.ru/docs/en/search-api/concepts/limits) | российский контрагент, платёжный аккаунт «юрлицо или ИП», безнал — [billing](https://yandex.cloud/ru/docs/billing/operations/create-new-account); terms: нельзя менять порядок результатов (§3.2), нельзя строить конкурирующий поиск (§3.3.1); про кэширование/хранение terms молчат — [terms](https://yandex.ru/legal/cloud_terms_search_api/index.html) | **Primary** |
| Perplexity Search API | **$0.005** ($1/1k fast); до 5 queries в одном request, контент страниц включён; Agent API: `web_search` $0.0025, `fetch_url` $0.0005 — [pricing](https://docs.perplexity.ai/docs/getting-started/pricing) | `country`, `search_language_filter` (до 10 языков), до 20 результатов — [quickstart](https://docs.perplexity.ai/docs/search/quickstart) | общая US-embargo оговорка (§11.9, текст получен из сниппета, страница отдала 403); практический блокер — оплата: карты РФ за рубежом не работают ([Visa](https://usa.visa.com/about-visa/newsroom/press-releases.releaseId.18871.html)) | fallback только при легальном способе оплаты |
| Brave Search API | **$0.005** (Search), Answers $4/1k + $5/1M токенов; $5 бесплатного кредита в месяц; 50 QPS — [brave.com/search/api](https://brave.com/search/api/) | собственный индекс; `search_lang` ru/jp/ko/zh-hans/fr/es | хранение результатов — только на плане со storage rights; запрет на обучение моделей; US-embargo оговорка; калифорнийское право — [terms](https://api-dashboard.search.brave.com/terms-of-service) | fallback при оплате; кэшировать нельзя |
| Exa | $0.007 (auto), contents $0.001/стр | хороший семантический поиск | «Exa blocks API access from sanctioned or otherwise restricted countries and regions» — Россия в списке, блок на уровне Cloudflare WAF ([Exa security](https://exa.ai/docs/reference/security)) | **нельзя** |
| Tavily (куплен Nebius) | $0.008/credit; search 1–2 credits, extract 1 credit/5 URL — [credits](https://docs.tavily.com/documentation/api-credits) | — | terms §17.1: Россия — Sanctioned Territory, запрещено и косвенное использование через третьи страны/лица ([terms](https://tavily.com/terms)) | **нельзя** |
| Serper.dev / SerpAPI | ≈$0.3–1/1k (Serper, сторонние данные); SerpAPI от $3.75/1k на объёме — [serpapi pricing](https://serpapi.com/pricing) | выдача Google | санкционных оговорок нет, но оплата картой; SerpAPI — в судебном споре с Google (дело продолжается, слушание 2026-10-13, по вторичным источникам) | не рекомендую: скрейпинг Google без прав на хранение |
| XMLRiver (RU-реселлер выдачи Google/Yandex) | 0,012–0,025 ₽/запрос — [price](https://xmlriver.com/price.html) | выдача Google | оплата в рублях, но это неофициальный скрейпинг (ToS-риск и риск прекращения) | только как аварийный вариант, после юриста |
| Bing Search API | — | — | выведен из эксплуатации 2025-08-11 ([Microsoft](https://learn.microsoft.com/en-us/lifecycle/announcements/bing-search-api-retirement)) | нет |
| Google Custom Search JSON API | — | — | закрыт для новых клиентов, существующие — до 2027-01-01 ([overview](https://developers.google.com/custom-search/v1/overview)); Gemini grounding — Россия не в регионах ([regions](https://ai.google.dev/gemini-api/docs/available-regions)) | нет |
| DuckDuckGo | — | — | официального web-search API нет (только Instant Answers; страницу подтвердить не удалось) | нет |

### 4.2 Извлечение страниц

| Вариант | Цена / лицензия | Комментарий |
|---|---|---|
| **Self-host jsoup** (MIT, [license](https://jsoup.org/license)) в learning-сервисе или отдельном fetcher | 0 ₽ | естественно ложится на Java-стек; простая readability-эвристика (main/article, плотность текста) |
| trafilatura (Apache-2.0 с v1.8.0, [repo](https://github.com/adbar/trafilatura)) sidecar | 0 ₽, Python-контейнер | лучшее качество извлечения; ещё один контейнер на VPS |
| Mozilla Readability (Apache-2.0) | 0 ₽ | JS; на сервере нужен Node; вывод санитайзить |
| Jina Reader | токены; 10M бесплатно на ключ; Elastic купил Jina (2025-10) — [jina.ai/reader](https://jina.ai/reader/) | зарубежный сервис, контент запроса уходит за рубеж; ReaderLM-v2 — CC-BY-NC |
| Firecrawl | от $16/мес; self-host — **AGPL-3.0** ([repo](https://github.com/firecrawl/firecrawl)) | AGPL-сервис рядом с коммерческим продуктом — нежелательно |
| Tavily extract | — | Россия исключена |

### 4.3 SSRF, сеть и лицензии

- **SSRF** (fetcher получает URL из выдачи, т. е. из недоверенного источника) — по
  [OWASP SSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html),
  «Case 2»: только `http/https` и порты 80/443; резолвить **все** A/AAAA и отклонять
  private/loopback/link-local/CGNAT/metadata-диапазоны; подключаться к **уже проверенному**
  IP (защита от DNS rebinding); редиректы выключить или проверять каждый hop заново;
  лимиты размера (например, 2 MB), времени (5–8 s), content-type allowlist (`text/html`,
  `text/plain`); отдельный egress-контейнер без доступа к внутренней сети (Docker network
  без маршрута к Postgres/MinIO). В Spring Boot 4.1 появился `InetAddressFilter` для
  исходящих клиентов ([4.1 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)) —
  ещё один довод сделать INFRA-01 до фактчека.
- **robots.txt** — по [RFC 9309](https://www.rfc-editor.org/rfc/rfc9309.html) не механизм
  авторизации, но уважать его — разумная политика (UA `MnemaFactCheck/1.0` + contact URL).
- **Достижимость из РФ.** Российские провайдеры с 2025-06-09 режут контент за Cloudflare
  примерно до 16 KB ([Cloudflare blog](https://blog.cloudflare.com/russian-internet-users-are-unable-to-access-the-open-internet/));
  распространяется ли это на исходящий трафик дата-центров — не подтверждено. Часть
  зарубежных сайтов заблокирована. Обходить блокировки не стоит; если страница не
  загрузилась, фактчек опирается на сниппет и получает статус «недостаточно источников».
- **Хранение.** Сохранять только URL, заголовок, короткую цитату (≤ 300 символов),
  дату и хэш — без полного текста страниц. Brave хранение запрещает без отдельного плана,
  terms Yandex про кэш молчат (уточнить у Yandex до production). При показе списка
  источников сохранять порядок выдачи (§3.2 terms Yandex).
- **152-ФЗ.** Запросы фактчека строятся из учебного текста пользователя; передача
  зарубежному поисковику текста с ПД — трансграничная передача (уведомление РКН по ст. 12).
  Yandex этот вопрос снимает. Для зарубежного fallback — redaction запроса.

### 4.4 Рекомендация

- **Primary: Yandex Search API.** Русскоязычные запросы — тип RU, остальные языки — COM.
  Low-фактчек (1–2 запроса sync) ≈ **0,5–1,0 ₽**; high (до 15 запросов) ≈ **7,3 ₽** sync
  или **≈ 0,46 ₽** в deferred, если проверку можно сделать фоном (например, после
  публикации LearningItem). Причины: российский договор, закрывающие документы для ИП,
  нет трансграничной передачи и санкционных рисков, квоты с запасом.
- **Extraction:** jsoup в отдельном egress-fetcher с SSRF-guard; trafilatura — позже, если
  качество jsoup не хватит.
- **Fallback (условный):** Brave или Perplexity Search — **только** при появлении
  легального способа оплаты (иностранное юрлицо/карта владельца без посредников, которые
  нарушают terms). Ценовой ориентир — $0.005 ≈ 0,42 ₽/запрос; Perplexity fast $0.001 ≈ 0,08 ₽.
- **Главный открытый риск:** качество COM-индекса Yandex для ja/zh/ko/fr/es официально не
  описано. Нужен размеченный eval: 30–50 фактов на язык, метрика — доля утверждений, по
  которым в top-5 есть релевантный первоисточник.

---

## 5. Что не удалось проверить

**Стек**
- Полного open-source рецепта OpenRewrite для Boot 4.1 нет (по issue в `rewrite-spring` и
  вторичным источникам). Маппит ли `UpgradeJackson_2_3_JsonNodeMethodRenames`
  `textValue()` → `stringValue()` (что изменило бы семантику), не проверено.
- Скомпилируются ли без правок лямбды `.access((auth, ctx) -> new AuthorizationDecision(…))`
  против Security 7.1 — проверит только компиляция.
- Читаются ли в 7.1 строки `oauth2_authorization`, `oauth2_registered_client`
  (`client_settings`/`token_settings`) и `spring_session`, записанные 6.5. План исходит из
  очистки.
- Работа AWS SDK 2.55 с Netty 4.2 — не проверялась (рекомендация обходит её исключением
  `netty-nio-client`).
- Новые пакеты `LocalServerPort` и прочих тестовых аннотаций Boot 4 — покажет компилятор.
- Совместимость Flyway 12 с history-таблицей 11.x — предположение, без официального
  подтверждения.
- Java 29 как следующий LTS (сентябрь 2027) — вывод из двухлетнего каденса, не анонс.
- Дата выхода Angular 23 в проверенных источниках не объявлена.

**STT**
- Число потоков в замерах transcribe.cpp на Ryzen 4750U; машина в замерах sherpa-onnx
  (Qwen3, zipformer); включает ли EPYC-замер turbo загрузку модели и beam.
- Официального x86 CPU-бенчмарка faster-whisper turbo/large-v3 нет; RAM turbo на CPU и RSS
  sherpa-onnx Qwen3 int8 не найдены.
- Вся таблица 2.3 — экстраполяция, не замер.
- В какой метрике Qwen3-ASR даёт KO (CER или WER); официальной поязычной FLEURS-таблицы
  для large-v3/turbo нет (есть только large-v2 и заявление OpenAI про −10–20%).
- Минимальная тарификация Voxtral; страница groq.com/pricing не отрисовалась (цены взяты
  из console docs).
- Подпадает ли ИП под Art. 5n ЕС («entity» или «natural person»); применимость правил OFAC к
  AI-API — вопрос к юристу.
- Языки STT у VK Cloud Voice; отсутствие публичной русской STT-модели Silero — вывод.

**TTS**
- API terms MiniMax (страница рендерится JS) и подсчёт символов CJK.
- Цена Alibaba qwen3-tts-flash (≈ $10/1M — только сниппет официальной страницы), курс CNY,
  использование данных Model Studio для обучения, принимают ли российские аккаунты и
  карты (есть только блоги реселлеров).
- Цены и языки T-Bank VoiceKit и MWS Audiogram; голоса, НДС и статус VK Cloud Voice;
  отсутствие TTS у Cloud.ru.
- Цена Yandex без НДС (расчёт при НДС 22%).
- Любой рейтинг качества для RU/CJK/FR/ES; качество английского голоса Yandex.
- VRAM и скорость Qwen3-TTS / CosyVoice3 / Chatterbox (сторонние или авторские данные),
  скорость на CPU.
- Лицензии отдельных русских голосов Piper; цена коммерческой лицензии Silero.
- Почасовые тарифы GPU в российских облаках.

**Web search**
- Качество COM-индекса Yandex для ja/zh/ko/fr/es; задержка и срок хранения deferred-ответов;
  право кэшировать результаты (terms молчат).
- Текст terms Perplexity (страница отдала 403, использован сниппет), её способы оплаты и
  блокировка российских IP; блокируют ли Россию Brave, Serper, SerpAPI, Jina и Firecrawl.
- Официальная таблица цен Serper (есть только сторонние данные).
- Распространяется ли throttling Cloudflare на трафик из российских дата-центров.
- Официальная страница DuckDuckGo API; выставление счетов ИП у XMLRiver.
- Применимость санкций ЕС/США к поисковым API для ИП.

## 6. Ссылки на документы репозитория

- `docs/product/ai-layer-2026-10.md` — тиры, лимиты STT, экономика credits.
- `docs/reviews/ai-layer-research-2026-10/economics.md` — предыдущие цены STT/TTS/поиска.
  Этот отчёт её обновляет: SpeechKit TTS без FR/ES/JA/ZH/KO; EU Art. 5n(1)(g) закрывает
  Mistral; Qwen3-ASR / GigaAM / Parakeet как CPU-альтернативы faster-whisper; Exa и Tavily
  исключают Россию.
- `docs/engineering/epic-77-refinement.md` (INFRA-01), `docs/architecture/ai-generation-platform.md`.
- Устаревшее наследие v1: `scripts/local-audio-gateway/`, `docs/deploy/model-matrix.md`.
