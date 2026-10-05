package app.mnema.learning.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Speech to text over the {@code learning.ai.routes.stt} list ({@code stt-ru} for Russian when it is not empty): the first usable entry is asked, a
 * transport, rate-limit, credential or unusable-answer failure falls through to the next one, a refusal ends the call. A route entry is skipped, never
 * an error, when its provider is switched off ({@code learning.ai.providers.<id>.enabled}), has no key or base URL, has no transport (a proxied
 * provider without an active proxy) or has an open breaker.
 *
 * <p>Every real call is guarded by a breaker per {@code (provider, STT)}, bounded by the {@code stt} permits, the budget left of the whole call and the
 * daily budget of the capability, journaled in {@code ai_provider_call} (the request hash covers the shape, never the audio or the hints) and metered:
 * {@code ai_call} like every provider call, plus {@code stt_call provider= outcome= seconds= latency_ms= egress=} and
 * {@code mnema_stt_latency_seconds{provider,outcome}}. The method refuses to run inside a database transaction.
 */
final class RoutedTranscription implements Transcription {
    private static final Logger LOG = LoggerFactory.getLogger(RoutedTranscription.class);
    private static final AiCapability CAPABILITY = AiCapability.STT;
    private static final Set<String> KNOWN = Set.of(GeminiTranscription.PROVIDER, SelfHostTranscription.PROVIDER);

    private record Entry(String provider, String model) { }

    private final List<Entry> general;
    private final List<Entry> russian;
    private final Map<String, TranscriptionAdapter> adapters;
    private final BreakerRegistry breakers;
    private final AiBudget budget;
    private final CallJournal journal;
    private final AiTelemetry telemetry;
    private final MeterRegistry meters;
    private final AiProperties properties;
    private final SttSettings settings;
    private final Semaphore permits;

    RoutedTranscription(AiProperties properties, SttSettings settings, Map<String, TranscriptionAdapter> adapters, BreakerRegistry breakers,
                        AiBudget budget, CallJournal journal, AiTelemetry telemetry, MeterRegistry meters) {
        this.properties = properties;
        this.settings = settings;
        this.adapters = Map.copyOf(adapters);
        this.breakers = breakers;
        this.budget = budget;
        this.journal = journal;
        this.telemetry = telemetry;
        this.meters = meters;
        this.general = parse(properties.routes().stt(), properties);
        this.russian = parse(properties.routes().sttRu(), properties);
        this.permits = new Semaphore(properties.permits().stt());
    }

    private static List<Entry> parse(List<String> raw, AiProperties properties) {
        List<Entry> entries = new ArrayList<>();
        for (String value : raw) {
            int colon = value.indexOf(':');
            if (colon < 1 || colon == value.length() - 1) throw new IllegalArgumentException("A route entry is provider:model");
            String provider = value.substring(0, colon).strip();
            String model = value.substring(colon + 1).strip();
            if (!KNOWN.contains(provider)) throw new IllegalArgumentException("Unknown transcription provider in a route");
            // Gemini is billed by tokens and needs a price entry; a self-hosted container costs nothing per call
            if (provider.equals(GeminiTranscription.PROVIDER)
                    && properties.models().stream().noneMatch(price -> price.provider().equals(provider) && price.id().equals(model))) {
                throw new IllegalArgumentException("A transcription route names a model that has no price entry");
            }
            entries.add(new Entry(provider, model));
        }
        return List.copyOf(entries);
    }

    private List<Entry> entries(String lang) {
        boolean ru = lang != null && (lang.equalsIgnoreCase("ru") || lang.toLowerCase(Locale.ROOT).startsWith("ru-"));
        return !russian.isEmpty() && ru ? russian : general;
    }

    /** The entries of the route of {@code lang} whose provider can be called now (not counting breakers). */
    private List<Entry> usable(String lang) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries(lang)) {
            TranscriptionAdapter adapter = adapters.get(entry.provider());
            if (adapter != null && adapter.configured()) out.add(entry);
        }
        return out;
    }

    private boolean permitted(Entry entry, Region allowed) {
        return allowed == Region.ABROAD || adapters.get(entry.provider()).region() == Region.RU;
    }

    @Override
    public boolean configured() { return !usable(null).isEmpty() || !usable("ru").isEmpty(); }

    @Override
    public boolean healthy() {
        List<Entry> all = new ArrayList<>(usable(null));
        all.addAll(usable("ru"));
        return all.isEmpty() || all.stream().anyMatch(entry -> !breakers.of(entry.provider(), CAPABILITY).isOpen());
    }

    @Override
    public Optional<Region> region(String lang) {
        List<Entry> usable = usable(lang);
        if (usable.isEmpty()) return Optional.empty();
        // the first entry that is healthy serves the call; with every breaker open the first one still names the region
        for (Entry entry : usable) {
            if (!breakers.of(entry.provider(), CAPABILITY).isOpen()) return Optional.of(adapters.get(entry.provider()).region());
        }
        return Optional.of(adapters.get(usable.getFirst().provider()).region());
    }

    @Override
    public AiResult<Transcript> transcribe(Request request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A provider call must not run inside a database transaction");
        }
        // the consent decides where the voice may go: an RU consent never reaches an entry that leaves Russia, whatever fails before it
        List<Entry> usable = usable(request.lang()).stream().filter(entry -> permitted(entry, request.allowedRegion())).toList();
        if (usable.isEmpty()) return AiResult.failed(new AiFailure.NotConfigured("no_route"));
        if (budget.exhausted(CAPABILITY)) return AiResult.failed(new AiFailure.BudgetExhausted());
        long deadline = System.nanoTime() + request.deadline().toNanos();
        AiFailure last = null;
        AiFailure unsupported = null;
        for (Entry entry : usable) {
            Duration left = Duration.ofNanos(deadline - System.nanoTime());
            if (left.isNegative() || left.isZero()) {
                last = new AiFailure.Timeout();
                break;
            }
            AiResult<Transcript> result = call(entry, adapters.get(entry.provider()), request, left.compareTo(settings.callTimeout()) < 0 ? left : settings.callTimeout());
            if (result instanceof AiResult.Ok<Transcript>) return result;
            last = ((AiResult.Failed<Transcript>) result).failure();
            if (last instanceof AiFailure.InvalidOutput invalid && "unsupported_audio".equals(invalid.detail())) unsupported = last;
            if (last instanceof AiFailure.Refusal || last instanceof AiFailure.BudgetExhausted) break;
        }
        // every entry could not decode the clip: that is the learner's clip, not an outage
        if (unsupported != null && last instanceof AiFailure.InvalidOutput) return AiResult.failed(unsupported);
        return AiResult.failed(last == null ? new AiFailure.Transient("no_answer") : last);
    }

    private AiResult<Transcript> call(Entry entry, TranscriptionAdapter adapter, Request request, Duration callBudget) {
        CircuitBreaker breaker = breakers.of(entry.provider(), CAPABILITY);
        long ticket = breaker.tryAcquire();
        if (ticket == CircuitBreaker.REFUSED) return AiResult.failed(new AiFailure.CircuitOpen());
        boolean settled = false;
        boolean permit = false;
        try {
            permit = permits.tryAcquire(properties.permits().queueWait().toMillis(), TimeUnit.MILLISECONDS);
            if (!permit) return AiResult.failed(new AiFailure.RateLimited(Duration.ofSeconds(1)));
            UUID callId;
            try {
                callId = journal.begin(new CallJournal.Intent(null, 1, CAPABILITY, entry.provider(), entry.model(), hash(entry, request)));
            } catch (RuntimeException exception) {
                LOG.warn("stt_internal_failure stage=journal_begin error_type={} provider={}", exception.getClass().getSimpleName(), entry.provider());
                return AiResult.failed(new AiFailure.Transient("journal_unavailable"));
            }
            long started = System.nanoTime();
            AiResult<TranscriptionAdapter.Answer> result;
            try {
                result = adapter.transcribe(entry.model(), request, callBudget);
            } catch (RuntimeException exception) {
                LOG.warn("stt_internal_failure stage=adapter error_type={} provider={}", exception.getClass().getSimpleName(), entry.provider());
                result = AiResult.failed(new AiFailure.Transient("adapter_error"));
            }
            Duration latency = Duration.ofNanos(System.nanoTime() - started);
            // as for text: the breaker opens on transport and credential failures, not on an answer that is merely unusable
            if (result instanceof AiResult.Failed<TranscriptionAdapter.Answer> failed && (failed.failure() instanceof AiFailure.Transient
                    || failed.failure() instanceof AiFailure.Timeout || failed.failure() instanceof AiFailure.NotConfigured)) {
                breaker.onFailure(ticket);
            } else {
                breaker.onSuccess(ticket);
            }
            settled = true;
            TranscriptionAdapter.Answer answer = result instanceof AiResult.Ok<TranscriptionAdapter.Answer> ok ? ok.value() : null;
            long cost = answer == null ? 0 : adapter.costMicros(entry.model(), answer);
            Usage usage = answer == null ? Usage.ZERO : answer.usage();
            String outcome = result instanceof AiResult.Failed<TranscriptionAdapter.Answer> failed ? failed.failure().outcome() : "OK";
            journal.finish(callId, new CallJournal.Outcome(outcome, usage, cost, null, latency.toMillis()));
            budget.record(CAPABILITY, cost);
            try {
                telemetry.record(CAPABILITY, entry.provider(), entry.model(), null, outcome, latency, usage, cost, adapter.egress());
                Timer.builder("mnema_stt_latency_seconds").tags("provider", entry.provider(), "outcome", outcome).register(meters).record(latency);
                LOG.info("stt_call provider={} outcome={} seconds={} latency_ms={} egress={}", entry.provider(), outcome,
                        answer == null ? 0 : answer.transcript().seconds(), latency.toMillis(), adapter.egress().label());
            } catch (RuntimeException exception) {
                LOG.warn("stt_internal_failure stage=telemetry error_type={}", exception.getClass().getSimpleName());
            }
            return answer == null ? AiResult.failed(((AiResult.Failed<TranscriptionAdapter.Answer>) result).failure()) : AiResult.ok(answer.transcript());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AiResult.failed(new AiFailure.Transient("interrupted"));
        } finally {
            if (permit) permits.release();
            if (!settled) breaker.release(ticket);
        }
    }

    /** SHA-256 of the request shape: correlates repeats without keeping the audio, the hints or the language of the learner. */
    private static String hash(Entry entry, Request request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((entry.provider() + "|" + entry.model() + "|" + request.mimeType() + "|" + request.audio().length + "|" + request.hints().size() + "|")
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
