package app.mnema.learning.generation;

import app.mnema.learning.ai.AiCapability;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.ImageSearch;
import app.mnema.learning.generation.Rows.Candidate;
import app.mnema.learning.generation.Rows.Slot;
import app.mnema.learning.generation.Rows.Turn;
import app.mnema.learning.media.GeneratedMediaStager;
import app.mnema.learning.media.MediaCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code IMAGE_SEARCH} step (#296): licensed stock image search into the media pipeline. Two inputs share the kind:
 * <ul>
 *   <li><b>the initial step of a slot</b> (input {@code slotKey, assetId, revisionId, spec}, created by {@code TEXT_DRAFT}): search the slot's query,
 *       download the best result through the safe fetcher, stage it as an untrusted upload under the slot's own pre-allocated asset, wait for the
 *       verification and make the slot READY with that candidate; no new revision;</li>
 *   <li><b>the step of an IMAGE_SEARCH turn</b> (input {@code turnId}): the same search with the turn's instruction (else the slot's query), results already
 *       among the slot's candidates dropped, up to four new ones staged under fresh asset ids; the first that becomes READY is applied in a new revision.</li>
 * </ul>
 * Nothing here holds a database transaction while it searches, downloads, stages or waits: each write is one short transaction of
 * {@link ImageSearchLifecycle} (fenced by the lease token), and the wait is a poll of the asset state every 500 ms bounded by the step deadline (PT3M).
 * The query is written by the draft model (no extra model call here), so the language of the query decides the {@code lang} a source gets.
 */
@Component
class ImageSearchExecutor implements StepExecutor {
    static final String KIND = "IMAGE_SEARCH";
    /** The budget of one run: a search, up to four downloads and the verification of each. */
    static final Duration DEADLINE = Duration.ofMinutes(3);
    static final int NEW_PER_TURN = 4;
    private static final Logger LOG = LoggerFactory.getLogger(ImageSearchExecutor.class);
    private static final Duration POLL = Duration.ofMillis(500);

    private final ImageSearch search;
    private final GeneratedMediaStager stager;
    private final GenerationRepository repository;
    private final CandidateRepository candidates;
    private final ImageSearchLifecycle lifecycle;
    private final EditLifecycle edits;
    private final MeterRegistry meters;
    private final Duration poll;

    @org.springframework.beans.factory.annotation.Autowired
    ImageSearchExecutor(ImageSearch search, GeneratedMediaStager stager, GenerationRepository repository, CandidateRepository candidates,
                        ImageSearchLifecycle lifecycle, EditLifecycle edits, MeterRegistry meters) {
        this(search, stager, repository, candidates, lifecycle, edits, meters, POLL);
    }

    ImageSearchExecutor(ImageSearch search, GeneratedMediaStager stager, GenerationRepository repository, CandidateRepository candidates,
                        ImageSearchLifecycle lifecycle, EditLifecycle edits, MeterRegistry meters, Duration poll) {
        this.search = search;
        this.stager = stager;
        this.repository = repository;
        this.candidates = candidates;
        this.lifecycle = lifecycle;
        this.edits = edits;
        this.meters = meters;
        this.poll = poll;
    }

    @Override public String kind() { return KIND; }

    @Override public AiCapability capability() { return AiCapability.IMAGE_SEARCH; }

    /** The initial step of a slot, as opposed to the step of a turn. */
    static boolean isSlotStep(String kind, JsonNode input) {
        return KIND.equals(kind) && !input.has("turnId");
    }

    @Override
    public void execute(StepClaim claim, StepControl control) {
        if (claim.input().has("turnId")) turn(claim, control);
        else slot(claim, control);
    }

    // --------------------------------------------------------------- the slot

    private void slot(StepClaim claim, StepControl control) {
        Optional<Slot> began = lifecycle.beginSlot(claim);
        if (began.isEmpty() || control.lost()) return;
        Slot slot = began.get();
        // an earlier attempt that lost its lease recorded its choice before the transfer: this one resumes that very candidate
        Optional<Candidate> resumed = candidates.ofSlot(slot.artifactId(), slot.slotKey()).stream()
                .filter(each -> each.assetId().equals(slot.assetId())).findFirst();
        Candidate staged;
        if (resumed.isPresent() && stager.assetState(claim.ownerId(), slot.assetId()) != GeneratedMediaStager.State.PENDING) {
            // its bytes are already staged: only the verification is left to wait for
            staged = resumed.get();
        } else {
            String query = slot.spec().path("query").stringValue("");
            // a resumed choice whose transfer never happened is searched for again by its key (the answer is cached for a day), never replaced
            Set<String> exclude = resumed.isPresent() ? Set.of() : Set.copyOf(candidates.keysOfSlot(slot.artifactId(), slot.slotKey()));
            Found found = find(claim, control, query, exclude, resumed.isPresent() ? 30 : 6);
            if (found.stop()) return;
            if (found.failure() != null) {
                endSlot(claim, found.failure(), false);
                return;
            }
            List<ImageSearch.Candidate> list = resumed.isEmpty() ? found.list() : found.list().stream()
                    .filter(each -> each.source().name().equals(resumed.get().source()) && each.sourceId().equals(resumed.get().sourceId())).toList();
            staged = stageFirst(claim, control, slot, list, resumed.orElse(null));
            if (staged == null) return;
        }
        if (!lifecycle.slotStaged(claim, staged)) {
            outcome(false);
            return;
        }
        Instant deadline = claim.deadlineAt();
        GeneratedMediaStager.State state = await(List.of(staged.assetId()), claim, control, deadline).get(staged.assetId());
        if (control.lost()) return;
        if (control.cancelled()) {
            endSlot(claim, null, true);
            return;
        }
        switch (state) {
            case READY -> outcome(lifecycle.slotReady(claim, staged.candidateId()));
            case REJECTED, FAILED -> endSlot(claim, "VERIFICATION_REJECTED", false);
            default -> endSlot(claim, "DEADLINE_EXCEEDED", false);
        }
    }

    /**
     * Stages the first downloadable result under the slot's own asset id; null when the step has ended (nothing downloadable, or void). The
     * choice is recorded (the asset is reserved, then the candidate row with its source key and attribution is stored) before the bytes are
     * transferred, so that a retry resumes the same candidate and an asset is never paired with another image's attribution. {@code resumed}
     * is the already recorded candidate of a retry, whose row is not stored again.
     */
    private Candidate stageFirst(StepClaim claim, StepControl control, Slot slot, List<ImageSearch.Candidate> found, Candidate resumed) {
        boolean downloaded = false;
        for (ImageSearch.Candidate each : found.stream().limit(3).toList()) {
            if (control.cancelled() || control.lost() || Instant.now().isAfter(claim.deadlineAt())) break;
            ImageSearch.Image image = download(control, each);
            if (image == null) continue;
            downloaded = true;
            Candidate row = resumed != null ? resumed : row(each, slot.artifactId(), slot.slotKey(), slot.assetId());
            try {
                stager.reserve(claim.ownerId(), slot.assetId(), MediaCatalog.Kind.IMAGE, image.mimeType(), image.bytes());
                if (resumed == null && !lifecycle.slotChosen(claim, row)) {
                    outcome(false);
                    return null;
                }
                stager.stage(claim.ownerId(), slot.assetId(), MediaCatalog.Kind.IMAGE, image.mimeType(), image.bytes());
            } catch (RuntimeException unavailable) {
                LOG.warn("generation_media_stage_failed step_id={} error_type={}", claim.stepId(), unavailable.getClass().getSimpleName());
                endSlot(claim, "PROVIDER_UNAVAILABLE", false);
                return null;
            }
            return row;
        }
        if (control.lost()) return null;
        endSlot(claim, control.cancelled() ? null : downloaded ? "VERIFICATION_REJECTED" : "PROVIDER_UNAVAILABLE", control.cancelled());
        return null;
    }

    private void endSlot(StepClaim claim, String errorCode, boolean cancelled) {
        boolean stored = lifecycle.slotFailed(claim, errorCode, cancelled);
        outcome(stored && !cancelled ? "failed" : cancelled ? "cancelled" : "void");
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome=failed error_code={} stored={}", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), errorCode == null ? "-" : errorCode, stored);
    }

    // ---------------------------------------------------------------- the turn

    private void turn(StepClaim claim, StepControl control) {
        Optional<Turn> began = edits.begin(claim);
        if (began.isEmpty() || control.lost()) return;
        Turn turn = began.get();
        Slot slot = repository.slotsOf(claim.artifactId()).stream()
                .filter(each -> each.slotKey().equals(claim.input().path("slotKey").stringValue(""))).findFirst().orElse(null);
        if (slot == null) {
            failTurn(claim, Map.of(), "NO_RESULT");
            return;
        }
        if (claim.attempt() > 1 && applyStored(claim, slot, turn)) return;
        int room = CandidateRepository.MAX_PER_SLOT - candidates.count(slot.artifactId(), slot.slotKey());
        int wanted = Math.min(NEW_PER_TURN, room);
        if (wanted <= 0) {
            failTurn(claim, Map.of(), "NO_RESULT");
            return;
        }
        String query = turn.instruction() != null ? turn.instruction() : slot.spec().path("query").stringValue("");
        Found found = find(claim, control, query, Set.copyOf(candidates.keysOfSlot(slot.artifactId(), slot.slotKey())), Math.min(50, wanted * 3));
        if (found.stop()) return;
        if (found.failure() != null) {
            failTurn(claim, Map.of(), found.failure());
            return;
        }
        // download and stage until there are as many as asked for; a download that fails is replaced by the next result
        List<Candidate> rows = new ArrayList<>();
        for (ImageSearch.Candidate each : found.list()) {
            if (rows.size() >= wanted || control.cancelled() || control.lost() || Instant.now().isAfter(claim.deadlineAt())) break;
            ImageSearch.Image image = download(control, each);
            if (image == null) continue;
            UUID asset = UUID.randomUUID();
            try {
                stager.stage(claim.ownerId(), asset, MediaCatalog.Kind.IMAGE, image.mimeType(), image.bytes());
            } catch (RuntimeException unavailable) {
                LOG.warn("generation_media_stage_failed step_id={} error_type={}", claim.stepId(), unavailable.getClass().getSimpleName());
                break;
            }
            rows.add(row(each, slot.artifactId(), slot.slotKey(), asset));
        }
        if (control.lost()) return;
        if (control.cancelled()) {
            stored(lifecycle.cancelTurn(claim), "cancelled");
            return;
        }
        if (rows.isEmpty()) {
            // something was found but nothing could be downloaded: no new usable image, as for an empty search
            failTurn(claim, Map.of(), found.list().isEmpty() ? "NO_RESULT" : "PROVIDER_UNAVAILABLE");
            return;
        }
        if (!lifecycle.addTurnCandidates(claim, rows)) {
            outcome(false);
            return;
        }
        Map<UUID, GeneratedMediaStager.State> done = await(rows.stream().map(Candidate::assetId).toList(), claim, control, claim.deadlineAt());
        if (control.lost()) return;
        if (control.cancelled()) {
            stored(lifecycle.cancelTurn(claim), "cancelled");
            return;
        }
        Map<UUID, String> states = new LinkedHashMap<>();
        Candidate chosen = null;
        for (Candidate row : rows) {
            GeneratedMediaStager.State state = done.get(row.assetId());
            states.put(row.candidateId(), state == GeneratedMediaStager.State.READY ? "READY"
                    : stillWaiting(state) ? "VERIFYING" : "FAILED");
            if (chosen == null && state == GeneratedMediaStager.State.READY) chosen = row;
        }
        if (chosen == null) {
            boolean late = done.values().stream().anyMatch(ImageSearchExecutor::stillWaiting);
            failTurn(claim, states, late ? "DEADLINE_EXCEEDED" : "NO_RESULT");
            return;
        }
        stored(lifecycle.succeedImages(claim, chosen, states), "succeeded");
    }

    /**
     * A retry of the turn (the lease of an earlier attempt was lost) first considers what that attempt already stored: the READY candidates of
     * this slot created since the turn began, which no revision uses yet (a turn that was applied ends, so none of them is chosen). The first
     * is applied without a new search, download or staging. True when this step has ended with it (or its claim is void).
     */
    private boolean applyStored(StepClaim claim, Slot slot, Turn turn) {
        if (turn.createdAt() == null) return false;
        Candidate stored = candidates.ofSlot(slot.artifactId(), slot.slotKey()).stream()
                .filter(each -> each.state().equals("READY") && !each.assetId().equals(slot.assetId())
                        && each.createdAt() != null && !each.createdAt().isBefore(turn.createdAt()))
                .findFirst().orElse(null);
        if (stored == null) return false;
        stored(lifecycle.succeedImages(claim, stored, Map.of(stored.candidateId(), "READY")), "succeeded");
        return true;
    }

    private void failTurn(StepClaim claim, Map<UUID, String> states, String errorCode) {
        boolean stored = lifecycle.failTurn(claim, states, errorCode);
        outcome(stored ? "failed" : "void");
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome=failed error_code={} stored={}", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), errorCode, stored);
    }

    private void stored(boolean stored, String outcome) {
        outcome(stored ? outcome : "void");
    }

    // ----------------------------------------------------------------- shared

    /** What a search came to: the licensed results, or the code the step ends with, or {@code stop} when the claim is gone. */
    private record Found(List<ImageSearch.Candidate> list, String failure, boolean stop) { }

    private Found find(StepClaim claim, StepControl control, String query, Set<String> exclude, int max) {
        ImageSearch.Request request = new ImageSearch.Request(query, language(query), Math.max(1, max), exclude, claim.stepId(), claim.attempt());
        AiResult<List<ImageSearch.Candidate>> result;
        control.callStarted();
        try {
            result = search.search(request);
        } finally {
            control.callEnded();
        }
        if (control.lost()) return new Found(List.of(), null, true);
        if (control.cancelled()) {
            if (claim.input().has("turnId")) stored(lifecycle.cancelTurn(claim), "cancelled");
            else endSlot(claim, null, true);
            return new Found(List.of(), null, true);
        }
        if (result instanceof AiResult.Failed<List<ImageSearch.Candidate>>) return new Found(List.of(), "PROVIDER_UNAVAILABLE", false);
        List<ImageSearch.Candidate> list = ((AiResult.Ok<List<ImageSearch.Candidate>>) result).value();
        return list.isEmpty() ? new Found(List.of(), "NO_RESULT", false) : new Found(list, null, false);
    }

    private ImageSearch.Image download(StepControl control, ImageSearch.Candidate candidate) {
        AiResult<ImageSearch.Image> result;
        control.callStarted();
        try {
            result = search.fetch(candidate);
        } finally {
            control.callEnded();
        }
        return result instanceof AiResult.Ok<ImageSearch.Image> ok ? ok.value() : null;
    }

    /** Polls the assets until each is READY, REJECTED or FAILED, or the deadline passes (then it is still VERIFYING). No transaction is open. */
    private Map<UUID, GeneratedMediaStager.State> await(List<UUID> assets, StepClaim claim, StepControl control, Instant deadline) {
        Map<UUID, GeneratedMediaStager.State> states = new LinkedHashMap<>();
        Set<UUID> pending = new HashSet<>(assets);
        assets.forEach(asset -> states.put(asset, GeneratedMediaStager.State.VERIFYING));
        while (!pending.isEmpty()) {
            for (UUID asset : List.copyOf(pending)) {
                GeneratedMediaStager.State state = stager.assetState(claim.ownerId(), asset);
                states.put(asset, state);
                if (!stillWaiting(state)) pending.remove(asset);
            }
            if (pending.isEmpty() || control.lost() || control.cancelled() || Instant.now().plus(poll).isAfter(deadline)) break;
            try {
                Thread.sleep(poll);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return states;
    }

    private static boolean stillWaiting(GeneratedMediaStager.State state) {
        return state == GeneratedMediaStager.State.VERIFYING || state == GeneratedMediaStager.State.PENDING;
    }

    private static Candidate row(ImageSearch.Candidate found, UUID artifactId, String slotKey, UUID asset) {
        return new Candidate(UUID.randomUUID(), artifactId, slotKey, asset, found.source().name(), found.sourceId(), found.title(), found.author(),
                found.license(), found.licenseUrl(), found.sourcePageUrl(), found.shareAlike(), found.width(), found.height(), "VERIFYING", null);
    }

    private void outcome(boolean stored) {
        outcome(stored ? "succeeded" : "void");
    }

    private void outcome(String outcome) {
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", outcome).increment();
    }

    /**
     * The {@code lang} of a query from its script, for sources that take one: kana is Japanese, Hangul Korean, Han Chinese, Cyrillic Russian,
     * everything else English. No extra model call decides it.
     */
    static String language(String query) {
        boolean kana = false;
        boolean hangul = false;
        boolean han = false;
        boolean cyrillic = false;
        for (int index = 0; index < query.length(); ) {
            int point = query.codePointAt(index);
            index += Character.charCount(point);
            switch (Character.UnicodeScript.of(point)) {
                case HIRAGANA, KATAKANA -> kana = true;
                case HANGUL -> hangul = true;
                case HAN -> han = true;
                case CYRILLIC -> cyrillic = true;
                default -> { }
            }
        }
        if (kana) return "ja";
        if (hangul) return "ko";
        if (han) return "zh";
        return cyrillic ? "ru" : "en";
    }
}
