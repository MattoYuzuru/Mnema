package app.mnema.learning.generation;

import app.mnema.learning.ai.AiFailure;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.SpeechSynthesis;
import app.mnema.learning.ai.StreamListener;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test doubles around the real provider layer (which runs on the deterministic Stub): a decorator of {@link TextGeneration}
 * that records every call and what the calling thread holds (a transaction, a borrowed connection) at the call and at
 * every streamed delta, and that simulates failures the Stub cannot, selected by markers in the prompt:
 * {@code [[fake:always-invalid-mbm]]} (every answer, repairs included, is a document the compiler rejects),
 * {@code [[fake:invalid-once]]} (the first answer is rejected by the compiler, the repair is answered by the Stub),
 * {@code [[fake:transient]]} (a transport failure the router already gave up on; the Stub's own transient marker would
 * open the shared circuit breaker),
 * {@code [[fake:block]]} (the call waits until released or interrupted: a long provider call to cancel),
 * {@code [[fake:hold-edit]]} (the same for an edit call only, with its own latches),
 * {@code [[fake:crash-once]]} (the first call of each step throws, as a worker that dies mid-step) and
 * {@code [[fake:audio]]} (a valid document with one {@code ::audio} directive; the Stub speech makes its clip),
 * {@code [[fake:audio-hold]]} (the same, whose clip waits in {@link ScriptedSpeech} until a test releases it: a slot that stays GENERATING),
 * {@code [[fake:audio-down]]} and {@code [[fake:audio-garbage]]} (the Stub speech is down, or answers bytes the pipeline rejects),
 * {@code [[fake:image]]} (a valid document with one {@code ::image mode=search} directive whose query carries the {@code [[stub:image-none]]}
 * and {@code [[stub:image-down]]} markers of the prompt, so the Stub image search can be made to find nothing or to be down). For exercise requests (JSON output):
 * {@code [[fake:not-json]]} (every answer is prose), {@code [[fake:fenced]]} (the Stub's answer inside a code fence) and
 * {@code [[fake:length-once]]} (the first answer is cut off by the output limit, the repair is answered by the Stub),
 * {@code [[fake:no-variants]]} (every variant number of the first answer is 1, so exercises of one mechanic repeat their question) and
 * {@code [[fake:fail-on-repair]]} (a repair call is a transport failure the router gave up on). {@link Scripted#outage} makes every call a
 * transport failure (a provider that is down) until a test clears it, to fail an artifact and retry it.
 */
@TestConfiguration(proxyBeanMethods = false)
class GenerationTestConfiguration {
    static final String INVALID_DOCUMENT = "# Заголовок\n\n::unknown{x=\"1\"} текст\n";
    static final String MULTI_DOCUMENT = "# Заголовок\n\nАбзац один.\n\nАбзац два.\n\n- пункт\n- пункт\n\nКонец.\n";
    /** An audio directive whose text carries a marker of the speech double: {@code [[fake:tts-hold]]} waits for the test, {@code [[stub:tts-down]]} fails. */
    static String audioDocument(String marker) {
        return "# Глагол 行く\n\nПервый абзац.\n\n::audio{slot=\"a1\" lang=\"ja\" title=\"Произношение\"} 行く " + marker + "\n";
    }

    static final String AUDIO_DOCUMENT = "# Глагол 行く\n\nПервый абзац.\n\n::audio{slot=\"a1\" lang=\"ja\" title=\"Произношение\"} 行く\n";

    /** A document with one searched image; the markers of the prompt that steer the Stub image search go into the query. */
    static String imageDocument(String prompt) {
        String markers = (prompt.contains("[[stub:image-none]]") ? " [[stub:image-none]]" : "") + (prompt.contains("[[stub:image-down]]") ? " [[stub:image-down]]" : "");
        return "# Лиса\n\nРыжая лиса живёт в лесу.\n\n::image{slot=\"i1\" mode=\"search\" alt=\"Лиса зимой\"} red fox snow" + markers + "\n";
    }

    /** One recorded provider call; {@code userKey} tells whose it is, as the context and its dispatcher are shared by every test. */
    record Call(OpaqueUserKey userKey, UUID stepId, int attempt, app.mnema.learning.ai.AiRoute route, boolean repair, String prompt,
                boolean transactionAtCall, int connectionsAtCall) { }

    /** What the calling thread held at each streamed delta of the call made with {@code userKey}. */
    record DeltaObservation(OpaqueUserKey userKey, boolean transaction, int connections) { }

    /** Per-thread count of connections borrowed from the pool and not yet closed. */
    static final class Connections {
        private static final ThreadLocal<AtomicInteger> HELD = ThreadLocal.withInitial(AtomicInteger::new);

        private Connections() { }

        static int held() { return HELD.get().get(); }
    }

    static final class Scripted implements TextGeneration {
        private final TextGeneration real;
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<DeltaObservation> deltas = new CopyOnWriteArrayList<>();
        volatile boolean outage;
        volatile CountDownLatch blockedEntered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        /** The latches of {@code [[fake:hold-edit]]}: an edit call that waits while a draft call, held by {@code [[fake:block]]}, runs on. */
        volatile CountDownLatch editEntered = new CountDownLatch(1);
        volatile CountDownLatch editRelease = new CountDownLatch(1);
        private final Set<UUID> crashed = ConcurrentHashMap.newKeySet();

        Scripted(TextGeneration real) { this.real = real; }

        void reset() {
            outage = false;
            calls.clear();
            deltas.clear();
            crashed.clear();
            release = new CountDownLatch(1);
            blockedEntered = new CountDownLatch(1);
            editRelease = new CountDownLatch(1);
            editEntered = new CountDownLatch(1);
        }

        List<Call> callsOf(String marker) {
            return calls.stream().filter(call -> call.prompt().contains(marker)).toList();
        }

        @Override
        public AiResult<TextResponse> generate(TextRequest request) {
            String prompt = String.join("\n", request.segments().stream().map(TextRequest.Segment::text).toList());
            boolean repair = request.segments().stream().anyMatch(segment -> segment.text().startsWith(TextRequest.REPAIR_PREFIX));
            calls.add(new Call(request.userKey(), request.stepId(), request.attempt(), request.route(), repair, prompt,
                    TransactionSynchronizationManager.isActualTransactionActive(), Connections.held()));
            if (prompt.contains("[[fake:crash-once]]" ) && crashed.add(request.stepId())) {
                throw new IllegalStateException("simulated worker crash");
            }
            if (prompt.contains("[[fake:hold-edit]]") && prompt.contains("<task kind=\"edit\">")) {
                editEntered.countDown();
                try {
                    editRelease.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return AiResult.failed(new AiFailure.Transient("interrupted"));
                }
            }
            if (prompt.contains("[[fake:block]]")) {
                blockedEntered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return AiResult.failed(new AiFailure.Transient("interrupted"));
                }
            }
            if (outage) return AiResult.failed(new AiFailure.Transient("fake_outage"));
            if (prompt.contains("[[fake:transient]]")) return AiResult.failed(new AiFailure.Transient("fake_transient"));
            if (prompt.contains("[[fake:always-invalid-mbm]]")) return ok(request, INVALID_DOCUMENT);
            if (prompt.contains("[[fake:invalid-once]]") && !repair) return ok(request, INVALID_DOCUMENT);
            if (prompt.contains("[[fake:audio]]")) return ok(request, AUDIO_DOCUMENT);
            if (prompt.contains("[[fake:audio-hold]]")) return ok(request, audioDocument("[[fake:tts-hold]]"));
            if (prompt.contains("[[fake:audio-down]]")) return ok(request, audioDocument("[[stub:tts-down]]"));
            if (prompt.contains("[[fake:audio-garbage]]")) return ok(request, audioDocument("[[stub:tts-garbage]]"));
            if (prompt.contains("[[fake:image]]")) return ok(request, imageDocument(prompt));
            if (prompt.contains("[[fake:not-json]]")) return ok(request, "это не json");
            if (prompt.contains("[[fake:length-once]]") && !repair) {
                return AiResult.ok(new TextResponse("{\"exercises\":[{\"mechanic\":\"CHO", TextResponse.FinishReason.LENGTH,
                        new Usage(100, 0, 100, 50), 0, "fake", new TextResponse.RouteUsed("stub", "stub")));
            }
            if (repair && prompt.contains("[[fake:fail-on-repair]]")) return AiResult.failed(new AiFailure.Transient("fake_repair_outage"));
            if (!repair && prompt.contains("[[fake:no-variants]]")) {
                AiResult<TextResponse> answer = real.generate(request);
                return answer instanceof AiResult.Ok<TextResponse> success
                        ? ok(request, success.value().text().replaceAll("\\(вариант [0-9]+\\)", "(вариант 1)")) : answer;
            }
            if (prompt.contains("[[fake:fenced]]")) {
                AiResult<TextResponse> answer = real.generate(request);
                return answer instanceof AiResult.Ok<TextResponse> success ? ok(request, "```json\n" + success.value().text() + "\n```") : answer;
            }
            if (prompt.contains("[[fake:multiblock]]")) return ok(request, MULTI_DOCUMENT);
            StreamListener original = request.listener();
            StreamListener probe = new StreamListener() {
                @Override public void onDelta(String text) {
                    deltas.add(new DeltaObservation(request.userKey(), TransactionSynchronizationManager.isActualTransactionActive(), Connections.held()));
                    if (original != null) original.onDelta(text);
                }

                @Override public void onRestart() {
                    if (original != null) original.onRestart();
                }
            };
            return real.generate(withoutStubMedia(request, prompt).withListener(probe));
        }

        /**
         * The Stub appends the media directives that the task line of a material allows (so a local run creates slots); the flows of these tests
         * name an audio or image only through {@code [[fake:audio]]} and {@code [[fake:image]]}, so the task line is neutralised for the Stub
         * unless the prompt carries {@code [[fake:stub-media]]}.
         */
        private static TextRequest withoutStubMedia(TextRequest request, String prompt) {
            if (request.output() != app.mnema.learning.ai.OutputContract.MBM_TEXT || prompt.contains("[[fake:stub-media]]")) return request;
            List<TextRequest.Segment> segments = request.segments().stream().map(segment -> new TextRequest.Segment(segment.role(),
                    segment.text().replace("аудио (::audio)", "нет, не добавляй медиа-директивы")
                            .replace("картинка из поиска (::image mode=search)", "нет, не добавляй медиа-директивы"), segment.cacheable())).toList();
            return new TextRequest(request.route(), segments, request.output(), request.maxOutputTokens(), request.temperature(), request.deadline(),
                    request.userKey(), request.listener(), request.stepId(), request.attempt());
        }

        private AiResult<TextResponse> ok(TextRequest request, String text) {
            if (request.listener() != null) {
                for (int start = 0; start < text.length(); start += 16) {
                    deltas.add(new DeltaObservation(request.userKey(), TransactionSynchronizationManager.isActualTransactionActive(), Connections.held()));
                    request.listener().onDelta(text.substring(start, Math.min(text.length(), start + 16)));
                }
            }
            return AiResult.ok(new TextResponse(text, TextResponse.FinishReason.STOP, new Usage(100, 0, 100, 50), 0, "fake",
                    new TextResponse.RouteUsed("stub", "stub")));
        }
    }

    @Bean
    @Primary
    Scripted scriptedText(@Qualifier("textGeneration") TextGeneration real) {
        return new Scripted(real);
    }

    /**
     * The speech port around the Stub: records every call (and whether the caller held a transaction) and holds a call whose text carries
     * {@code [[fake:tts-hold]]} until {@link #release} is counted down or the call is interrupted (a cancelled step).
     */
    static final class ScriptedSpeech implements SpeechSynthesis {
        private final SpeechSynthesis real;
        final List<Request> calls = new CopyOnWriteArrayList<>();
        final List<Boolean> transactionAtCall = new CopyOnWriteArrayList<>();
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        ScriptedSpeech(SpeechSynthesis real) { this.real = real; }

        void reset() {
            calls.clear();
            transactionAtCall.clear();
            release.countDown();
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Override public java.util.Optional<Identity> identity(String lang, String voice) { return real.identity(lang, voice); }

        @Override public boolean configured() { return real.configured(); }

        @Override
        public AiResult<Audio> synthesize(Request request) {
            calls.add(request);
            transactionAtCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (request.text().contains("[[fake:tts-hold]]")) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return AiResult.failed(new AiFailure.Transient("interrupted"));
                }
            }
            return real.synthesize(request);
        }
    }

    @Bean
    @Primary
    ScriptedSpeech scriptedSpeech(@Qualifier("speechSynthesis") SpeechSynthesis real) {
        return new ScriptedSpeech(real);
    }

    /** An executor for a media kind, so a test can see that a step of a REVIEW session is claimed (the real ones come with AI-09). */
    static final class VideoExecutor implements StepExecutor {
        final List<UUID> claimed = new CopyOnWriteArrayList<>();
        private final StepRepository steps;

        VideoExecutor(StepRepository steps) { this.steps = steps; }

        @Override public String kind() { return "VIDEO_GENERATE"; }

        @Override public app.mnema.learning.ai.AiCapability capability() { return app.mnema.learning.ai.AiCapability.VIDEO; }

        @Override
        public void execute(StepClaim claim, StepControl control) {
            claimed.add(claim.stepId());
            steps.finish(claim.stepId(), "SUCCEEDED", null, null);
        }
    }

    @Bean
    VideoExecutor testVideoExecutor(StepRepository steps) {
        return new VideoExecutor(steps);
    }

    /**
     * The media pipeline's staging without S3 and without a worker: it creates the asset the caller named (origin {@code generated}) and, by
     * {@link #mode}, makes it READY at once, READY after {@link #delayPolls} state reads, REJECTED, or leaves it VERIFYING. The first
     * {@link #rejectNext} staged assets are REJECTED whatever the mode. The real staging is tested against MinIO in the media package.
     */
    static final class FakeStager implements app.mnema.learning.media.GeneratedMediaStager {
        enum Mode { READY, REJECT, STAY_VERIFYING }

        private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
        private final app.mnema.learning.media.MediaCatalog media;
        volatile Mode mode = Mode.READY;
        volatile int delayPolls;
        volatile boolean fail;
        /** Assets staged while this latch is set wait for it in {@link #assetState} (a worker held inside the verification wait). */
        volatile CountDownLatch gate;
        /** The next verification poll throws, as a worker that crashes mid-step (its lease then runs out). */
        final java.util.concurrent.atomic.AtomicBoolean crashNextPoll = new java.util.concurrent.atomic.AtomicBoolean();
        /** The next {@link #stage} dies after the reservation, as a worker that is killed before the bytes were transferred. */
        final java.util.concurrent.atomic.AtomicBoolean crashNextStage = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.Map<UUID, CountDownLatch> gated = new ConcurrentHashMap<>();
        final AtomicInteger rejectNext = new AtomicInteger();
        final List<UUID> staged = new CopyOnWriteArrayList<>();
        final List<UUID> adopted = new CopyOnWriteArrayList<>();
        volatile boolean failAdopt;
        final List<Boolean> transactionAtStage = new CopyOnWriteArrayList<>();
        private final java.util.Map<UUID, AtomicInteger> waiting = new ConcurrentHashMap<>();

        FakeStager(org.springframework.jdbc.core.simple.JdbcClient jdbc, app.mnema.learning.media.MediaCatalog media) {
            this.jdbc = jdbc;
            this.media = media;
        }

        void reset() {
            mode = Mode.READY;
            delayPolls = 0;
            fail = false;
            gate = null;
            gated.values().forEach(CountDownLatch::countDown);
            gated.clear();
            crashNextPoll.set(false);
            crashNextStage.set(false);
            rejectNext.set(0);
            staged.clear();
            adopted.clear();
            failAdopt = false;
            transactionAtStage.clear();
            waiting.clear();
        }

        /** A worker killed mid-step: an {@link Error}, which the dispatcher does not catch, so the step stays RUNNING until its lease runs out. */
        static final class Crash extends Error {
            private static final long serialVersionUID = 1L;

            Crash(String message) { super(message); }
        }

        @Override
        public void reserve(UUID owner, UUID assetId, app.mnema.learning.media.MediaCatalog.Kind kind, String mimeType, byte[] bytes) {
            jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,created_at,updated_at) "
                            + "VALUES (:asset,:owner,:asset,'generated',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT (asset_id) DO NOTHING")
                    .param("asset", assetId).param("owner", owner).update();
        }

        @Override
        public void stage(UUID owner, UUID assetId, app.mnema.learning.media.MediaCatalog.Kind kind, String mimeType, byte[] bytes) {
            transactionAtStage.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (fail) throw new IllegalStateException("storage down");
            if (crashNextStage.compareAndSet(true, false)) throw new Crash("killed before the transfer");
            if (gate != null) gated.put(assetId, gate);
            if (!staged.contains(assetId)) {
                staged.add(assetId);
                jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,created_at,updated_at) "
                                + "VALUES (:asset,:owner,:asset,'generated',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT (asset_id) DO NOTHING")
                        .param("asset", assetId).param("owner", owner).update();
            }
            Mode effective = rejectNext.getAndUpdate(count -> Math.max(0, count - 1)) > 0 ? Mode.REJECT : mode;
            switch (effective) {
                case REJECT -> jdbc.sql("UPDATE app_learning.media_asset SET state='REJECTED',updated_at=CURRENT_TIMESTAMP WHERE asset_id=:asset")
                        .param("asset", assetId).update();
                case STAY_VERIFYING -> verifying(assetId);
                case READY -> {
                    if (delayPolls > 0) {
                        verifying(assetId);
                        waiting.put(assetId, new AtomicInteger(delayPolls));
                    } else {
                        ready(assetId, mimeType);
                    }
                }
            }
        }

        @Override
        public State assetState(UUID owner, UUID assetId) {
            CountDownLatch held = gated.get(assetId);
            if (held != null) {
                try {
                    held.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (crashNextPoll.compareAndSet(true, false)) throw new IllegalStateException("worker crashed");
            AtomicInteger left = waiting.get(assetId);
            if (left != null && left.decrementAndGet() < 0) {
                waiting.remove(assetId);
                ready(assetId, "image/png");
            }
            return jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:asset AND owner_id=:owner").param("asset", assetId)
                    .param("owner", owner).query(String.class).optional().map(state -> switch (state) {
                        case "PENDING_UPLOAD" -> State.PENDING;
                        case "READY" -> State.READY;
                        case "REJECTED" -> State.REJECTED;
                        case "FAILED_RETRYABLE", "DELETED" -> State.FAILED;
                        default -> State.VERIFYING;
                    }).orElse(State.MISSING);
        }

        @Override
        public java.util.Optional<VerifiedMedia> verified(UUID owner, UUID assetId) {
            UUID source = jdbc.sql("SELECT source_blob_id FROM app_learning.media_asset WHERE asset_id=:asset AND owner_id=:owner AND state='READY'")
                    .param("asset", assetId).param("owner", owner).query(UUID.class).optional().orElse(null);
            if (source == null) return java.util.Optional.empty();
            // the fake pipeline makes no variants: the playback variant is the source blob itself
            return java.util.Optional.of(new VerifiedMedia(source, List.of(new VerifiedMedia.Variant("playback", "audio_aac_m4a_v1", source, null, null, 1_000L))));
        }

        @Override
        public boolean adopt(UUID owner, UUID assetId, VerifiedMedia media) {
            adopted.add(assetId);
            if (failAdopt) return false;
            jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,state,source_blob_id,owner_hold_until,created_at,updated_at) "
                            + "VALUES (:asset,:owner,:asset,'generated','READY',:blob,CURRENT_TIMESTAMP + interval '1 hour',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
                            + "ON CONFLICT (asset_id) DO NOTHING").param("asset", assetId).param("owner", owner).param("blob", media.sourceBlob()).update();
            return true;
        }

        private void verifying(UUID asset) {
            jdbc.sql("UPDATE app_learning.media_asset SET state='VERIFYING',updated_at=CURRENT_TIMESTAMP WHERE asset_id=:asset AND state='PENDING_UPLOAD'")
                    .param("asset", asset).update();
        }

        private void ready(UUID asset, String mime) {
            UUID blob = UUID.randomUUID();
            byte[] hash = new byte[32];
            java.nio.ByteBuffer.wrap(hash).putLong(blob.getMostSignificantBits()).putLong(blob.getLeastSignificantBits());
            jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) VALUES (:blob,:hash,32,:mime,:key,CURRENT_TIMESTAMP)")
                    .param("blob", blob).param("hash", hash).param("mime", mime).param("key", "k/" + blob).update();
            jdbc.sql("UPDATE app_learning.media_asset SET state='PROCESSING',updated_at=CURRENT_TIMESTAMP WHERE asset_id=:asset AND state<>'READY'")
                    .param("asset", asset).update();
            media.ready(asset, 0, blob);
        }
    }

    @Bean
    @Primary
    FakeStager fakeStager(org.springframework.jdbc.core.simple.JdbcClient jdbc, app.mnema.learning.media.MediaCatalog media) {
        return new FakeStager(jdbc, media);
    }

    /** Counts the connections each thread borrows from the pool, so a test can assert that none is held during a call. */
    @Bean
    static BeanPostProcessor connectionTracking() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource source) || bean instanceof Tracking) return bean;
                return new Tracking(source);
            }
        };
    }

    private static final class Tracking extends DelegatingDataSource {
        Tracking(DataSource target) { super(target); }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return track(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return track(super.getConnection(username, password));
        }

        private static Connection track(Connection connection) {
            AtomicInteger held = Connections.HELD.get();
            held.incrementAndGet();
            boolean[] closed = {false};
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close") && !closed[0]) {
                            closed[0] = true;
                            held.decrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }

    static List<Call> copy(List<Call> calls) {
        return Collections.unmodifiableList(new ArrayList<>(calls));
    }
}
