package app.mnema.learning.generation;

import app.mnema.learning.ai.SpeechSettings;
import app.mnema.learning.ai.SpeechSynthesis;
import app.mnema.learning.media.GeneratedMediaStager;
import app.mnema.learning.usage.AdmissionPricing;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance of #297 (AI-09) on the Stub speech, the speech cache, PostgreSQL and the real catalog (the media pipeline's staging is the test double of
 * {@link GenerationTestConfiguration.FakeStager}; the real one is tested on MinIO in the media package): the first clip of a slot, the cache hit that costs
 * nothing, the redo of a clip (a new take, another voice), failures, cancellation, revert, approval, and one synthesis for two steps on one key.
 */
class GenerationSpeechIntegrationTest extends GenerationEditsSupport {
    private static final String AUDIO = "[[fake:audio]] с аудио";

    @Autowired private AdmissionPricing pricing;
    @Autowired private SpeechCache cache;
    @Autowired private GenerationRepository generationRepository;
    @Autowired private SpeechSettings settings;
    @Autowired private SpeechSynthesis port;
    @Autowired private MeterRegistry meters;

    // ------------------------------------------------------------------ helpers

    private String slotState(UUID artifact) {
        return jdbc.sql("SELECT state FROM app_learning.generation_media_slot WHERE artifact_id=:id").param("id", artifact).query(String.class).single();
    }

    private void awaitSlot(UUID artifact, String state) throws InterruptedException {
        await("slot of " + artifact + " to be " + state, Duration.ofSeconds(20), () -> slotState(artifact).equals(state));
    }

    private JsonNode slot(UUID owner, UUID deck, Proposal proposal) throws Exception {
        return detail(owner, deck, proposal).path("mediaSlots").get(0);
    }

    private UUID nodeAsset(UUID owner, UUID deck, Proposal proposal) throws Exception {
        for (JsonNode block : blocks(detail(owner, deck, proposal))) {
            if (block.path("type").stringValue("").equals("audio")) return UUID.fromString(block.path("attrs").path("assetId").stringValue(null));
        }
        throw new AssertionError("no audio node");
    }

    private UUID audioNode(UUID owner, UUID deck, Proposal proposal) throws Exception {
        return id(blocks(detail(owner, deck, proposal)).stream().filter(block -> block.path("type").stringValue("").equals("audio")).findFirst().orElseThrow());
    }

    private UUID redo(UUID owner, UUID deck, Proposal proposal, String voice) throws Exception {
        Proposal now = fresh(owner, deck, proposal);
        ObjectNode body = editBody(UUID.randomUUID(), now.revision(), "AUDIO_REGENERATE", null, null, audioNode(owner, deck, proposal));
        if (voice != null) body.put("voice", voice);
        return accepted(owner, deck, now, body);
    }

    private Proposal readyProposal(UUID owner, UUID deck, String prompt) throws Exception {
        Proposal proposal = proposal(owner, deck, audioSpec(prompt));
        awaitSlot(proposal.artifact(), "READY");
        return proposal;
    }

    private int cacheRows(String state) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.speech_cache WHERE state=:state").param("state", state).query(Integer.class).single();
    }

    private List<String> slotEvents(UUID owner, UUID deck, Proposal proposal) throws Exception {
        List<String> log = new ArrayList<>();
        json(events(owner, deck, proposal.session(), "?after=0&limit=100")).path("events").forEach(event -> {
            if (event.path("type").stringValue("").equals("MEDIA_SLOT_STATE")) {
                log.add(event.path("payload").path("state").stringValue(null) + ":" + event.path("payload").path("errorCode").stringValue("-"));
            }
        });
        return log;
    }

    /** Claims of the speech cache that found another step's synthesis in flight ({@code SpeechClips#stage}). */
    private double busyPolls() {
        return meters.counter("mnema_tts_cache_total", "outcome", "busy").count();
    }

    private int heldClips(UUID artifact) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_media_hold WHERE artifact_id=:id").param("id", artifact).query(Integer.class).single();
    }

    // -------------------------------------------------------- the first clip

    @Test
    void theFirstClipOfASlotIsSynthesisedStagedHeldAndDebitedOnceAndCachedAfterItsVerification() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);

        Proposal proposal = readyProposal(owner, deck, AUDIO);

        JsonNode slot = slot(owner, deck, proposal);
        UUID asset = UUID.fromString(slot.path("assetId").stringValue(null));
        assertThat(slot.path("slotKey").stringValue(null)).isEqualTo("a1");
        assertThat(slot.path("kind").stringValue(null)).isEqualTo("AUDIO");
        assertThat(slot.path("voice").stringValue(null)).isEqualTo("female");
        assertThat(slot.path("lang").stringValue(null)).isEqualTo("ja");
        assertThat(slot.path("mode").isNull()).isTrue();
        assertThat(slot.path("state").stringValue(null)).isEqualTo("READY");
        // the pre-allocated asset is the staged one, and the node of the revision uses it; no new revision
        assertThat(mediaStager.staged).containsExactly(asset);
        assertThat(asset).isEqualTo(nodeAsset(owner, deck, proposal));
        assertThat(revisions(proposal.artifact())).isEqualTo(1);
        assertThat(slotEvents(owner, deck, proposal)).containsSubsequence("PENDING:-", "GENERATING:-", "VERIFYING:-", "READY:-");
        // one provider call outside any transaction, in the language and voice of the directive, for the text of the directive
        assertThat(speech.calls).hasSize(1);
        SpeechSynthesis.Request request = speech.calls.getFirst();
        assertThat(request.lang()).isEqualTo("ja");
        assertThat(request.voice()).isEqualTo("female");
        assertThat(request.take()).isZero();
        assertThat(request.text()).isEqualTo("行く");
        assertThat(speech.transactionAtCall).containsOnly(false);
        assertThat(mediaStager.transactionAtStage).containsOnly(false);
        // the material and the clip are debited from the batch hold, which ended with the last step
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM") + pricing.credits("TTS_CLIP_30S"));
        assertThat(reservationState(proposal.session())).isEqualTo("SETTLED");
        // the Workshop holds the clip (the node and the clip row) and the verified clip is in the cache for the next one
        assertThat(heldClips(proposal.artifact())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT voice||':'||take FROM app_learning.generation_media_clip WHERE asset_id=:id").param("id", asset).query(String.class).single())
                .isEqualTo("female:0");
        assertThat(cacheRows("READY")).isEqualTo(1);
        assertThat(cacheRows("PENDING")).isZero();
        assertThat(jdbc.sql("SELECT provider||':'||model||':'||voice||':'||lang||':'||take FROM app_learning.speech_cache").query(String.class).single())
                .isEqualTo("stub:stub-tts:female:ja:0");
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_step WHERE artifact_id=:id AND kind='TTS'").param("id", proposal.artifact()).query(String.class).single())
                .isEqualTo("SUCCEEDED");
        assertThat(json(getSession(owner, deck, proposal.session())).path("approvableCount").intValue()).isEqualTo(1);
    }

    @Test
    void theSameTextLanguageAndVoiceAgainIsACacheHitWithoutAProviderCallOrADebit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal first = readyProposal(owner, deck, AUDIO);
        assertThat(speech.calls).hasSize(1);
        int debitsAfterFirst = debits(owner);

        // another owner, another session: the same clip
        UUID other = UUID.randomUUID();
        UUID otherDeck = deck(other);
        Proposal second = readyProposal(other, otherDeck, AUDIO);

        assertThat(speech.calls).as("no second provider call").hasSize(1);
        UUID firstAsset = nodeAsset(owner, deck, first);
        UUID secondAsset = nodeAsset(other, otherDeck, second);
        assertThat(secondAsset).isNotEqualTo(firstAsset);
        // the owner got a new asset of their own on the cached blobs, without staging or processing
        assertThat(mediaStager.adopted).containsExactly(secondAsset);
        assertThat(mediaStager.staged).containsExactly(firstAsset);
        assertThat(jdbc.sql("SELECT owner_id FROM app_learning.media_asset WHERE asset_id=:id").param("id", secondAsset).query(UUID.class).single()).isEqualTo(other);
        assertThat(jdbc.sql("SELECT source_blob_id FROM app_learning.media_asset WHERE asset_id=:id").param("id", secondAsset).query(UUID.class).single())
                .isEqualTo(jdbc.sql("SELECT source_blob_id FROM app_learning.media_asset WHERE asset_id=:id").param("id", firstAsset).query(UUID.class).single());
        // the hit costs nothing: only the material is debited
        assertThat(debitsOf(other)).isEqualTo(pricing.credits("MATERIAL_MEDIUM"));
        assertThat(debits(owner)).isEqualTo(debitsAfterFirst);
        assertThat(slot(other, otherDeck, second).path("state").stringValue(null)).isEqualTo("READY");
        assertThat(reservationState(second.session())).isEqualTo("SETTLED");
        assertThat(cacheRows("READY")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_used_at > created_at FROM app_learning.speech_cache").query(Boolean.class).single()).isTrue();
    }

    private int debitsOf(UUID owner) { return debits(owner); }

    @Test
    void twoStepsOnOneKeyAtTheSameTimeMakeOneSynthesisAndTheLoserWaitsForTheWinnersClip() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID other = UUID.randomUUID();
        UUID otherDeck = deck(other);

        Proposal winner = proposal(owner, deck, audioSpec("[[fake:audio-hold]] с аудио"));
        assertThat(speech.entered.await(10, TimeUnit.SECONDS)).isTrue();
        double busyBefore = busyPolls();
        Proposal loser = proposal(other, otherDeck, audioSpec("[[fake:audio-hold]] с аудио"));
        // the step is RUNNING once claimed and its slot GENERATING one transaction later; a busy poll proves it reached the winner's entry
        await("the loser to wait on the winner's lease", Duration.ofSeconds(10), () -> busyPolls() > busyBefore && slotState(loser.artifact()).equals("GENERATING"));
        // the winner's call is held: until it is released these states cannot move
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_step WHERE artifact_id=:id AND kind='TTS'").param("id", loser.artifact()).query(String.class).single())
                .isEqualTo("RUNNING");
        assertThat(cacheRows("PENDING")).isEqualTo(1);
        assertThat(speech.calls).as("only the winner's held call").hasSize(1);

        speech.release.countDown();
        awaitSlot(winner.artifact(), "READY");
        awaitSlot(loser.artifact(), "READY");

        assertThat(speech.calls).as("one synthesis for both").hasSize(1);
        assertThat(cacheRows("READY")).isEqualTo(1);
        assertThat(cacheRows("PENDING")).isZero();
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM") + pricing.credits("TTS_CLIP_30S"));
        assertThat(debits(other)).isEqualTo(pricing.credits("MATERIAL_MEDIUM"));
        assertThat(mediaStager.adopted).hasSize(1);
    }

    // ----------------------------------------------------------- failures

    @Test
    void aProviderThatIsDownFailsTheSlotWithoutADebitAndRetryingFailsTheTurnWithItsHoldReleased() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);

        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio-down]] с аудио"));
        awaitSlot(proposal.artifact(), "FAILED");

        JsonNode slot = slot(owner, deck, proposal);
        assertThat(slot.path("errorCode").stringValue(null)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM"));
        assertThat(cacheRows("PENDING")).as("a failed synthesis does not keep its entry").isZero();
        assertThat(cacheRows("READY")).isZero();
        assertThat(slotEvents(owner, deck, proposal)).endsWith("FAILED:PROVIDER_UNAVAILABLE");
        assertThat(heldClips(proposal.artifact())).isZero();
        assertThat(json(getSession(owner, deck, proposal.session())).path("approvableCount").intValue()).isZero();
        MockHttpServletResponse blocked = approve(owner, deck, proposal, UUID.randomUUID());
        problem(blocked, 409, "GENERATION_STATE_CONFLICT");

        // «Повторить»: the same text, still down: the turn fails, the slot and the revision stay, nothing is debited and the hold is released
        UUID turn = redo(owner, deck, proposal, null);
        awaitTurn(turn, "FAILED");
        assertThat(turnError(turn)).isEqualTo("PROVIDER_UNAVAILABLE");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        assertThat(revisions(proposal.artifact())).isEqualTo(1);
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM"));
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
        assertThat(slotState(proposal.artifact())).isEqualTo("FAILED");

        // «Убрать блок» makes the proposal approvable without the audio
        UUID removal = accepted(owner, deck, fresh(owner, deck, proposal), editBody(UUID.randomUUID(), fresh(owner, deck, proposal).revision(), "REMOVE_MEDIA", null,
                null, audioNode(owner, deck, proposal)));
        assertThat(turnStatus(removal)).isEqualTo("APPLIED");
        assertThat(approve(owner, deck, fresh(owner, deck, proposal), UUID.randomUUID()).getStatus()).isEqualTo(200);
    }

    @Test
    void aTextWithPersonalDataIsRefusedBeforeAnyProviderCallAndNothingIsDebitedOrCached() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        int callsBefore = speech.calls.size();

        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio-personal]] с аудио"));
        awaitSlot(proposal.artifact(), "FAILED");

        assertThat(slot(owner, deck, proposal).path("errorCode").stringValue(null)).isEqualTo("PERSONAL_DATA");
        assertThat(slotEvents(owner, deck, proposal)).endsWith("FAILED:PERSONAL_DATA");
        assertThat(speech.calls).as("the text never reached the provider port").hasSize(callsBefore);
        assertThat(mediaStager.staged).isEmpty();
        assertThat(cacheRows("READY")).isZero();
        assertThat(cacheRows("PENDING")).isZero();
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM"));
        assertThat(heldClips(proposal.artifact())).isZero();

        // «Повторить» reads the same text: the turn fails with the same code, nothing changes and nothing is held
        UUID turn = redo(owner, deck, proposal, null);
        awaitTurn(turn, "FAILED");
        assertThat(turnError(turn)).isEqualTo("PERSONAL_DATA");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        assertThat(revisions(proposal.artifact())).isEqualTo(1);
        assertThat(speech.calls).hasSize(callsBefore);
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM"));
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
        assertThat(slotState(proposal.artifact())).isEqualTo("FAILED");
    }

    @Test
    void bytesThePipelineRejectsFailTheSlotAndAreNeverCached() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        mediaStager.rejectNext.set(1);

        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio-garbage]] с аудио"));
        awaitSlot(proposal.artifact(), "FAILED");

        assertThat(slot(owner, deck, proposal).path("errorCode").stringValue(null)).isEqualTo("VERIFICATION_REJECTED");
        assertThat(speech.calls).hasSize(1);
        assertThat(cacheRows("READY")).isZero();
        assertThat(cacheRows("PENDING")).isZero();
        assertThat(debits(owner)).as("a rejected clip is not charged").isEqualTo(pricing.credits("MATERIAL_MEDIUM"));
        assertThat(slotEvents(owner, deck, proposal)).endsWith("FAILED:VERIFICATION_REJECTED");
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_media_clip WHERE artifact_id=:id").param("id", proposal.artifact())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aStorageThatIsDownFailsTheSlotAsUnavailable() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        mediaStager.fail = true;

        Proposal proposal = proposal(owner, deck, audioSpec(AUDIO));
        awaitSlot(proposal.artifact(), "FAILED");

        assertThat(slot(owner, deck, proposal).path("errorCode").stringValue(null)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(cacheRows("PENDING")).isZero();
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM"));
    }

    @Test
    void aCacheEntryWhoseBlobsCouldNotBeAdoptedIsDroppedAndTheClipIsSynthesisedAgain() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        readyProposal(owner, deck, AUDIO);
        assertThat(speech.calls).hasSize(1);
        mediaStager.failAdopt = true;

        UUID other = UUID.randomUUID();
        Proposal second = proposal(other, deck(other), audioSpec(AUDIO));
        awaitSlot(second.artifact(), "READY");

        assertThat(speech.calls).as("the entry was unusable: a real synthesis").hasSize(2);
        assertThat(debits(other)).isEqualTo(pricing.credits("MATERIAL_MEDIUM") + pricing.credits("TTS_CLIP_30S"));
        assertThat(cacheRows("READY")).isEqualTo(1);
    }

    // ------------------------------------------------------------- the redo

    @Test
    void redoingAClipInTheSameVoiceIsANewTakeThatIsSynthesisedAndDebitedIntoANewAssetInANewRevision() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = readyProposal(owner, deck, AUDIO);
        UUID firstAsset = nodeAsset(owner, deck, proposal);
        UUID node = audioNode(owner, deck, proposal);
        int debitsBefore = debits(owner);

        UUID turn = redo(owner, deck, proposal, null);
        awaitTurn(turn, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");

        JsonNode detail = detail(owner, deck, proposal);
        assertThat(detail.path("revisions")).hasSize(2);
        assertThat(detail.path("revisions").get(1).path("cause").stringValue(null)).isEqualTo("MEDIA");
        UUID secondAsset = nodeAsset(owner, deck, proposal);
        assertThat(secondAsset).isNotEqualTo(firstAsset);
        JsonNode slot = detail.path("mediaSlots").get(0);
        assertThat(slot.path("assetId").stringValue(null)).isEqualTo(secondAsset.toString());
        assertThat(slot.path("voice").stringValue(null)).isEqualTo("female");
        assertThat(slot.path("state").stringValue(null)).isEqualTo("READY");
        JsonNode applied = detail.path("turns").get(0);
        assertThat(applied.path("status").stringValue(null)).isEqualTo("APPLIED");
        assertThat(applied.path("action").stringValue(null)).isEqualTo("AUDIO_REGENERATE");
        assertThat(applied.path("voice").stringValue(null)).isEqualTo("female");
        assertThat(applied.path("targetNodeIds").get(0).stringValue(null)).isEqualTo(node.toString());
        // take 1: not in the cache, so a provider call, debited from the turn's own hold
        assertThat(speech.calls).hasSize(2);
        assertThat(speech.calls.get(1).take()).isEqualTo(1);
        assertThat(debits(owner)).isEqualTo(debitsBefore + pricing.credits("TTS_CLIP_30S"));
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
        assertThat(cacheRows("READY")).isEqualTo(2);
        // both clips stay held for the life of the session (a revert can restore the first), the Workshop holds the one the node uses
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_media_clip WHERE artifact_id=:id").param("id", proposal.artifact())
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT asset_id FROM app_learning.generation_media_ref WHERE artifact_id=:id").param("id", proposal.artifact()).query(UUID.class).single())
                .isEqualTo(secondAsset);
        assertThat(slotEvents(owner, deck, proposal).getLast()).isEqualTo("READY:-");

        // approval binds the clip the node uses now
        MockHttpServletResponse approved = approve(owner, deck, fresh(owner, deck, proposal), UUID.randomUUID());
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE asset_id=:asset AND asset_owner_id=:owner").param("asset", secondAsset)
                .param("owner", owner).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE asset_id=:asset").param("asset", firstAsset).query(Integer.class).single()).isZero();
    }

    @Test
    void anotherVoiceStartsAtTakeZeroAndGoingBackToAVoiceThatWasMadeIsACacheHit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = readyProposal(owner, deck, AUDIO);
        int base = debits(owner);

        UUID male = redo(owner, deck, proposal, "male");
        awaitTurn(male, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        assertThat(speech.calls).hasSize(2);
        assertThat(speech.calls.get(1).voice()).isEqualTo("male");
        assertThat(speech.calls.get(1).take()).isZero();
        assertThat(slot(owner, deck, proposal).path("voice").stringValue(null)).isEqualTo("male");
        assertThat(detail(owner, deck, proposal).path("turns").get(0).path("voice").stringValue(null)).isEqualTo("male");
        assertThat(debits(owner)).isEqualTo(base + pricing.credits("TTS_CLIP_30S"));

        // the female voice at take 0 is in the cache from the first clip: no provider call, no debit, and still a new asset in a new revision
        UUID back = redo(owner, deck, proposal, "female");
        awaitTurn(back, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        assertThat(speech.calls).as("a cache hit").hasSize(2);
        assertThat(debits(owner)).isEqualTo(base + pricing.credits("TTS_CLIP_30S"));
        assertThat(slot(owner, deck, proposal).path("voice").stringValue(null)).isEqualTo("female");
        assertThat(revisions(proposal.artifact())).isEqualTo(3);
        assertThat(mediaStager.adopted).hasSize(1);
        assertThat(mediaStager.adopted.getFirst()).isEqualTo(nodeAsset(owner, deck, proposal));
        // the hold of that turn was released unspent
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
    }

    @Test
    void aSameVoiceRedoAfterARevertIsAboveEveryTakeTheSlotHasHad() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = readyProposal(owner, deck, AUDIO);
        UUID turn = redo(owner, deck, proposal, null);
        awaitTurn(turn, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        assertThat(speech.calls.getLast().take()).isEqualTo(1);

        Proposal now = fresh(owner, deck, proposal);
        assertThat(revert(owner, deck, now, now.version(), proposal.revision()).getStatus()).isEqualTo(200);
        UUID again = redo(owner, deck, fresh(owner, deck, proposal), null);
        awaitTurn(again, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");

        assertThat(speech.calls.getLast().take()).as("take 1 was already made; the slot spec said 0 after the revert").isEqualTo(2);
        assertThat(speech.calls).hasSize(3);
    }

    @Test
    void revertingARedoGivesTheSlotTheClipAndTheVoiceOfTheRevisionThatIsShown() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = readyProposal(owner, deck, AUDIO);
        UUID firstAsset = nodeAsset(owner, deck, proposal);
        UUID turn = redo(owner, deck, proposal, "male");
        awaitTurn(turn, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        UUID maleAsset = nodeAsset(owner, deck, proposal);
        assertThat(maleAsset).isNotEqualTo(firstAsset);

        Proposal now = fresh(owner, deck, proposal);
        MockHttpServletResponse back = revert(owner, deck, now, now.version(), proposal.revision());
        assertThat(back.getStatus()).as(back.getContentAsString()).isEqualTo(200);

        JsonNode slot = slot(owner, deck, proposal);
        assertThat(slot.path("assetId").stringValue(null)).isEqualTo(firstAsset.toString());
        assertThat(slot.path("voice").stringValue(null)).isEqualTo("female");
        assertThat(slot.path("state").stringValue(null)).isEqualTo("READY");
        assertThat(jdbc.sql("SELECT asset_id FROM app_learning.generation_media_ref WHERE artifact_id=:id").param("id", proposal.artifact()).query(UUID.class).single())
                .isEqualTo(firstAsset);
        // the clip of the other revision is still held: undoing the undo restores it
        Proposal undone = fresh(owner, deck, proposal);
        UUID latest = UUID.fromString(detail(owner, deck, proposal).path("revisions").get(1).path("revisionId").stringValue(null));
        assertThat(revert(owner, deck, undone, undone.version(), latest).getStatus()).isEqualTo(200);
        assertThat(slot(owner, deck, proposal).path("assetId").stringValue(null)).isEqualTo(maleAsset.toString());
        assertThat(slot(owner, deck, proposal).path("voice").stringValue(null)).isEqualTo("male");
    }

    @Test
    void aRedoThatReplacesAFirstClipStillBeingMadeStopsThatStepAndTakesItsPlace() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio-hold]] с аудио"));
        assertThat(speech.entered.await(10, TimeUnit.SECONDS)).isTrue();

        UUID turn = redo(owner, deck, proposal, "male");
        // the first step is told to stop and the turn's own step makes the clip in the other voice (its text is held in the double too: let it go)
        speech.release.countDown();
        awaitTurn(turn, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");

        assertThat(slotState(proposal.artifact())).isEqualTo("READY");
        assertThat(slot(owner, deck, proposal).path("voice").stringValue(null)).isEqualTo("male");
        await("the replaced first step to end", Duration.ofSeconds(15), () -> jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_step WHERE artifact_id=:id "
                + "AND kind='TTS' AND state IN ('CANCELLED','SUCCEEDED')").param("id", proposal.artifact()).query(Integer.class).single() == 2);
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
    }

    @Test
    void cancellingARunningRedoEndsItsTurnCancelledWithItsHoldReleasedAndTheSlotAsItWas() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = readyProposal(owner, deck, AUDIO);
        // the redo is a new take of the same text, held in the provider call
        jdbc.sql("DELETE FROM app_learning.speech_cache").update();
        jdbc.sql("UPDATE app_learning.generation_media_slot SET spec=jsonb_set(spec,'{text}','\"行く [[fake:tts-hold]]\"') WHERE artifact_id=:id").param("id", proposal.artifact()).update();
        UUID turn = redo(owner, deck, proposal, null);
        assertThat(speech.entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(cancel(owner, deck, proposal.session(), UUID.randomUUID()).getStatus()).isEqualTo(200);

        await("the turn to end", Duration.ofSeconds(15), () -> turnStatus(turn).equals("CANCELLED"));
        // the step stops its held call and gives its cache entry back
        await("the cache entry to be released", Duration.ofSeconds(15), () -> cacheRows("PENDING") == 0);
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
    }

    // ------------------------------------------------------------ request shape

    @Test
    void theRedoTakesAVoiceOnlyForTheRedoOfAudioAndOnlyOneAudioBlock() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = readyProposal(owner, deck, AUDIO);
        UUID node = audioNode(owner, deck, proposal);
        UUID paragraph = id(blocks(detail(owner, deck, proposal)).get(1));
        for (ObjectNode bad : List.of(
                editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, paragraph).put("voice", "male"),
                editBody(UUID.randomUUID(), proposal.revision(), "AUDIO_REGENERATE", null, null, node).put("voice", "robot"),
                editBody(UUID.randomUUID(), proposal.revision(), "AUDIO_REGENERATE", null, null, node, paragraph),
                editBody(UUID.randomUUID(), proposal.revision(), "AUDIO_REGENERATE", null, null, paragraph))) {
            problem(edit(owner, deck, proposal, bad), 400, "INVALID_REQUEST");
        }
        assertThat(speech.calls).hasSize(1);
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
    }

    @Test
    void theCapabilityGateRefusesTheRedoWhenSpeechIsUnavailable() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = readyProposal(owner, deck, AUDIO);
        jdbc.sql("UPDATE app_learning.ai_provider_call SET cost_micros=0 WHERE capability='TTS'").update();
        assertThat(port.configured()).isTrue();
        assertThat(settings.maxText()).isEqualTo(600);
        // the Stub is always configured; the gate's own states are covered by LearningCapabilitiesTest, here the redo of a missing slot is a plain 404/400
        MockHttpServletResponse missing = edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "AUDIO_REGENERATE", null, null, UUID.randomUUID()));
        problem(missing, 400, "INVALID_REQUEST");
    }

    // ------------------------------------------------------------- the cache itself

    @Test
    void theKeyNormalisesTextButNotCaseOrYoAndSeparatesLanguageVoiceTakeAndProvider() {
        SpeechSynthesis.Identity identity = new SpeechSynthesis.Identity("google", "gemini-3.8-flash-tts", "v1", "wav", "Kore");
        byte[] base = SpeechCache.key("Ёж  идёт\n домой ", "ru", identity, 0).hash();
        assertThat(SpeechCache.key("Ёж идёт домой", "RU", identity, 0).hash()).isEqualTo(base);
        // a precomposed and a decomposed é are the same text
        assertThat(SpeechCache.key("Ёж\u00a0идёт\u3000домой\u2028", "ru", identity, 0).hash()).as("separators collapse like spaces").isEqualTo(base);
        assertThat(SpeechCache.key("café", "fr", identity, 0).hash()).isEqualTo(SpeechCache.key("café", "fr", identity, 0).hash());
        assertThat(SpeechCache.key("ёж идёт домой", "ru", identity, 0).hash()).as("case is speech").isNotEqualTo(base);
        assertThat(SpeechCache.key("Еж идет домой", "ru", identity, 0).hash()).as("yo is speech").isNotEqualTo(base);
        // the language is not in the key: Gemini is sent none and SpeechKit speaks Russian only, so one text is one clip in a material (es) and an exercise (en)
        assertThat(SpeechCache.key("Hola", "es", identity, 0).hash()).isEqualTo(SpeechCache.key("Hola", "en", identity, 0).hash());
        assertThat(SpeechCache.key("Hola", "es-419", identity, 0).hash()).isEqualTo(SpeechCache.key("Hola", "es-ES", identity, 0).hash());
        // a sent style (a hash of it rides on the modelVersion) is part of the key
        assertThat(SpeechCache.key("Ёж идёт домой", "ru", new SpeechSynthesis.Identity("google", "gemini-3.8-flash-tts", "v1.0a1b2c3d", "wav", "Kore"), 0).hash())
                .isNotEqualTo(base);
        assertThat(SpeechCache.key("Ёж идёт домой", "ru", identity, 1).hash()).isNotEqualTo(base);
        assertThat(SpeechCache.key("Ёж идёт домой", "ru", new SpeechSynthesis.Identity("google", "gemini-3.8-flash-tts", "v1", "wav", "Charon"), 0).hash()).isNotEqualTo(base);
        assertThat(SpeechCache.key("Ёж идёт домой", "ru", new SpeechSynthesis.Identity("google", "gemini-3.8-flash-tts", "v2", "wav", "Kore"), 0).hash()).isNotEqualTo(base);
        assertThat(SpeechCache.key("Ёж идёт домой", "ru", new SpeechSynthesis.Identity("yandex", "gemini-3.8-flash-tts", "v1", "mp3", "Kore"), 0).hash()).isNotEqualTo(base);
        assertThat(base).hasSize(32);
    }

    @Test
    void theJournalTellsAResumedStepHowManyProviderCallsAnsweredAndWhatTheyCost() {
        UUID step = UUID.randomUUID();
        assertThat(generationRepository.providerSpend(step, "TTS")).isEqualTo(new GenerationRepository.ProviderSpend(0, 0));
        for (String outcome : List.of("OK", "TRANSIENT", "OK")) {
            UUID call = UUID.randomUUID();
            jdbc.sql("INSERT INTO app_learning.ai_provider_call(call_id,step_id,attempt,capability,provider,model,request_hash) VALUES (:id,:step,1,'TTS','stub','m',:hash)")
                    .param("id", call).param("step", step).param("hash", "a".repeat(64)).update();
            jdbc.sql("UPDATE app_learning.ai_provider_call SET outcome=:outcome,cost_micros=40 WHERE call_id=:id").param("outcome", outcome).param("id", call).update();
        }
        assertThat(generationRepository.providerSpend(step, "TTS")).isEqualTo(new GenerationRepository.ProviderSpend(2, 80));
        // the rows are shared with the other tests of the class (one of them rewrites every TTS row): leave none behind
        jdbc.sql("DELETE FROM app_learning.ai_provider_call WHERE step_id=:step").param("step", step).update();
    }

    @Test
    void anEntryIsNotPublishedOnABlobTheGcBeganToReclaim() throws Exception {
        UUID owner = UUID.randomUUID();
        readyProposal(owner, deck(owner), AUDIO);
        UUID blob = jdbc.sql("SELECT unnest(blob_ids) FROM app_learning.speech_cache WHERE state='READY' LIMIT 1").query(UUID.class).single();
        String objectKey = jdbc.sql("SELECT object_key FROM app_learning.media_blob WHERE blob_id=:blob").param("blob", blob).query(String.class).single();
        SpeechSynthesis.Identity identity = new SpeechSynthesis.Identity("stub", "stub-tts", "1", "wav", "female");
        SpeechCache.Key key = SpeechCache.key("gc " + UUID.randomUUID(), "en", identity, 0);
        UUID token = ((SpeechCache.Claim.Won) cache.claim(key)).token();
        jdbc.sql("INSERT INTO app_learning.media_gc_object(object_key,origin,created_at,state,first_scan_at,delete_token,lease_until,updated_at) "
                + "VALUES (:key,'catalog',CURRENT_TIMESTAMP,'DELETING',CURRENT_TIMESTAMP,:token,CURRENT_TIMESTAMP + interval '1 hour',CURRENT_TIMESTAMP) "
                + "ON CONFLICT (object_key) DO UPDATE SET state='DELETING',first_scan_at=CURRENT_TIMESTAMP,delete_token=:token,"
                + "lease_until=CURRENT_TIMESTAMP + interval '1 hour'").param("key", objectKey).param("token", UUID.randomUUID()).update();
        var media = new GeneratedMediaStager.VerifiedMedia(blob, List.of());

        assertThat(cache.publish(key, token, media, 0, 0)).isFalse();
        jdbc.sql("DELETE FROM app_learning.media_gc_object WHERE object_key=:key").param("key", objectKey).update();
        assertThat(cache.publish(key, token, media, 0, 0)).as("derives size and duration when they are unknown").isTrue();
    }

    @Test
    void aHitTouchesLastUsedAtAtMostOncePerDay() {
        SpeechSynthesis.Identity identity = new SpeechSynthesis.Identity("stub", "stub-tts", "1", "wav", "female");
        SpeechCache.Key key = SpeechCache.key("touch " + UUID.randomUUID(), "en", identity, 0);
        UUID token = ((SpeechCache.Claim.Won) cache.claim(key)).token();
        // its own precondition: a blob of its own (a random digest, so no other test's blob can collide), removed whatever the outcome
        UUID blob = UUID.randomUUID();
        insertBlob(blob);
        try {
            GeneratedMediaStager.VerifiedMedia media = new GeneratedMediaStager.VerifiedMedia(blob, List.of());
            assertThat(cache.publish(key, token, media, 1_000, 10)).isTrue();
            String used = "SELECT last_used_at FROM app_learning.speech_cache WHERE cache_key=:key";
            var fresh = jdbc.sql(used).param("key", key.hash()).query(java.time.OffsetDateTime.class).single();

            assertThat(cache.claim(key)).isInstanceOf(SpeechCache.Claim.Hit.class);
            assertThat(jdbc.sql(used).param("key", key.hash()).query(java.time.OffsetDateTime.class).single()).as("a hit within the day writes nothing").isEqualTo(fresh);

            jdbc.sql("UPDATE app_learning.speech_cache SET last_used_at=CURRENT_TIMESTAMP - interval '2 days' WHERE cache_key=:key").param("key", key.hash()).update();
            assertThat(cache.claim(key)).isInstanceOf(SpeechCache.Claim.Hit.class);
            assertThat(jdbc.sql(used).param("key", key.hash()).query(java.time.OffsetDateTime.class).single()).as("a day later it is touched").isAfter(fresh.minusMinutes(1));
        } finally {
            cache.drop(key);
            jdbc.sql("DELETE FROM app_learning.media_blob WHERE blob_id=:blob").param("blob", blob).update();
        }
    }

    private void insertBlob(UUID blob) {
        byte[] digest = new byte[32];
        new java.security.SecureRandom().nextBytes(digest);
        jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) VALUES (:blob,:hash,1,'audio/wav',:key,CURRENT_TIMESTAMP)")
                .param("blob", blob).param("hash", digest).param("key", "k/" + blob).update();
    }

    @Test
    void anEntryIsClaimedOnceLeasedTakenOverWhenTheLeaseRunsOutAndEvictedWhenUnused() throws Exception {
        SpeechSynthesis.Identity identity = new SpeechSynthesis.Identity("stub", "stub-tts", "1", "wav", "female");
        SpeechCache.Key key = SpeechCache.key("claim " + UUID.randomUUID(), "en", identity, 0);

        SpeechCache.Claim first = cache.claim(key);
        assertThat(first).isInstanceOf(SpeechCache.Claim.Won.class);
        UUID token = ((SpeechCache.Claim.Won) first).token();
        assertThat(cache.claim(key)).isInstanceOf(SpeechCache.Claim.Busy.class);
        assertThat(cache.renew(key, token)).isTrue();
        assertThat(cache.renew(key, UUID.randomUUID())).isFalse();

        // the winner died: the lease runs out and the next caller owns the entry; the old token is void
        jdbc.sql("UPDATE app_learning.speech_cache SET lease_until=CURRENT_TIMESTAMP - interval '1 second' WHERE cache_key=:key").param("key", key.hash()).update();
        SpeechCache.Claim taken = cache.claim(key);
        assertThat(taken).isInstanceOf(SpeechCache.Claim.Won.class);
        UUID newToken = ((SpeechCache.Claim.Won) taken).token();
        assertThat(newToken).isNotEqualTo(token);
        GeneratedMediaStager.VerifiedMedia media = new GeneratedMediaStager.VerifiedMedia(UUID.randomUUID(), List.of());
        assertThat(cache.publish(key, token, media, 1_000, 10)).isFalse();

        cache.abandon(key, newToken);
        SpeechCache.Claim again = cache.claim(key);
        assertThat(again).isInstanceOf(SpeechCache.Claim.Won.class);
        cache.abandon(key, ((SpeechCache.Claim.Won) again).token());
        cache.drop(key);

        // unused entries are evicted after the TTL, a PENDING one a day after its lease ran out; a recently used one stays
        jdbc.sql("DELETE FROM app_learning.speech_cache").update();
        SpeechCache.Key old = SpeechCache.key("old " + UUID.randomUUID(), "en", identity, 0);
        SpeechCache.Key fresh = SpeechCache.key("fresh " + UUID.randomUUID(), "en", identity, 0);
        SpeechCache.Key stuck = SpeechCache.key("stuck " + UUID.randomUUID(), "en", identity, 0);
        for (SpeechCache.Key each : List.of(old, fresh, stuck)) cache.claim(each);
        UUID blob = UUID.randomUUID();
        insertBlob(blob);
        GeneratedMediaStager.VerifiedMedia verified = new GeneratedMediaStager.VerifiedMedia(blob, List.of());
        for (SpeechCache.Key each : List.of(old, fresh)) {
            UUID owned = jdbc.sql("SELECT lease_token FROM app_learning.speech_cache WHERE cache_key=:key").param("key", each.hash()).query(UUID.class).single();
            assertThat(cache.publish(each, owned, verified, 1_000, 1)).isTrue();
        }
        jdbc.sql("UPDATE app_learning.speech_cache SET last_used_at=CURRENT_TIMESTAMP - interval '181 days' WHERE cache_key=:key").param("key", old.hash()).update();
        jdbc.sql("UPDATE app_learning.speech_cache SET lease_until=CURRENT_TIMESTAMP - interval '2 days' WHERE cache_key=:key").param("key", stuck.hash()).update();
        assertThat(cache.evict(10)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.speech_cache").query(Integer.class).single()).isEqualTo(1);
        assertThat(cache.claim(fresh)).isInstanceOf(SpeechCache.Claim.Hit.class);
        jdbc.sql("DELETE FROM app_learning.media_blob WHERE blob_id=:blob").param("blob", blob).update();
    }

    @Test
    void anEntryWhoseBlobIsGoneIsNotPublished() throws Exception {
        SpeechSynthesis.Identity identity = new SpeechSynthesis.Identity("stub", "stub-tts", "1", "wav", "female");
        SpeechCache.Key key = SpeechCache.key("gone " + UUID.randomUUID(), "en", identity, 0);
        UUID token = ((SpeechCache.Claim.Won) cache.claim(key)).token();
        assertThat(cache.publish(key, token, new GeneratedMediaStager.VerifiedMedia(UUID.randomUUID(), List.of()), 1_000, 10)).isFalse();
        cache.abandon(key, token);
    }
}
