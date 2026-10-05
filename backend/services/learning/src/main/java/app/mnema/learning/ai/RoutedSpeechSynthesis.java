package app.mnema.learning.ai;

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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Speech synthesis over the {@code learning.ai.routes.tts} list ({@code tts-ru} for Russian when it is not empty): the first usable entry is asked, a
 * transport, rate-limit, credential or unusable-answer failure falls through to the next one, a refusal ends the call. A route entry is skipped, never
 * an error, when its provider is switched off ({@code learning.ai.providers.<id>.enabled}), has no key, has no transport (a proxied provider without an
 * active proxy), does not speak the language, or has an open breaker.
 *
 * <p>Every real call is guarded by a breaker per {@code (provider, TTS)}, bounded by the {@code tts} permits and the daily budget of the capability,
 * journaled in {@code ai_provider_call} (the request hash covers the shape, never the text) and metered. The text is bounded by
 * {@link SpeechSettings#maxText()} before any call. The method refuses to run inside a database transaction.
 */
final class RoutedSpeechSynthesis implements SpeechSynthesis {
    private static final Logger LOG = LoggerFactory.getLogger(RoutedSpeechSynthesis.class);
    private static final AiCapability CAPABILITY = AiCapability.TTS;
    private static final Set<String> KNOWN = Set.of(GeminiSpeechSynthesis.PROVIDER, YandexSpeechSynthesis.PROVIDER);

    private record Entry(String provider, String model) { }

    private final List<Entry> general;
    private final List<Entry> russian;
    private final Map<String, SpeechAdapter> adapters;
    private final BreakerRegistry breakers;
    private final AiBudget budget;
    private final CallJournal journal;
    private final AiTelemetry telemetry;
    private final AiProperties properties;
    private final SpeechSettings settings;
    private final Semaphore permits;

    RoutedSpeechSynthesis(AiProperties properties, SpeechSettings settings, Map<String, SpeechAdapter> adapters, BreakerRegistry breakers,
                          AiBudget budget, CallJournal journal, AiTelemetry telemetry) {
        this.properties = properties;
        this.settings = settings;
        this.adapters = Map.copyOf(adapters);
        this.breakers = breakers;
        this.budget = budget;
        this.journal = journal;
        this.telemetry = telemetry;
        this.general = parse(properties.routes().tts(), properties);
        this.russian = parse(properties.routes().ttsRu(), properties);
        this.permits = new Semaphore(properties.permits().tts());
    }

    private static List<Entry> parse(List<String> raw, AiProperties properties) {
        List<Entry> entries = new ArrayList<>();
        for (String value : raw) {
            int colon = value.indexOf(':');
            if (colon < 1 || colon == value.length() - 1) throw new IllegalArgumentException("A route entry is provider:model");
            String provider = value.substring(0, colon).strip();
            String model = value.substring(colon + 1).strip();
            if (!KNOWN.contains(provider)) throw new IllegalArgumentException("Unknown speech provider in a route");
            // Gemini is billed by tokens and needs a price entry; SpeechKit is billed by character (learning.ai.tts.yandex-rub-per-million-chars)
            if (provider.equals(GeminiSpeechSynthesis.PROVIDER)
                    && properties.models().stream().noneMatch(price -> price.provider().equals(provider) && price.id().equals(model))) {
                throw new IllegalArgumentException("A speech route names a model that has no price entry");
            }
            entries.add(new Entry(provider, model));
        }
        return List.copyOf(entries);
    }

    private List<Entry> entries(String lang) {
        return !russian.isEmpty() && (lang.equalsIgnoreCase("ru") || lang.toLowerCase(java.util.Locale.ROOT).startsWith("ru-")) ? russian : general;
    }

    private List<Entry> usable(String lang) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries(lang)) {
            SpeechAdapter adapter = adapters.get(entry.provider());
            if (adapter != null && adapter.configured() && adapter.supports(lang)) out.add(entry);
        }
        return out;
    }

    @Override
    public boolean configured() {
        return !usable("en").isEmpty() || !usable("ru").isEmpty();
    }

    @Override
    public Optional<Identity> identity(String lang, String voice) {
        List<Entry> usable = usable(lang);
        for (Entry entry : usable) {
            if (breakers.of(entry.provider(), CAPABILITY).isOpen()) continue;
            return Optional.of(identityOf(entry, adapters.get(entry.provider()), voice));
        }
        // every breaker is open: the first entry still names the key, the call itself fails fast
        return usable.isEmpty() ? Optional.empty() : Optional.of(identityOf(usable.getFirst(), adapters.get(usable.getFirst().provider()), voice));
    }

    private Identity identityOf(Entry entry, SpeechAdapter adapter, String voice) {
        return new Identity(entry.provider(), entry.model(), settings.version() + adapter.versionTag(), adapter.format(), adapter.voiceName(voice));
    }

    @Override
    public AiResult<Audio> synthesize(Request request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A provider call must not run inside a database transaction");
        }
        if (request.text().length() > settings.maxText()) return AiResult.failed(new AiFailure.Refusal("text_too_long"));
        List<Entry> usable = usable(request.lang());
        if (usable.isEmpty()) return AiResult.failed(new AiFailure.NotConfigured("no_route"));
        if (budget.exhausted(CAPABILITY)) return AiResult.failed(new AiFailure.BudgetExhausted());
        AiFailure last = null;
        for (Entry entry : usable) {
            AiResult<Audio> result = call(entry, adapters.get(entry.provider()), request);
            if (result instanceof AiResult.Ok<Audio>) return result;
            last = ((AiResult.Failed<Audio>) result).failure();
            if (last instanceof AiFailure.Refusal || last instanceof AiFailure.BudgetExhausted) break;
        }
        return AiResult.failed(last == null ? new AiFailure.Transient("no_answer") : last);
    }

    private AiResult<Audio> call(Entry entry, SpeechAdapter adapter, Request request) {
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
                callId = journal.begin(new CallJournal.Intent(request.stepId(), request.attempt(), CAPABILITY, entry.provider(), entry.model(), hash(entry, request)));
            } catch (RuntimeException exception) {
                LOG.warn("speech_internal_failure stage=journal_begin error_type={} provider={}", exception.getClass().getSimpleName(), entry.provider());
                return AiResult.failed(new AiFailure.Transient("journal_unavailable"));
            }
            long started = System.nanoTime();
            AiResult<Audio> result;
            try {
                result = adapter.synthesize(entry.model(), settings.version() + adapter.versionTag(), request, settings.callTimeout());
            } catch (RuntimeException exception) {
                LOG.warn("speech_internal_failure stage=adapter error_type={} provider={}", exception.getClass().getSimpleName(), entry.provider());
                result = AiResult.failed(new AiFailure.Transient("adapter_error"));
            }
            Duration latency = Duration.ofNanos(System.nanoTime() - started);
            // as for text: the breaker opens on transport and credential failures, not on an answer that is merely unusable
            if (result instanceof AiResult.Failed<Audio> failed && (failed.failure() instanceof AiFailure.Transient
                    || failed.failure() instanceof AiFailure.Timeout || failed.failure() instanceof AiFailure.NotConfigured)) {
                breaker.onFailure(ticket);
            } else {
                breaker.onSuccess(ticket);
            }
            settled = true;
            long cost = result instanceof AiResult.Ok<Audio> ok ? adapter.costMicros(ok.value()) : 0;
            Usage usage = result instanceof AiResult.Ok<Audio> ok ? adapter.usage(ok.value()) : Usage.ZERO;
            String outcome = result instanceof AiResult.Failed<Audio> failed ? failed.failure().outcome() : "OK";
            journal.finish(callId, new CallJournal.Outcome(outcome, usage, cost, null, latency.toMillis()));
            budget.record(CAPABILITY, cost);
            if (result instanceof AiResult.Ok<Audio> ok) {
                Audio audio = ok.value();
                result = AiResult.ok(new Audio(audio.bytes(), audio.mimeType(), audio.billedCharacters(), audio.durationMs(), audio.identity(), cost));
            }
            try {
                telemetry.record(CAPABILITY, entry.provider(), entry.model(), request.stepId(), outcome, latency, usage, cost, adapter.egress());
            } catch (RuntimeException exception) {
                LOG.warn("speech_internal_failure stage=telemetry error_type={}", exception.getClass().getSimpleName());
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AiResult.failed(new AiFailure.Transient("interrupted"));
        } finally {
            if (permit) permits.release();
            if (!settled) breaker.release(ticket);
        }
    }

    /** SHA-256 of the request shape: correlates repeats without keeping the text. */
    private static String hash(Entry entry, Request request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((entry.provider() + "|" + entry.model() + "|" + request.lang() + "|" + request.voice() + "|" + request.take() + "|" + request.text())
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
