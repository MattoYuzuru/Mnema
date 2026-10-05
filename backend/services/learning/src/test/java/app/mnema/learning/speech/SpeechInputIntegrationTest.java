package app.mnema.learning.speech;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.usage.Bucket;
import app.mnema.learning.usage.UsageLedger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Speech inputs of #298 (AI-15) on the real Spring context, PostgreSQL and the deterministic Stub (no network, no key), the worker on fast timers:
 * consent, admission (202, replay, conflict, the size and type guards), the transcript as the learner polls it, the audio that is gone when the
 * transcription ends, fair use, the rate limit, ownership, expiry and the failures the Stub simulates. Each test uses its own account.
 */
@SpringBootTest(properties = {
        "learning.runtime.roles=all",
        "learning.ai.provider=stub",
        "learning.features.speech-to-text.enabled=true",
        "learning.usage.entitlements.default-plan=PLUS",
        "learning.speech.sweep-interval=PT0.2S",
        // small pool: every distinct test context keeps its own pool and the shared test PostgreSQL has a connection cap
        "spring.datasource.hikari.maximum-pool-size=8"})
// MiddayUsageClock: a day boundary must not fall inside a run
@Import(app.mnema.learning.usage.MiddayUsageClock.class)
class SpeechInputIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] AUDIO = "not really opus, the Stub never listens".getBytes(StandardCharsets.UTF_8);

    @Autowired private SpeechInputController controller;
    @Autowired private SpeechInputWorker worker;
    @Autowired private SpeechInputRepository repository;
    @Autowired private SpeechHints hints;
    @Autowired private JdbcClient jdbc;
    @Autowired private UsageLedger ledger;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService studySessions;
    @Autowired private MediaCatalog media;
    @Autowired private SpeechInputService service;
    @Autowired private SpeechConsents consents;
    @Autowired private app.mnema.learning.ai.UserKeys userKeys;
    @Autowired private app.mnema.learning.ai.AiProperties aiProperties;

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    // ------------------------------------------------------------------ helpers

    private MockMvc mvc(UUID owner) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    private MockHttpServletResponse send(UUID owner, MockHttpServletRequestBuilder request) throws Exception {
        return mvc(owner).perform(request).andReturn().getResponse();
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception { return JSON.readTree(response.getContentAsString()); }

    private void consent(UUID owner) throws Exception {
        MockHttpServletResponse response = send(owner, put("/speech-consent").contentType("application/json")
                .content("{\"version\":\"speech-2026-10\",\"processing\":\"RU\"}"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    }

    private static MockHttpServletRequestBuilder input(String purpose, UUID key, int durationMs, byte[] audio) {
        return raw(purpose, key.toString(), Integer.toString(durationMs), audio);
    }

    /** The two headers as sent, valid or not (a header set twice would be two values). */
    private static MockHttpServletRequestBuilder raw(String purpose, String key, String duration, byte[] audio) {
        return post("/speech-inputs").queryParam("purpose", purpose).header("Idempotency-Key", key)
                .header("X-Audio-Duration-Ms", duration).contentType("audio/ogg;codecs=opus").content(audio);
    }

    private MockHttpServletResponse submit(UUID owner, String purpose, int durationMs) throws Exception {
        return send(owner, input(purpose, UUID.randomUUID(), durationMs, AUDIO));
    }

    private UUID accepted(UUID owner, MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = send(owner, request);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
        return UUID.fromString(json(response).path("speechInputId").stringValue(null));
    }

    private JsonNode poll(UUID owner, UUID id) throws Exception {
        MockHttpServletResponse response = send(owner, get("/speech-inputs/" + id));
        assertThat(response.getStatus()).isEqualTo(200);
        return json(response);
    }

    /** Polls until the input is terminal, as the client does. */
    private JsonNode finished(UUID owner, UUID id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode body = poll(owner, id);
            String state = body.path("state").stringValue(null);
            if (state.equals("DONE") || state.equals("FAILED")) return body;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for speech input " + id);
    }

    private JsonNode scripted(UUID owner, String script) throws Exception {
        return finished(owner, accepted(owner, input("COMPOSER", UUID.randomUUID(), 3_000, AUDIO)
                .header("X-Stub-Transcript", URLEncoder.encode(script, StandardCharsets.UTF_8).replace("+", "%20"))));
    }

    private int audioRows(UUID id) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.speech_input_audio WHERE speech_input_id=:id").param("id", id).query(Integer.class).single();
    }

    private long sttSeconds(UUID owner) {
        return jdbc.sql("SELECT COALESCE(sum(units),0)::bigint FROM app_learning.usage_ledger_entry WHERE owner_id=:owner AND bucket='STT'")
                .param("owner", owner).query(Long.class).single();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    // ------------------------------------------------------------------ consent

    @Test
    void theConsentIsAskedRecordedStaleOrWithdrawnAndAnInputNeedsIt() throws Exception {
        UUID owner = UUID.randomUUID();

        MockHttpServletResponse before = send(owner, get("/speech-consent"));
        assertThat(before.getStatus()).isEqualTo(200);
        assertThat(before.getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode view = json(before);
        assertThat(view.path("required").path("version").stringValue(null)).isEqualTo("speech-2026-10");
        // the Stub processes in the process: nothing leaves Russia
        assertThat(view.path("required").path("processing").stringValue(null)).isEqualTo("RU");
        assertThat(view.path("accepted").isNull()).isTrue();

        // an input without it: 409 with what to ask for
        MockHttpServletResponse refused = submit(owner, "COMPOSER", 3_000);
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(json(refused).path("code").stringValue(null)).isEqualTo("SPEECH_CONSENT_REQUIRED");
        assertThat(json(refused).path("version").stringValue(null)).isEqualTo("speech-2026-10");
        assertThat(json(refused).path("processing").stringValue(null)).isEqualTo("RU");

        // a stale version and another region are outdated, a malformed body is invalid
        for (String body : new String[] {"{\"version\":\"speech-2025-01\",\"processing\":\"RU\"}", "{\"version\":\"speech-2026-10\",\"processing\":\"ABROAD\"}"}) {
            MockHttpServletResponse stale = send(owner, put("/speech-consent").contentType("application/json").content(body));
            assertThat(stale.getStatus()).isEqualTo(409);
            assertThat(json(stale).path("code").stringValue(null)).isEqualTo("SPEECH_CONSENT_OUTDATED");
            assertThat(json(stale).path("version").stringValue(null)).isEqualTo("speech-2026-10");
        }
        for (String body : new String[] {"{}", "[]", "{\"version\":1,\"processing\":\"RU\"}", "{\"version\":\"speech-2026-10\",\"processing\":\"MARS\"}",
                "{\"version\":\"speech-2026-10\",\"processing\":\"RU\",\"extra\":true}", "{nope"}) {
            assertThat(send(owner, put("/speech-consent").contentType("application/json").content(body)).getStatus()).as(body).isEqualTo(400);
        }

        // accepted: 200 with the GET body; again is the same, with its first time
        MockHttpServletResponse accepted = send(owner, put("/speech-consent").contentType("application/json")
                .content("{\"version\":\"speech-2026-10\",\"processing\":\"RU\"}"));
        assertThat(accepted.getStatus()).isEqualTo(200);
        JsonNode first = json(accepted).path("accepted");
        assertThat(first.path("version").stringValue(null)).isEqualTo("speech-2026-10");
        assertThat(first.path("processing").stringValue(null)).isEqualTo("RU");
        assertThat(first.path("acceptedAt").stringValue(null)).isNotBlank();
        consent(owner);
        assertThat(json(send(owner, get("/speech-consent"))).path("accepted").path("acceptedAt").stringValue(null)).isEqualTo(first.path("acceptedAt").stringValue(null));
        assertThat(submit(owner, "COMPOSER", 3_000).getStatus()).isEqualTo(202);

        // an older consent version asks again
        jdbc.sql("UPDATE app_learning.speech_consent SET version='speech-2025-01' WHERE owner_id=:owner").param("owner", owner).update();
        assertThat(submit(owner, "COMPOSER", 3_000).getStatus()).isEqualTo(409);

        // withdrawn (also when there is nothing to withdraw): 204
        consent(owner);
        assertThat(send(owner, delete("/speech-consent")).getStatus()).isEqualTo(204);
        assertThat(send(owner, delete("/speech-consent")).getStatus()).isEqualTo(204);
        assertThat(json(send(owner, get("/speech-consent"))).path("accepted").isNull()).isTrue();
        assertThat(submit(owner, "COMPOSER", 3_000).getStatus()).isEqualTo(409);

        // one account's consent is not another's
        assertThat(submit(UUID.randomUUID(), "COMPOSER", 3_000).getStatus()).isEqualTo(409);
    }

    // ------------------------------------------------------------------ the flow

    @Test
    void aClipIsQueuedTranscribedMeteredAndItsAudioIsGone() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);
        UUID key = UUID.randomUUID();

        MockHttpServletResponse response = send(owner, input("COMPOSER", key, 3_040, AUDIO).queryParam("lang", "ru"));

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(response.getHeader("Idempotency-Replayed")).isNull();
        JsonNode accepted = json(response);
        assertThat(accepted.path("state").stringValue(null)).isEqualTo("QUEUED");
        assertThat(accepted.path("pollAfterMs").intValue()).isEqualTo(400);
        assertThat(java.time.Instant.parse(accepted.path("expiresAt").stringValue(null))).isAfter(java.time.Instant.now().plus(Duration.ofMinutes(14)))
                .isBefore(java.time.Instant.now().plus(Duration.ofMinutes(16)));
        UUID id = UUID.fromString(accepted.path("speechInputId").stringValue(null));

        JsonNode done = finished(owner, id);

        assertThat(done.path("state").stringValue(null)).isEqualTo("DONE");
        assertThat(done.path("text").stringValue(null)).isEqualTo("Тестовая расшифровка голосового ввода.");
        // the declared 3.04 s, rounded up
        assertThat(done.path("seconds").intValue()).isEqualTo(4);
        assertThat(done.path("lang").stringValue(null)).isEqualTo("ru");
        assertThat(done.path("garbled").booleanValue()).isFalse();
        assertThat(done.path("errorCode").isNull()).isTrue();
        assertThat(done.path("expiresAt").stringValue(null)).isEqualTo(accepted.path("expiresAt").stringValue(null));
        // the recording lived only until the transcription ended; the seconds were counted once, the unit of the usage ledger being seconds
        assertThat(audioRows(id)).isZero();
        assertThat(sttSeconds(owner)).isEqualTo(4);
        assertThat(poll(owner, id).path("text").stringValue(null)).isEqualTo("Тестовая расшифровка голосового ввода.");

        // a repeat with the same key and body is the stored 202, nothing new is queued or counted
        MockHttpServletResponse replay = send(owner, input("COMPOSER", key, 3_040, AUDIO).queryParam("lang", "ru"));
        assertThat(replay.getStatus()).isEqualTo(202);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay).path("speechInputId").stringValue(null)).isEqualTo(id.toString());
        assertThat(json(replay).path("state").stringValue(null)).isEqualTo("QUEUED");
        assertThat(json(replay).path("expiresAt").stringValue(null)).isEqualTo(accepted.path("expiresAt").stringValue(null));
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.speech_input WHERE owner_id=:owner").param("owner", owner).query(Integer.class).single()).isEqualTo(1);

        // the same key with another body (the clip, the length, the purpose, the language) conflicts
        for (MockHttpServletRequestBuilder other : new MockHttpServletRequestBuilder[] {
                input("COMPOSER", key, 3_040, "another clip".getBytes(StandardCharsets.UTF_8)).queryParam("lang", "ru"),
                input("COMPOSER", key, 3_041, AUDIO).queryParam("lang", "ru"),
                input("EDIT", key, 3_040, AUDIO).queryParam("lang", "ru"),
                input("COMPOSER", key, 3_040, AUDIO).queryParam("lang", "en")}) {
            MockHttpServletResponse conflict = send(owner, other);
            assertThat(conflict.getStatus()).isEqualTo(409);
            assertThat(json(conflict).path("code").stringValue(null)).isEqualTo("IDEMPOTENCY_CONFLICT");
        }
        // another account's same key is its own input
        UUID other = UUID.randomUUID();
        consent(other);
        assertThat(send(other, input("COMPOSER", key, 3_040, AUDIO)).getHeader("Idempotency-Replayed")).isNull();

        // a deleted input is gone, and deleting it again (or an unknown one) is still 204
        assertThat(send(owner, delete("/speech-inputs/" + id)).getStatus()).isEqualTo(204);
        assertThat(send(owner, get("/speech-inputs/" + id)).getStatus()).isEqualTo(404);
        assertThat(send(owner, delete("/speech-inputs/" + id)).getStatus()).isEqualTo(204);
        assertThat(send(owner, delete("/speech-inputs/" + UUID.randomUUID())).getStatus()).isEqualTo(204);
    }

    @Test
    void aSpokenStudyAnswerHasItsOwnStubTextAndEveryPurposeIsAccepted() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);

        assertThat(finished(owner, accepted(owner, input("STUDY_ANSWER", UUID.randomUUID(), 5_000, AUDIO))).path("text").stringValue(null))
                .isEqualTo("Тестовый устный ответ.");
        for (String purpose : new String[] {"COMPOSER", "EDIT", "CAPTURE"}) {
            assertThat(finished(owner, accepted(owner, input(purpose, UUID.randomUUID(), 1_000, AUDIO))).path("text").stringValue(null))
                    .isEqualTo("Тестовая расшифровка голосового ввода.");
        }
    }

    @Test
    void aHarnessScriptsTheStubsAnswerAndTheFailuresItSimulatesAreTheLearnersOrNot() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);

        assertThat(scripted(owner, "Привет, мир").path("text").stringValue(null)).isEqualTo("Привет, мир");
        JsonNode garbled = scripted(owner, "Что-то неясное [[stub:stt-garbled]]");
        assertThat(garbled.path("state").stringValue(null)).isEqualTo("DONE");
        assertThat(garbled.path("garbled").booleanValue()).isTrue();
        assertThat(garbled.path("text").stringValue(null)).isEqualTo("Что-то неясное");
        long counted = sttSeconds(owner);
        assertThat(counted).isEqualTo(6);

        // no speech: FAILED NO_SPEECH, no text
        JsonNode silence = scripted(owner, "");
        assertThat(silence.path("state").stringValue(null)).isEqualTo("FAILED");
        assertThat(silence.path("errorCode").stringValue(null)).isEqualTo("NO_SPEECH");
        assertThat(silence.path("text").isNull()).isTrue();
        // an outage, and a clip the provider cannot decode
        assertThat(scripted(owner, "[[stub:stt-down]]").path("errorCode").stringValue(null)).isEqualTo("UNAVAILABLE");
        assertThat(scripted(owner, "[[stub:stt-unsupported]]").path("errorCode").stringValue(null)).isEqualTo("UNSUPPORTED_AUDIO");
        // a failed input is not counted, and none of them keeps its audio
        assertThat(sttSeconds(owner)).isEqualTo(counted);
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.speech_input_audio a JOIN app_learning.speech_input i USING (speech_input_id) WHERE i.owner_id=:owner")
                .param("owner", owner).query(Integer.class).single()).isZero();
    }

    @Test
    void noLogLineCarriesTheTranscriptTheAudioOrTheHeaderOfAHarness() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);
        String secret = "СЕКРЕТНАЯ-РАСШИФРОВКА-777";
        byte[] audio = "СЕКРЕТНОЕ-АУДИО-555".getBytes(StandardCharsets.UTF_8);
        var appender = new ListAppender<ILoggingEvent>();
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender.start();
        root.addAppender(appender);
        try {
            UUID id = accepted(owner, input("COMPOSER", UUID.randomUUID(), 3_000, audio).header("X-Stub-Transcript", URLEncoder.encode(secret, StandardCharsets.UTF_8)));
            assertThat(finished(owner, id).path("text").stringValue(null)).isEqualTo(secret);
            assertThat(scripted(owner, "[[stub:stt-down]]").path("errorCode").stringValue(null)).isEqualTo("UNAVAILABLE");
            assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 3_000, audio).contentType("text/plain")).getStatus()).isEqualTo(400);
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }
        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(secret, "СЕКРЕТНОЕ-АУДИО", URLEncoder.encode(secret, StandardCharsets.UTF_8));
        }
        // the lines of the flow carry ids, the purpose, sizes and the outcome
        assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().startsWith("speech_input_queued speech_input_id="));
        assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().startsWith("speech_input_done speech_input_id=") && event.getFormattedMessage().contains("outcome=DONE"));
    }

    @Test
    void theCallJournalTakesTheSttCapabilityAndStillRefusesAnUnknownOne() {
        String hash = "0".repeat(64);
        assertThat(jdbc.sql("INSERT INTO app_learning.ai_provider_call(call_id,attempt,capability,provider,model,request_hash) "
                + "VALUES (:id,1,'STT','google','gemini-3.5-flash-lite',:hash)").param("id", UUID.randomUUID()).param("hash", hash).update()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.ai_provider_call(call_id,attempt,capability,provider,model,request_hash) "
                + "VALUES (:id,1,'ASR','google','m',:hash)").param("id", UUID.randomUUID()).param("hash", hash).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // the daily budget of the capability sums it like the others
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.ai_provider_call WHERE capability='STT'").query(Integer.class).single()).isPositive();
    }

    // ------------------------------------------------------------------ guards

    @Test
    void theRequestIsValidatedBeforeAnythingIsStored() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);
        UUID key = UUID.randomUUID();

        // the size cap: a declared length and a body that lies about it are both 413; the cap itself is fine
        MockHttpServletResponse declared = send(owner, input("COMPOSER", key, 3_000, new byte[2 * 1024 * 1024 + 1]));
        assertThat(declared.getStatus()).isEqualTo(413);
        assertThat(json(declared).path("code").stringValue(null)).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 60_000, new byte[2 * 1024 * 1024]).contentType("audio/mpeg")).getStatus()).isEqualTo(202);

        // the type allowlist, with and without the codecs parameter; a body that is not audio
        for (String type : new String[] {"audio/ogg", "audio/ogg;codecs=opus", "audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/mpeg", "AUDIO/MP4;CODECS=\"mp4a.40.2\""}) {
            assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).contentType(type)).getStatus()).as(type).isEqualTo(202);
        }
        for (String type : new String[] {"audio/wav", "video/mp4", "application/json", "text/plain", "audio/ogg;rate=1", "audio/ogg;codecs=a;b", "application/octet-stream"}) {
            assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).contentType(type)).getStatus()).as(type).isEqualTo(400);
        }
        assertThat(send(owner, post("/speech-inputs").queryParam("purpose", "COMPOSER").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Audio-Duration-Ms", "1000").content(AUDIO)).getStatus()).as("no content type").isEqualTo(400);
        // an empty body
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, new byte[0])).getStatus()).isEqualTo(400);
        // the duration header: 1..60000, digits only
        for (String duration : new String[] {"0", "-5", "60001", "abc", "1.5", "", "0600"}) {
            assertThat(send(owner, raw("COMPOSER", UUID.randomUUID().toString(), duration, AUDIO)).getStatus()).as(duration).isEqualTo(400);
        }
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 60_000, AUDIO)).getStatus()).isEqualTo(202);
        assertThat(send(owner, post("/speech-inputs").queryParam("purpose", "COMPOSER").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("audio/ogg").content(AUDIO)).getStatus()).as("no duration").isEqualTo(400);
        // the key: present, UUIDv4 or v7 in canonical form
        assertThat(send(owner, post("/speech-inputs").queryParam("purpose", "COMPOSER").header("X-Audio-Duration-Ms", "1000")
                .contentType("audio/ogg").content(AUDIO)).getStatus()).as("no key").isEqualTo(400);
        for (String bad : new String[] {"not-a-uuid", "00000000-0000-0000-0000-000000000000", UUID.randomUUID().toString().toUpperCase(), "00000000-0000-1000-8000-000000000001"}) {
            assertThat(send(owner, raw("COMPOSER", bad, "1000", AUDIO)).getStatus()).as(bad).isEqualTo(400);
        }
        // the query: a known purpose, a language tag, an own deck, nothing else
        assertThat(send(owner, post("/speech-inputs").header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Audio-Duration-Ms", "1000")
                .contentType("audio/ogg").content(AUDIO)).getStatus()).as("no purpose").isEqualTo(400);
        assertThat(send(owner, input("SHOUTING", UUID.randomUUID(), 1_000, AUDIO)).getStatus()).isEqualTo(400);
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).queryParam("lang", "r u")).getStatus()).isEqualTo(400);
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).queryParam("lang", "ru-RU")).getStatus()).isEqualTo(202);
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).queryParam("model", "x")).getStatus()).isEqualTo(400);
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).queryParam("deckId", "nope")).getStatus()).isEqualTo(400);
        assertThat(send(owner, get("/speech-inputs/not-a-uuid")).getStatus()).isEqualTo(400);
        assertThat(send(owner, get("/speech-inputs/" + UUID.randomUUID()).queryParam("x", "1")).getStatus()).isEqualTo(400);

        // the Stub header is honoured only percent-encoded and within its bound
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).header("X-Stub-Transcript", "%zz")).getStatus()).isEqualTo(400);
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).header("X-Stub-Transcript", "a".repeat(2_001))).getStatus()).isEqualTo(400);
        assertThat(SpeechInputController.script("a+b%20c%D0%B9")).isEqualTo("a+b cй");
        assertThat(SpeechInputController.script(null)).isNull();
    }

    @Test
    void aClipIsAcceptedOnlyForTheOwnersDeckAndItsTermsAreHintsWithoutPersonalData() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        consent(owner);
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, studySessions, media, jdbc);
        UUID deck = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Планировщик", "Запросы"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
        fixtures.addMaterial(owner, deck, "Планировщик выбирает план", "Статистика");

        assertThat(finished(owner, accepted(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).queryParam("deckId", deck.toString()))).path("state")
                .stringValue(null)).isEqualTo("DONE");
        // an unknown deck and another account's deck are the same 404
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).queryParam("deckId", UUID.randomUUID().toString())).getStatus()).isEqualTo(404);
        consent(stranger);
        assertThat(send(stranger, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO).queryParam("deckId", deck.toString())).getStatus()).isEqualTo(404);

        // the titles of the deck's current materials; nobody else's deck gives any
        assertThat(hints.of(owner, deck)).isNotEmpty().allSatisfy(term -> assertThat(term).isNotBlank().hasSizeLessThanOrEqualTo(64));
        assertThat(hints.of(stranger, deck)).isEmpty();
        assertThat(hints.of(owner, null)).isEmpty();
    }

    @Test
    void theHintsAreCleanedDeduplicatedBoundedAndFreeOfPersonalLookingTerms() {
        java.util.List<String> titles = java.util.List.of("  Токио  ", "токио", "Рамен\u0000\n суши", "mail me at a@b.example", "https://example.org/x", "call +79001234567",
                "12345678", "x".repeat(65), "", "Нормальный термин", "Ещё один");
        assertThat(SpeechHints.clean(titles, 60)).containsExactly("Токио", "Рамен суши", "Нормальный термин", "Ещё один");
        assertThat(SpeechHints.clean(titles, 2)).containsExactly("Токио", "Рамен суши");
        assertThat(SpeechHints.clean(java.util.Arrays.asList("a", null, "b"), 60)).containsExactly("a", "b");
        assertThat(SpeechHints.clean(java.util.List.of(), 60)).isEmpty();
    }

    // ------------------------------------------------------------------ limits

    @Test
    void theTwentyFirstInputOfTenMinutesIsRateLimitedWithRetryAfter() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);
        for (int index = 0; index < 20; index++) {
            MockHttpServletResponse response = submit(owner, "COMPOSER", 1_000);
            assertThat(response.getStatus()).as("input " + index + ": " + response.getContentAsString()).isEqualTo(202);
        }

        MockHttpServletResponse limited = submit(owner, "COMPOSER", 1_000);

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(json(limited).path("code").stringValue(null)).isEqualTo("RATE_LIMITED");
        long wait = Long.parseLong(limited.getHeader("Retry-After"));
        assertThat(wait).isBetween(1L, 600L);
        assertThat(json(limited).path("retryAfter").longValue()).isEqualTo(wait);
        // the refused call took no place and stored nothing; another account is not limited
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.speech_input WHERE owner_id=:owner").param("owner", owner).query(Integer.class).single()).isEqualTo(20);
        UUID other = UUID.randomUUID();
        consent(other);
        assertThat(submit(other, "COMPOSER", 1_000).getStatus()).isEqualTo(202);
        // the key of a stored input still replays at the limit, as it takes no new place
        UUID key = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        consent(third);
        assertThat(send(third, input("COMPOSER", key, 1_000, AUDIO)).getStatus()).isEqualTo(202);
        jdbc.sql("DELETE FROM app_learning.speech_input_use WHERE owner_id=:owner").param("owner", owner).update();
        // the window slides: once the uses are older than ten minutes there is room again
        for (int index = 0; index < 20; index++) {
            jdbc.sql("INSERT INTO app_learning.speech_input_use(owner_id,used_at) VALUES (:owner, CURRENT_TIMESTAMP - interval '11 minutes')").param("owner", owner).update();
        }
        assertThat(submit(owner, "COMPOSER", 1_000).getStatus()).isEqualTo(202);
    }

    @Test
    void theDeclaredSecondsMustFitTheFairUseBucketAndAnInputInFlightIsReserved() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);
        // PLUS may transcribe 30 minutes a day (1800 s): 1790 are already counted
        new TransactionTemplate(transactions).executeWithoutResult(status -> ledger.consume(owner, Bucket.STT, 1_790, "stt:test:" + owner, null));

        MockHttpServletResponse over = submit(owner, "COMPOSER", 20_000);

        assertThat(over.getStatus()).isEqualTo(409);
        JsonNode problem = json(over);
        assertThat(problem.path("code").stringValue(null)).isEqualTo("USAGE_LIMIT_REACHED");
        assertThat(problem.path("bucket").stringValue(null)).isEqualTo("STT");
        assertThat(problem.path("window").stringValue(null)).isEqualTo("DAY");
        assertThat(problem.path("unit").stringValue(null)).isEqualTo("MINUTES");
        assertThat(problem.path("renewsAt").stringValue(null)).isNotBlank();
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.speech_input WHERE owner_id=:owner").param("owner", owner).query(Integer.class).single()).isZero();
        // ten seconds still fit; the audio that was refused was never stored
        assertThat(submit(owner, "COMPOSER", 10_000).getStatus()).isEqualTo(202);

        // admitted and not counted yet counts against the window: a queued 60 s clip leaves no room for another when only 70 s are left
        UUID second = UUID.randomUUID();
        consent(second);
        new TransactionTemplate(transactions).executeWithoutResult(status -> ledger.consume(second, Bucket.STT, 1_730, "stt:test:" + second, null));
        UUID queued = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> repository.insert(queued, second, UUID.randomUUID(), new byte[32],
                app.mnema.learning.ai.Transcription.Purpose.COMPOSER, null, null, "audio/ogg", 60_000, AUDIO, null, app.mnema.learning.ai.Transcription.Region.RU, Duration.ofSeconds(30), Duration.ofMinutes(15)));
        // (the worker would take it at once: hold it back by claiming it as another instance did)
        jdbc.sql("UPDATE app_learning.speech_input SET state='TRANSCRIBING', claim_token=:token WHERE speech_input_id=:id").param("token", UUID.randomUUID()).param("id", queued).update();
        assertThat(submit(second, "COMPOSER", 20_000).getStatus()).isEqualTo(409);
        // the held input ends (here: is deleted) and the room is back
        assertThat(send(second, delete("/speech-inputs/" + queued)).getStatus()).isEqualTo(204);
        assertThat(submit(second, "COMPOSER", 20_000).getStatus()).isEqualTo(202);
    }

    // ------------------------------------------------------------------ ownership, expiry, deadline

    @Test
    void anotherAccountsInputIsNotFoundAndAnExpiredOneIsPurged() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        consent(owner);
        UUID id = accepted(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO));
        finished(owner, id);

        assertThat(send(stranger, get("/speech-inputs/" + id)).getStatus()).isEqualTo(404);
        assertThat(send(stranger, delete("/speech-inputs/" + id)).getStatus()).as("idempotent, and not the stranger's to delete").isEqualTo(204);
        assertThat(poll(owner, id).path("state").stringValue(null)).isEqualTo("DONE");

        // an expired row is not served and the sweeper purges it
        jdbc.sql("UPDATE app_learning.speech_input SET expires_at=CURRENT_TIMESTAMP - interval '1 second' WHERE speech_input_id=:id").param("id", id).update();
        assertThat(send(owner, get("/speech-inputs/" + id)).getStatus()).isEqualTo(404);
        await("the expired input to be purged", () -> jdbc.sql("SELECT count(*)::integer FROM app_learning.speech_input WHERE speech_input_id=:id").param("id", id)
                .query(Integer.class).single() == 0);
    }

    @Test
    void aTranscriptionThatNeverEndsIsFailedUnavailableAndItsAudioIsDeleted() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);
        // overdue before the worker can take it (the claim skips an overdue row); and a running one whose worker died
        UUID overdue = UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            repository.insert(overdue, owner, UUID.randomUUID(), new byte[32], app.mnema.learning.ai.Transcription.Purpose.COMPOSER, null, null, "audio/ogg", 1_000,
                    AUDIO, null, app.mnema.learning.ai.Transcription.Region.RU, Duration.ofSeconds(-5), Duration.ofMinutes(15));
            repository.insert(dead, owner, UUID.randomUUID(), new byte[32], app.mnema.learning.ai.Transcription.Purpose.COMPOSER, null, null, "audio/ogg", 1_000,
                    AUDIO, null, app.mnema.learning.ai.Transcription.Region.RU, Duration.ofSeconds(30), Duration.ofMinutes(15));
        });
        jdbc.sql("UPDATE app_learning.speech_input SET state='TRANSCRIBING', claim_token=:token, deadline_at=CURRENT_TIMESTAMP - interval '1 second' "
                + "WHERE speech_input_id=:id").param("token", UUID.randomUUID()).param("id", dead).update();

        for (UUID id : new UUID[] {overdue, dead}) {
            JsonNode failed = finished(owner, id);
            assertThat(failed.path("errorCode").stringValue(null)).isEqualTo("UNAVAILABLE");
            await("the audio of " + id + " to be deleted", () -> audioRows(id) == 0);
        }
        assertThat(sttSeconds(owner)).isZero();
    }

    @Test
    void aSettlementThatArrivesAfterTheInputWasDeletedOrFailedChangesNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> repository.insert(id, owner, UUID.randomUUID(), new byte[32],
                app.mnema.learning.ai.Transcription.Purpose.COMPOSER, null, null, "audio/ogg", 1_000, AUDIO, null, app.mnema.learning.ai.Transcription.Region.RU, Duration.ofSeconds(30), Duration.ofMinutes(15)));
        jdbc.sql("UPDATE app_learning.speech_input SET state='TRANSCRIBING', claim_token=:token WHERE speech_input_id=:id").param("token", UUID.randomUUID()).param("id", id).update();
        var stale = new SpeechInputRepository.Claim(id, owner, UUID.randomUUID(), app.mnema.learning.ai.Transcription.Purpose.COMPOSER, null, null, "audio/ogg", 1_000,
                java.time.Instant.now().plusSeconds(30), app.mnema.learning.ai.Transcription.Region.RU);
        assertThat(repository.done(stale, "late", 1, "ru", false)).as("another claim token").isFalse();
        assertThat(repository.failed(stale, "UNAVAILABLE")).isFalse();
        assertThat(audioRows(id)).isEqualTo(1);
        assertThat(repository.delete(owner, id)).isTrue();
        assertThat(repository.delete(owner, id)).isFalse();
        assertThat(audioRows(id)).as("the audio goes with the row").isZero();
    }

    // ------------------------------------------------------------------ review fixes: consent at the claim, fair use, sweeps, limits

    /** What a worker with a scripted provider saw and answered. */
    private static final class Provider implements app.mnema.learning.ai.Transcription {
        final java.util.List<Request> requests = new java.util.ArrayList<>();
        app.mnema.learning.ai.AiResult<Transcript> answer;

        @Override
        public app.mnema.learning.ai.AiResult<Transcript> transcribe(Request request) {
            requests.add(request);
            return answer;
        }

        @Override public java.util.Optional<Region> region(String lang) { return java.util.Optional.of(Region.RU); }
    }

    private SpeechInputWorker workerOf(Provider provider) {
        return new SpeechInputWorker(repository, hints, consents, provider, userKeys, ledger, aiProperties,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), transactions);
    }

    /** An input that a worker (the test's own) has claimed: inserted and claimed in one transaction, so that the worker of the context never sees it queued. */
    private SpeechInputRepository.Claim running(UUID owner, int declaredMs, app.mnema.learning.ai.Transcription.Region region) {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            repository.insert(id, owner, UUID.randomUUID(), new byte[32], app.mnema.learning.ai.Transcription.Purpose.COMPOSER, null, null, "audio/ogg", declaredMs,
                    AUDIO, null, region, Duration.ofSeconds(30), Duration.ofMinutes(15));
            jdbc.sql("UPDATE app_learning.speech_input SET state='TRANSCRIBING', claim_token=:token WHERE speech_input_id=:id").param("token", token).param("id", id).update();
        });
        return new SpeechInputRepository.Claim(id, owner, token, app.mnema.learning.ai.Transcription.Purpose.COMPOSER, null, null, "audio/ogg", declaredMs,
                java.time.Instant.now().plusSeconds(30), region);
    }

    private static app.mnema.learning.ai.AiResult<app.mnema.learning.ai.Transcription.Transcript> heard(String text, int seconds) {
        return app.mnema.learning.ai.AiResult.ok(new app.mnema.learning.ai.Transcription.Transcript(text, seconds, "ru", false));
    }

    private JsonNode stored(UUID owner, UUID id) throws Exception { return poll(owner, id); }

    private void consentFor(UUID owner, String version, String region) {
        jdbc.sql("INSERT INTO app_learning.speech_consent(owner_id,version,processing,accepted_at) VALUES (:owner,:version,:region,CURRENT_TIMESTAMP) "
                + "ON CONFLICT (owner_id) DO UPDATE SET version=EXCLUDED.version, processing=EXCLUDED.processing")
                .param("owner", owner).param("version", version).param("region", region).update();
    }

    @Test
    void aConsentWithdrawnOrOutdatedAfterAdmissionStopsTheClipBeforeAnyProviderSeesIt() throws Exception {
        var ru = app.mnema.learning.ai.Transcription.Region.RU;
        Provider provider = new Provider();
        provider.answer = heard("hello", 3);
        SpeechInputWorker mine = workerOf(provider);

        // withdrawn between admission and processing
        UUID withdrawn = UUID.randomUUID();
        consentFor(withdrawn, "speech-2026-10", "RU");
        var claim = running(withdrawn, 3_000, ru);
        assertThat(send(withdrawn, delete("/speech-consent")).getStatus()).isEqualTo(204);
        mine.run(claim);
        assertThat(provider.requests).isEmpty();
        JsonNode failed = stored(withdrawn, claim.id());
        assertThat(failed.path("state").stringValue(null)).isEqualTo("FAILED");
        assertThat(failed.path("errorCode").stringValue(null)).isEqualTo("UNAVAILABLE");
        assertThat(audioRows(claim.id())).isZero();

        // the version changed meanwhile
        UUID stale = UUID.randomUUID();
        consentFor(stale, "speech-2025-01", "RU");
        var old = running(stale, 3_000, ru);
        mine.run(old);
        assertThat(provider.requests).isEmpty();
        assertThat(stored(stale, old.id()).path("errorCode").stringValue(null)).isEqualTo("UNAVAILABLE");

        // an input admitted for ABROAD whose consent was narrowed to RU: no longer covered
        UUID narrowed = UUID.randomUUID();
        consentFor(narrowed, "speech-2026-10", "RU");
        var wide = running(narrowed, 3_000, app.mnema.learning.ai.Transcription.Region.ABROAD);
        mine.run(wide);
        assertThat(provider.requests).isEmpty();
        assertThat(stored(narrowed, wide.id()).path("state").stringValue(null)).isEqualTo("FAILED");
    }

    @Test
    void theRegionOfTheConsentIsWhatTheRouterMayUse() throws Exception {
        Provider provider = new Provider();
        provider.answer = heard("hello", 3);
        SpeechInputWorker mine = workerOf(provider);

        UUID russian = UUID.randomUUID();
        consentFor(russian, "speech-2026-10", "RU");
        mine.run(running(russian, 3_000, app.mnema.learning.ai.Transcription.Region.RU));
        UUID abroad = UUID.randomUUID();
        consentFor(abroad, "speech-2026-10", "ABROAD");
        mine.run(running(abroad, 3_000, app.mnema.learning.ai.Transcription.Region.RU));

        assertThat(provider.requests).extracting(app.mnema.learning.ai.Transcription.Request::allowedRegion)
                .containsExactly(app.mnema.learning.ai.Transcription.Region.RU, app.mnema.learning.ai.Transcription.Region.ABROAD);
    }

    @Test
    void aProviderThatMeasuresMoreThanWasDeclaredFailsTheInputAndDeliversNothing() throws Exception {
        Provider provider = new Provider();
        SpeechInputWorker mine = workerOf(provider);
        UUID owner = UUID.randomUUID();
        consentFor(owner, "speech-2026-10", "RU");

        // 3 s declared, 20 s measured: far above max(2 s, 20 %)
        provider.answer = heard("a very long text", 20);
        var understated = running(owner, 3_000, app.mnema.learning.ai.Transcription.Region.RU);
        mine.run(understated);
        JsonNode failed = stored(owner, understated.id());
        assertThat(failed.path("state").stringValue(null)).isEqualTo("FAILED");
        assertThat(failed.path("errorCode").stringValue(null)).isEqualTo("TOO_LONG");
        assertThat(failed.path("text").isNull()).isTrue();
        assertThat(sttSeconds(owner)).isZero();

        // within the tolerance (3 s declared, 5 s measured) the text is delivered and counted at the measured seconds
        provider.answer = heard("fine", 5);
        var close = running(owner, 3_000, app.mnema.learning.ai.Transcription.Region.RU);
        mine.run(close);
        assertThat(stored(owner, close.id()).path("state").stringValue(null)).isEqualTo("DONE");
        assertThat(sttSeconds(owner)).isEqualTo(5);
        assertThat(SpeechInputWorker.understated(60_000, 61)).isFalse();
        assertThat(SpeechInputWorker.understated(10_000, 12)).isFalse();
        assertThat(SpeechInputWorker.understated(10_000, 13)).isTrue();
    }

    @Test
    void aTranscriptThatDoesNotFitTheWindowWhenCountedIsFailedNotDeliveredFree() throws Exception {
        Provider provider = new Provider();
        SpeechInputWorker mine = workerOf(provider);
        UUID owner = UUID.randomUUID();
        consentFor(owner, "speech-2026-10", "RU");
        new TransactionTemplate(transactions).executeWithoutResult(status -> ledger.consume(owner, Bucket.STT, 1_790, "stt:test:" + owner, null));

        provider.answer = heard("free text", 20);
        var claim = running(owner, 20_000, app.mnema.learning.ai.Transcription.Region.RU);
        mine.run(claim);

        JsonNode failed = stored(owner, claim.id());
        assertThat(failed.path("state").stringValue(null)).isEqualTo("FAILED");
        assertThat(failed.path("errorCode").stringValue(null)).isEqualTo("UNAVAILABLE");
        assertThat(failed.path("text").isNull()).isTrue();
        assertThat(sttSeconds(owner)).isEqualTo(1_790);
        assertThat(audioRows(claim.id())).isZero();
    }

    @Test
    void aTranscriptLongerThanTheColumnFailsTheInputInsteadOfLeavingItHanging() throws Exception {
        Provider provider = new Provider();
        SpeechInputWorker mine = workerOf(provider);
        UUID owner = UUID.randomUUID();
        consentFor(owner, "speech-2026-10", "RU");
        provider.answer = heard("я".repeat(SpeechInputWorker.MAX_TEXT + 1), 3);
        var claim = running(owner, 3_000, app.mnema.learning.ai.Transcription.Region.RU);

        mine.run(claim);

        JsonNode failed = stored(owner, claim.id());
        assertThat(failed.path("state").stringValue(null)).isEqualTo("FAILED");
        assertThat(failed.path("errorCode").stringValue(null)).isEqualTo("UNAVAILABLE");
        assertThat(audioRows(claim.id())).isZero();
        // the limit itself is delivered
        provider.answer = heard("я".repeat(SpeechInputWorker.MAX_TEXT), 3);
        var edge = running(owner, 3_000, app.mnema.learning.ai.Transcription.Region.RU);
        mine.run(edge);
        assertThat(stored(owner, edge.id()).path("state").stringValue(null)).isEqualTo("DONE");
    }

    @Test
    void aClipTooBigForItsDeclaredDurationIsRefusedAtAdmission() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);

        // 1 s declared, 500 KB of Opus: the declared seconds are what fair use reserves, so this is a lie
        MockHttpServletResponse refused = send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, new byte[500_000]));
        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.speech_input WHERE owner_id=:owner").param("owner", owner).query(Integer.class).single()).isZero();
        // the same bytes are plausible for a long clip, and a short clip of a believable size is fine
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 60_000, new byte[500_000])).getStatus()).isEqualTo(202);
        assertThat(send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, new byte[8_000])).getStatus()).isEqualTo(202);
        assertThat(SpeechInputService.plausibleSize("audio/mpeg", 1_000, 40_000)).isTrue();
        assertThat(SpeechInputService.plausibleSize("audio/webm", 1_000, 60_000)).isFalse();
    }

    @Test
    void theRateLimitIsLookedAtBeforeTheBodyIsReadWithoutALockAndAReplayStillPasses() throws Exception {
        UUID owner = UUID.randomUUID();
        consent(owner);
        UUID key = UUID.randomUUID();
        assertThat(send(owner, input("COMPOSER", key, 1_000, AUDIO)).getStatus()).isEqualTo(202);
        for (int index = 0; index < 20; index++) {
            jdbc.sql("INSERT INTO app_learning.speech_input_use(owner_id,used_at) VALUES (:owner,CURRENT_TIMESTAMP)").param("owner", owner).update();
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.precheck(owner, UUID.randomUUID()))
                .isInstanceOf(app.mnema.learning.platform.api.RateLimitedException.class);
        // a known key is answered from the stored input, whatever the limit says
        service.precheck(owner, key);
        assertThat(send(owner, input("COMPOSER", key, 1_000, AUDIO)).getHeader("Idempotency-Replayed")).isEqualTo("true");
        MockHttpServletResponse limited = send(owner, input("COMPOSER", UUID.randomUUID(), 1_000, AUDIO));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
    }
}
