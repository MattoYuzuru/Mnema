package app.mnema.learning.generation;

import app.mnema.learning.usage.AdmissionPricing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Acceptance of #296 (AI-10) on the Stub image search, PostgreSQL and the real catalog (the media pipeline's staging is the test double of
 * {@link GenerationTestConfiguration.FakeStager}; the real one is tested on MinIO in the media package): the first search of a slot, the
 * IMAGE_SEARCH turn, the choice of another candidate, failure and cancellation, revert, approval with attribution, and the media GC's holds.
 */
class GenerationImageSearchIntegrationTest extends GenerationEditsSupport {
    private static final String IMAGE = "[[fake:image]] лиса";

    @Autowired private GenerationTestConfiguration.FakeStager stager;
    @Autowired private AdmissionPricing pricing;

    @BeforeEach
    void resetStager() { stager.reset(); }

    // ------------------------------------------------------------------ helpers

    private static ObjectNode imageSpec(String prompt) {
        ObjectNode spec = spec(prompt);
        ((ObjectNode) spec.path("settings")).putObject("media").put("imageSearch", true);
        return spec;
    }

    private String slotState(UUID artifact) {
        return jdbc.sql("SELECT state FROM app_learning.generation_media_slot WHERE artifact_id=:id").param("id", artifact).query(String.class).single();
    }

    private String slotError(UUID artifact) {
        return jdbc.sql("SELECT COALESCE(error_code,'-') FROM app_learning.generation_media_slot WHERE artifact_id=:id").param("id", artifact)
                .query(String.class).single();
    }

    private void awaitSlot(UUID artifact, String state) throws InterruptedException {
        await("slot of " + artifact + " to be " + state, Duration.ofSeconds(20), () -> slotState(artifact).equals(state));
    }

    private JsonNode slot(UUID owner, UUID deck, Proposal proposal) throws Exception {
        return detail(owner, deck, proposal).path("mediaSlots").get(0);
    }

    private static List<String> keys(JsonNode candidates) {
        List<String> keys = new ArrayList<>();
        candidates.forEach(candidate -> keys.add(candidate.path("source").stringValue(null) + ":" + candidate.path("title").stringValue(null)));
        return keys;
    }

    private static JsonNode chosen(JsonNode slot) {
        for (JsonNode candidate : slot.path("candidates")) if (candidate.path("chosen").asBoolean()) return candidate;
        return null;
    }

    private UUID nodeAsset(UUID owner, UUID deck, Proposal proposal) throws Exception {
        for (JsonNode block : blocks(detail(owner, deck, proposal))) {
            if (block.path("type").stringValue("").equals("image")) return UUID.fromString(block.path("attrs").path("assetId").stringValue(null));
        }
        throw new AssertionError("no image node");
    }

    private UUID searchTurn(UUID owner, UUID deck, Proposal proposal, String instruction) throws Exception {
        UUID picture = id(blocks(detail(owner, deck, proposal)).stream().filter(block -> block.path("type").stringValue("").equals("image")).findFirst().orElseThrow());
        Proposal now = fresh(owner, deck, proposal);
        return accepted(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "IMAGE_SEARCH", null, instruction, picture));
    }

    private MockHttpServletResponse select(UUID owner, UUID deck, Proposal proposal, UUID command, UUID expectedRevision, UUID candidate) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString()).put("expectedRevisionId", expectedRevision.toString())
                .put("candidateId", candidate.toString());
        return selectRaw(owner, deck, proposal, "i1", body.toString());
    }

    private MockHttpServletResponse selectRaw(UUID owner, UUID deck, Proposal proposal, String slotKey, String body) throws Exception {
        return send(owner, post(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/media-slots/" + slotKey + "/selection")
                .contentType("application/json").content(body));
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

    private int holds(UUID artifact) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_media_ref WHERE artifact_id=:id").param("id", artifact)
                .query(Integer.class).single();
    }

    private int candidateRows(UUID artifact) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_media_candidate WHERE artifact_id=:id").param("id", artifact)
                .query(Integer.class).single();
    }

    // ------------------------------------------------------- the first search

    @Test
    void theFirstSearchOfASlotMakesItReadyOnItsOwnAssetWithOneCandidateAttributionAndTheDebit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        stager.delayPolls = 1;

        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");

        JsonNode slot = slot(owner, deck, proposal);
        UUID assetId = UUID.fromString(slot.path("assetId").stringValue(null));
        assertThat(slot.path("slotKey").stringValue(null)).isEqualTo("i1");
        assertThat(slot.path("kind").stringValue(null)).isEqualTo("IMAGE");
        assertThat(slot.path("mode").stringValue(null)).isEqualTo("search");
        assertThat(slot.path("state").stringValue(null)).isEqualTo("READY");
        assertThat(slot.path("errorCode").isNull()).isTrue();
        // the slot's own pre-allocated asset is the staged one, and the image node of the revision uses it
        assertThat(assetId).isEqualTo(nodeAsset(owner, deck, proposal)).isEqualTo(stager.staged.getFirst());
        assertThat(slot.path("candidates")).hasSize(1);
        JsonNode candidate = slot.path("candidates").get(0);
        assertThat(candidate.path("assetId").stringValue(null)).isEqualTo(assetId.toString());
        assertThat(candidate.path("state").stringValue(null)).isEqualTo("READY");
        assertThat(candidate.path("chosen").booleanValue()).isTrue();
        assertThat(candidate.path("source").stringValue(null)).isEqualTo("STUB");
        assertThat(candidate.path("license").stringValue(null)).isEqualTo("CC0 1.0");
        assertThat(candidate.path("sourcePageUrl").stringValue(null)).startsWith("https://example.org/stub/");
        assertThat(candidate.path("shareAlike").booleanValue()).isFalse();
        assertThat(slot.path("attribution").path("source").stringValue(null)).isEqualTo("STUB");
        assertThat(slot.path("attribution").path("author").stringValue(null)).isEqualTo("Stub Author");
        assertThat(slot.path("attribution").path("license").stringValue(null)).isEqualTo("CC0 1.0");
        // no new revision: the initial one is still the only one
        assertThat(revisions(proposal.artifact())).isEqualTo(1);
        // the log tells the client the slot's way, and the step ended
        assertThat(slotEvents(owner, deck, proposal)).containsSubsequence("PENDING:-", "GENERATING:-", "VERIFYING:-", "READY:-");
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_step WHERE artifact_id=:id AND kind='IMAGE_SEARCH'").param("id", proposal.artifact())
                .query(String.class).single()).isEqualTo("SUCCEEDED");
        // the Workshop holds the asset; the debit is the material and one search, from the batch hold, which ended with the last step
        assertThat(holds(proposal.artifact())).isEqualTo(1);
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM") + pricing.credits("IMAGE_SEARCH"));
        assertThat(reservationState(proposal.session())).isEqualTo("SETTLED");
        assertThat(json(getSession(owner, deck, proposal.session())).path("approvableCount").intValue()).isEqualTo(1);
        assertThat(stager.transactionAtStage).containsOnly(false);
    }

    @Test
    void anImageThatIsReadyAndApprovedCreditsItsSourceInTheCaptionAndInTheProvenance() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        UUID asset = nodeAsset(owner, deck, proposal);

        MockHttpServletResponse response = approve(owner, deck, proposal, UUID.randomUUID());

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode reference = json(response).path("artifacts").get(0).path("publishedRef");
        JsonNode material = items.read(owner, deck, UUID.fromString(reference.path("memberKey").stringValue(null)), null);
        JsonNode image = null;
        for (JsonNode block : material.path("document").path("root").path("content")) if (block.path("type").stringValue("").equals("image")) image = block;
        assertThat(image).isNotNull();
        assertThat(image.path("attrs").path("assetId").stringValue(null)).isEqualTo(asset.toString());
        assertThat(image.path("attrs").path("caption").stringValue(null)).isEqualTo("Stub Author · Тестовый источник · CC0 1.0");
        // the Workshop's revision itself carries no caption: the attribution is written at the boundary only
        assertThat(blocks(detail(owner, deck, proposal)).stream().filter(block -> block.path("type").stringValue("").equals("image")).findFirst().orElseThrow()
                .path("attrs").has("caption")).isFalse();
        JsonNode media = JSON.readTree(jdbc.sql("SELECT media::text FROM app_learning.generation_provenance WHERE artifact_id=:id").param("id", proposal.artifact())
                .query(String.class).single());
        assertThat(media).hasSize(1);
        assertThat(media.get(0).path("assetId").stringValue(null)).isEqualTo(asset.toString());
        assertThat(media.get(0).path("source").stringValue(null)).isEqualTo("STUB");
        assertThat(media.get(0).path("license").stringValue(null)).isEqualTo("CC0 1.0");
        assertThat(media.get(0).path("sourcePageUrl").stringValue(null)).startsWith("https://example.org/stub/");
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.content_media_ref WHERE asset_id=:asset AND owner_id=:owner").param("asset", asset)
                .param("owner", owner).query(Integer.class).single()).isEqualTo(1);
        assertThat(holds(proposal.artifact())).isZero();
    }

    @Test
    void handingOffAReadyImageOpensADraftWhoseImageCreditsItsSource() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        Proposal now = fresh(owner, deck, proposal);

        MockHttpServletResponse response = handoff(owner, deck, now, UUID.randomUUID());

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        UUID draft = UUID.fromString(json(response).path("draft").path("draftId").stringValue(null));
        JsonNode stored = JSON.readTree(jdbc.sql("SELECT document::text FROM app_learning.editing_draft WHERE draft_id=:id AND owner_id=:owner").param("id", draft)
                .param("owner", owner).query(String.class).single());
        JsonNode image = null;
        for (JsonNode block : stored.path("root").path("content")) if (block.path("type").stringValue("").equals("image")) image = block;
        assertThat(image).isNotNull();
        assertThat(image.path("attrs").path("caption").stringValue(null)).isEqualTo("Stub Author · Тестовый источник · CC0 1.0");
    }

    @Test
    void everySourceDownFailsTheSlotWithAWayOutAndNothingIsDebitedForIt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);

        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE + " [[stub:image-down]]"));
        awaitSlot(proposal.artifact(), "FAILED");

        assertThat(slotError(proposal.artifact())).isEqualTo("PROVIDER_UNAVAILABLE");
        JsonNode slot = slot(owner, deck, proposal);
        assertThat(slot.path("state").stringValue(null)).isEqualTo("FAILED");
        assertThat(slot.path("errorCode").stringValue(null)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(slot.path("candidates")).isEmpty();
        assertThat(slot.path("attribution").isNull()).isTrue();
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM"));
        assertThat(reservationState(proposal.session())).isNotEqualTo("ACTIVE");
        assertThat(slotEvents(owner, deck, proposal)).containsSubsequence("GENERATING:-", "FAILED:PROVIDER_UNAVAILABLE");
        // it blocks approval, and the three ways out are there: «Повторить», «Заменить» (both IMAGE_SEARCH) and «Убрать блок»
        MockHttpServletResponse blocked = approve(owner, deck, fresh(owner, deck, proposal), UUID.randomUUID());
        problem(blocked, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(blocked).path("reason").stringValue(null)).isEqualTo("MEDIA_NOT_READY");
        UUID retry = searchTurn(owner, deck, proposal, null);
        awaitTurn(retry, "FAILED");
        assertThat(turnError(retry)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(reservationOfTurn(retry)).isEqualTo("RELEASED");
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");
        assertThat(revisions(proposal.artifact())).isEqualTo(1);
        assertThat(slotState(proposal.artifact())).isEqualTo("FAILED");

        UUID replace = searchTurn(owner, deck, proposal, "red fox");
        awaitTurn(replace, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        JsonNode after = slot(owner, deck, proposal);
        assertThat(after.path("state").stringValue(null)).isEqualTo("READY");
        assertThat(after.path("errorCode").isNull()).isTrue();
        assertThat(after.path("candidates")).hasSize(ImageSearchExecutor.NEW_PER_TURN);
        assertThat(chosen(after)).isNotNull();
        assertThat(json(getSession(owner, deck, proposal.session())).path("approvableCount").intValue()).isEqualTo(1);
        assertThat(approve(owner, deck, fresh(owner, deck, proposal), UUID.randomUUID()).getStatus()).isEqualTo(200);
    }

    @Test
    void aSearchWithNothingLicensedFailsTheSlotWithNoResultAndTheBlockCanBeRemoved() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE + " [[stub:image-none]]"));
        awaitSlot(proposal.artifact(), "FAILED");

        assertThat(slotError(proposal.artifact())).isEqualTo("NO_RESULT");
        assertThat(candidateRows(proposal.artifact())).isZero();
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM"));

        Proposal now = fresh(owner, deck, proposal);
        UUID picture = id(blocks(detail(owner, deck, now)).stream().filter(block -> block.path("type").stringValue("").equals("image")).findFirst().orElseThrow());
        assertThat(edit(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "REMOVE_MEDIA", null, null, picture)).getStatus()).isEqualTo(202);
        assertThat(slotState(proposal.artifact())).isEqualTo("REMOVED");
        assertThat(json(getSession(owner, deck, proposal.session())).path("approvableCount").intValue()).isEqualTo(1);
    }

    @Test
    void aStorageThatIsDownFailsTheSlotAndARejectedImageFailsItWithVerificationRejected() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        stager.fail = true;
        Proposal down = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(down.artifact(), "FAILED");
        assertThat(slotError(down.artifact())).isEqualTo("PROVIDER_UNAVAILABLE");

        stager.reset();
        stager.mode = GenerationTestConfiguration.FakeStager.Mode.REJECT;
        Proposal rejected = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(rejected.artifact(), "FAILED");
        assertThat(slotError(rejected.artifact())).isEqualTo("VERIFICATION_REJECTED");
        assertThat(slot(owner, deck, rejected).path("candidates").get(0).path("state").stringValue(null)).isEqualTo("FAILED");
        assertThat(slotEvents(owner, deck, rejected)).containsSubsequence("VERIFYING:-", "FAILED:VERIFICATION_REJECTED");
        assertThat(debits(owner)).isEqualTo(2 * pricing.credits("MATERIAL_MEDIUM"));
    }

    @Test
    void cancellingTheSessionWhileAnImageIsVerifyingFailsTheSlotAsCancelledAndReleasesTheHold() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        stager.mode = GenerationTestConfiguration.FakeStager.Mode.STAY_VERIFYING;
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "VERIFYING");
        // the batch hold lives on while the step works, though the session is in REVIEW
        assertThat(sessionState(proposal.session())).isEqualTo("REVIEW");
        assertThat(reservationState(proposal.session())).isEqualTo("ACTIVE");

        assertThat(cancel(owner, deck, proposal.session(), UUID.randomUUID()).getStatus()).isEqualTo(200);

        awaitSlot(proposal.artifact(), "FAILED");
        assertThat(slotError(proposal.artifact())).isEqualTo("CANCELLED");
        assertThat(reservationState(proposal.session())).isNotEqualTo("ACTIVE");
        await("the step to end", Duration.ofSeconds(10), () -> !jdbc.sql("SELECT state FROM app_learning.generation_step WHERE artifact_id=:id AND kind='IMAGE_SEARCH'")
                .param("id", proposal.artifact()).query(String.class).single().equals("RUNNING"));
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_step WHERE artifact_id=:id AND kind='IMAGE_SEARCH'").param("id", proposal.artifact())
                .query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(debits(owner)).isEqualTo(pricing.credits("MATERIAL_MEDIUM"));
    }

    // ----------------------------------------------------------------- the turn

    @Test
    void anImageSearchTurnStagesNewCandidatesAndAppliesTheFirstReadyOneInAMediaRevision() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        JsonNode before = slot(owner, deck, proposal);
        UUID original = UUID.fromString(before.path("assetId").stringValue(null));
        int debited = debits(owner);
        stager.staged.clear();

        UUID turn = searchTurn(owner, deck, proposal, "лиса зимой");
        assertThat(turnStatus(turn)).isIn("QUEUED", "RUNNING", "APPLIED");
        awaitTurn(turn, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");

        JsonNode after = slot(owner, deck, proposal);
        assertThat(after.path("candidates")).hasSize(1 + ImageSearchExecutor.NEW_PER_TURN);
        assertThat(after.path("candidates").get(0).path("candidateId").stringValue(null)).isEqualTo(before.path("candidates").get(0).path("candidateId").stringValue(null));
        // the first new candidate is the chosen one, in a new revision (cause MEDIA) that differs from the old one only in the image node's asset
        assertThat(stager.staged).hasSize(ImageSearchExecutor.NEW_PER_TURN);
        UUID applied = UUID.fromString(after.path("assetId").stringValue(null));
        assertThat(applied).isEqualTo(stager.staged.getFirst()).isNotEqualTo(original).isEqualTo(nodeAsset(owner, deck, proposal));
        assertThat(chosen(after).path("assetId").stringValue(null)).isEqualTo(applied.toString());
        assertThat(after.path("candidates").get(0).path("chosen").booleanValue()).isFalse();
        assertThat(after.path("attribution").path("source").stringValue(null)).isEqualTo("STUB");
        JsonNode detail = detail(owner, deck, proposal);
        assertThat(detail.path("revision").path("cause").stringValue(null)).isEqualTo("MEDIA");
        assertThat(detail.path("revisions")).hasSize(2);
        JsonNode listed = detail.path("turns").get(0);
        assertThat(listed.path("action").stringValue(null)).isEqualTo("IMAGE_SEARCH");
        assertThat(listed.path("status").stringValue(null)).isEqualTo("APPLIED");
        assertThat(listed.path("instruction").stringValue(null)).isEqualTo("лиса зимой");
        assertThat(listed.path("resultRevisionId").stringValue(null)).isEqualTo(detail.path("currentRevisionId").stringValue(null));
        assertThat(listed.path("errorCode").isNull()).isTrue();
        // text blocks are the very same JSON as before
        JsonNode earlier = detail(owner, deck, proposal, "?revisionId=" + proposal.revision());
        for (int index = 0; index < blocks(earlier).size(); index++) {
            if (!blocks(earlier).get(index).path("type").stringValue("").equals("image")) assertThat(blocks(detail).get(index)).isEqualTo(blocks(earlier).get(index));
        }
        // the turn paid for itself from its own hold; the node is held on the new asset
        assertThat(debits(owner) - debited).isEqualTo(pricing.credits("IMAGE_SEARCH"));
        assertThat(reservationOfTurn(turn)).isEqualTo("SETTLED");
        assertThat(holds(proposal.artifact())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT asset_id FROM app_learning.generation_media_ref WHERE artifact_id=:id").param("id", proposal.artifact()).query(UUID.class).single())
                .isEqualTo(applied);
        assertThat(slotEvents(owner, deck, proposal)).endsWith("READY:-");
        assertThat(stager.transactionAtStage).containsOnly(false);

        // «Вернуть»: the ordinary revert brings the earlier image back, slot and hold included, and forward again
        Proposal now = fresh(owner, deck, proposal);
        assertThat(revert(owner, deck, now, now.version(), proposal.revision()).getStatus()).isEqualTo(200);
        JsonNode reverted = slot(owner, deck, proposal);
        assertThat(reverted.path("assetId").stringValue(null)).isEqualTo(original.toString());
        assertThat(chosen(reverted).path("candidateId").stringValue(null)).isEqualTo(before.path("candidates").get(0).path("candidateId").stringValue(null));
        assertThat(jdbc.sql("SELECT asset_id FROM app_learning.generation_media_ref WHERE artifact_id=:id").param("id", proposal.artifact()).query(UUID.class).single())
                .isEqualTo(original);
        assertThat(slotState(proposal.artifact())).isEqualTo("READY");
        Proposal back = fresh(owner, deck, proposal);
        assertThat(revert(owner, deck, back, back.version(), UUID.fromString(detail.path("revision").path("revisionId").stringValue(null))).getStatus()).isEqualTo(200);
        assertThat(slot(owner, deck, proposal).path("assetId").stringValue(null)).isEqualTo(applied.toString());
    }

    @Test
    void aTurnThatFindsNothingFailsWithNoResultOrProviderUnavailableAndChangesNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        int debited = debits(owner);

        UUID none = searchTurn(owner, deck, proposal, "лиса [[stub:image-none]]");
        awaitTurn(none, "FAILED");
        assertThat(turnError(none)).isEqualTo("NO_RESULT");
        assertThat(reservationOfTurn(none)).isEqualTo("RELEASED");
        UUID down = searchTurn(owner, deck, proposal, "лиса [[stub:image-down]]");
        awaitTurn(down, "FAILED");
        assertThat(turnError(down)).isEqualTo("PROVIDER_UNAVAILABLE");
        stager.mode = GenerationTestConfiguration.FakeStager.Mode.REJECT;
        UUID rejected = searchTurn(owner, deck, proposal, "лиса зимой");
        awaitTurn(rejected, "FAILED");
        assertThat(turnError(rejected)).isEqualTo("NO_RESULT");
        stager.reset();
        stager.fail = true;
        UUID storage = searchTurn(owner, deck, proposal, "лиса летом");
        awaitTurn(storage, "FAILED");
        assertThat(turnError(storage)).isEqualTo("PROVIDER_UNAVAILABLE");

        awaitArtifact(proposal.artifact(), "PROPOSED");
        assertThat(revisions(proposal.artifact())).isEqualTo(1);
        assertThat(slotState(proposal.artifact())).isEqualTo("READY");
        assertThat(debits(owner)).isEqualTo(debited);
        assertThat(reservationOfTurn(rejected)).isEqualTo("RELEASED");
        // the rejected images stay known (as FAILED), so a later search does not bring them again
        JsonNode slot = slot(owner, deck, proposal);
        assertThat(slot.path("candidates").size()).isEqualTo(1 + ImageSearchExecutor.NEW_PER_TURN);
        assertThat(slot.path("candidates").get(1).path("state").stringValue(null)).isEqualTo("FAILED");
        assertThat(chosen(slot).path("candidateId").stringValue(null)).isEqualTo(slot.path("candidates").get(0).path("candidateId").stringValue(null));
    }

    @Test
    void aTurnWithSomeRejectedImagesAppliesTheFirstOneThatIsReady() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        stager.rejectNext.set(2);
        stager.delayPolls = 1;

        UUID turn = searchTurn(owner, deck, proposal, "красная лиса");
        awaitTurn(turn, "APPLIED");

        JsonNode slot = slot(owner, deck, proposal);
        List<String> states = new ArrayList<>();
        slot.path("candidates").forEach(candidate -> states.add(candidate.path("state").stringValue(null)));
        assertThat(states).containsExactly("READY", "FAILED", "FAILED", "READY", "READY");
        assertThat(chosen(slot).path("candidateId").stringValue(null)).isEqualTo(slot.path("candidates").get(3).path("candidateId").stringValue(null));
    }

    @Test
    void aSlotHasAtMostTwelveCandidatesAndTheTurnThatWouldExceedItFindsNothingNew() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");

        for (String query : new String[] {"первый", "второй", "третий"}) awaitTurn(searchTurn(owner, deck, proposal, query), "APPLIED");
        assertThat(candidateRows(proposal.artifact())).isEqualTo(1 + 4 + 4 + 3);
        UUID full = searchTurn(owner, deck, proposal, "четвёртый");
        awaitTurn(full, "FAILED");
        assertThat(turnError(full)).isEqualTo("NO_RESULT");
        assertThat(candidateRows(proposal.artifact())).isEqualTo(12);
    }

    @Test
    void anImageSearchEditNeedsExactlyOneImageBlockThatSearchesAndAShortQuery() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        Proposal now = fresh(owner, deck, proposal);
        List<JsonNode> blocks = blocks(detail(owner, deck, now));
        UUID picture = id(blocks.stream().filter(block -> block.path("type").stringValue("").equals("image")).findFirst().orElseThrow());
        UUID paragraph = id(blocks.get(1));

        problem(edit(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "IMAGE_SEARCH", null, null, paragraph)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "IMAGE_SEARCH", null, null, paragraph, picture)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "IMAGE_SEARCH", null, "я".repeat(201), picture)), 400, "INVALID_REQUEST");
        MockHttpServletResponse limit = edit(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "IMAGE_SEARCH", null, "я".repeat(200), picture));
        assertThat(limit.getStatus()).isEqualTo(202);
        awaitArtifact(proposal.artifact(), "PROPOSED");
        // a second edit while the first runs is the ordinary EDIT_IN_PROGRESS
        stager.delayPolls = 4;
        UUID slow = searchTurn(owner, deck, proposal, "лиса");
        problem(edit(owner, deck, fresh(owner, deck, proposal), editBody(UUID.randomUUID(), fresh(owner, deck, proposal).revision(), "IMAGE_SEARCH", null, null, picture)),
                409, "EDIT_IN_PROGRESS");
        awaitTurn(slow, "APPLIED");
    }

    // ----------------------------------------------------------------- selection

    @Test
    void anotherReadyCandidateCanBeChosenInANewRevisionAndTheCommandIsReplayedFromItsReceipt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        awaitTurn(searchTurn(owner, deck, proposal, "лиса зимой"), "APPLIED");
        Proposal now = fresh(owner, deck, proposal);
        JsonNode slot = slot(owner, deck, now);
        UUID target = UUID.fromString(slot.path("candidates").get(3).path("candidateId").stringValue(null));
        UUID targetAsset = UUID.fromString(slot.path("candidates").get(3).path("assetId").stringValue(null));
        UUID command = UUID.randomUUID();
        int revisionsBefore = revisions(proposal.artifact());

        MockHttpServletResponse response = select(owner, deck, now, command, now.revision(), target);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(response.getHeader("ETag")).isEqualTo("\"" + body.path("rowVersion").stringValue(null) + "\"");
        assertThat(body.path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(body.path("revision").path("cause").stringValue(null)).isEqualTo("MEDIA");
        assertThat(body.path("revisions")).hasSize(revisionsBefore + 1);
        assertThat(body.path("currentRevisionId").stringValue(null)).isEqualTo(body.path("revision").path("revisionId").stringValue(null));
        JsonNode chosenSlot = body.path("mediaSlots").get(0);
        assertThat(chosenSlot.path("assetId").stringValue(null)).isEqualTo(targetAsset.toString());
        assertThat(chosen(chosenSlot).path("candidateId").stringValue(null)).isEqualTo(target.toString());
        assertThat(chosenSlot.path("attribution").path("source").stringValue(null)).isEqualTo("STUB");
        assertThat(nodeAsset(owner, deck, now)).isEqualTo(targetAsset);
        // only the node's asset changed
        JsonNode earlier = detail(owner, deck, now, "?revisionId=" + now.revision());
        for (int index = 0; index < blocks(earlier).size(); index++) {
            if (!blocks(earlier).get(index).path("type").stringValue("").equals("image")) assertThat(blocks(body).get(index)).isEqualTo(blocks(earlier).get(index));
        }
        assertThat(jdbc.sql("SELECT asset_id FROM app_learning.generation_media_ref WHERE artifact_id=:id").param("id", proposal.artifact()).query(UUID.class).single())
                .isEqualTo(targetAsset);
        // it is a revision, not a turn
        assertThat(detail(owner, deck, now).path("turns")).hasSize(1);

        // the same command again: the stored answer, no second revision; another body under the same id conflicts
        MockHttpServletResponse replay = select(owner, deck, now, command, now.revision(), target);
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay).path("revision").path("revisionId").stringValue(null)).isEqualTo(body.path("revision").path("revisionId").stringValue(null));
        assertThat(revisions(proposal.artifact())).isEqualTo(revisionsBefore + 1);
        problem(select(owner, deck, now, command, now.revision(), UUID.fromString(slot.path("candidates").get(2).path("candidateId").stringValue(null))), 409,
                "IDEMPOTENCY_CONFLICT");

        // choosing the one that is already chosen is a no-op (no revision), a stale revision is 412
        Proposal current = fresh(owner, deck, proposal);
        MockHttpServletResponse noop = select(owner, deck, current, UUID.randomUUID(), current.revision(), target);
        assertThat(noop.getStatus()).isEqualTo(200);
        assertThat(revisions(proposal.artifact())).isEqualTo(revisionsBefore + 1);
        problem(select(owner, deck, current, UUID.randomUUID(), now.revision(), UUID.fromString(slot.path("candidates").get(1).path("candidateId").stringValue(null))),
                412, "VERSION_CONFLICT");
        // «Вернуть» is the ordinary revert
        assertThat(revert(owner, deck, current, current.version(), now.revision()).getStatus()).isEqualTo(200);
        assertThat(slot(owner, deck, proposal).path("assetId").stringValue(null)).isEqualTo(slot.path("assetId").stringValue(null));
    }

    @Test
    void aCandidateThatIsUnknownForeignRejectedOrOfANoSlotIsRefusedOpaquelyOrAsIllegal() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        stager.rejectNext.set(1);
        awaitTurn(searchTurn(owner, deck, proposal, "лиса зимой"), "APPLIED");
        Proposal now = fresh(owner, deck, proposal);
        JsonNode slot = slot(owner, deck, now);
        UUID rejected = UUID.fromString(slot.path("candidates").get(1).path("candidateId").stringValue(null));
        assertThat(slot.path("candidates").get(1).path("state").stringValue(null)).isEqualTo("FAILED");
        UUID stranger = UUID.randomUUID();
        UUID otherDeck = deck(stranger);
        Proposal theirs = proposal(stranger, otherDeck, imageSpec(IMAGE));
        awaitSlot(theirs.artifact(), "READY");
        UUID theirCandidate = UUID.fromString(slot(stranger, otherDeck, theirs).path("candidates").get(0).path("candidateId").stringValue(null));

        // a candidate that is not READY is a state conflict; an unknown one, another artifact's and another owner's are the same 404
        problem(select(owner, deck, now, UUID.randomUUID(), now.revision(), rejected), 409, "GENERATION_STATE_CONFLICT");
        problem(select(owner, deck, now, UUID.randomUUID(), now.revision(), UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        problem(select(owner, deck, now, UUID.randomUUID(), now.revision(), theirCandidate), 404, "RESOURCE_NOT_FOUND");
        problem(select(stranger, otherDeck, theirs, UUID.randomUUID(), fresh(stranger, otherDeck, theirs).revision(), UUID.fromString(
                slot.path("candidates").get(0).path("candidateId").stringValue(null))), 404, "RESOURCE_NOT_FOUND");
        problem(selectRaw(owner, deck, now, "zz", "{\"commandId\":\"" + UUID.randomUUID() + "\",\"expectedRevisionId\":\"" + now.revision() + "\",\"candidateId\":\""
                + rejected + "\"}"), 404, "RESOURCE_NOT_FOUND");
        // a malformed key, an unknown field and a bad id are 400
        problem(selectRaw(owner, deck, now, "BAD-KEY", "{}"), 400, "INVALID_REQUEST");
        problem(selectRaw(owner, deck, now, "i1", "{\"commandId\":\"" + UUID.randomUUID() + "\",\"expectedRevisionId\":\"" + now.revision() + "\",\"candidateId\":\"nope\"}"),
                400, "INVALID_REQUEST");
        problem(selectRaw(owner, deck, now, "i1", "{\"commandId\":\"" + UUID.randomUUID() + "\",\"expectedRevisionId\":\"" + now.revision() + "\",\"candidateId\":\""
                + rejected + "\",\"extra\":1}"), 400, "INVALID_REQUEST");
        assertThat(revisions(proposal.artifact())).isEqualTo(2);
    }

    @Test
    void aChoiceWhileATurnRunsIsEditInProgressAndAnArtifactOutsideReviewIsIllegal() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        awaitTurn(searchTurn(owner, deck, proposal, "лиса зимой"), "APPLIED");
        Proposal now = fresh(owner, deck, proposal);
        UUID other = UUID.fromString(slot(owner, deck, now).path("candidates").get(2).path("candidateId").stringValue(null));
        stager.delayPolls = 4;
        UUID running = searchTurn(owner, deck, now, "лиса весной");

        MockHttpServletResponse busy = select(owner, deck, now, UUID.randomUUID(), now.revision(), other);
        problem(busy, 409, "EDIT_IN_PROGRESS");
        assertThat(json(busy).path("turnId").stringValue(null)).isEqualTo(running.toString());
        awaitTurn(running, "APPLIED");

        Proposal after = fresh(owner, deck, proposal);
        assertThat(reject(owner, deck, after, UUID.randomUUID(), after.version()).getStatus()).isEqualTo(200);
        problem(select(owner, deck, fresh(owner, deck, proposal), UUID.randomUUID(), fresh(owner, deck, proposal).revision(), other), 409, "GENERATION_STATE_CONFLICT");
    }

    // ------------------------------------------------------------- holds and GC

    @Test
    void everyCandidateKeepsItsAssetReachableUntilTheSessionIsGone() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        awaitTurn(searchTurn(owner, deck, proposal, "лиса зимой"), "APPLIED");
        List<UUID> assets = jdbc.sql("SELECT asset_id FROM app_learning.generation_media_candidate WHERE artifact_id=:id").param("id", proposal.artifact())
                .query(UUID.class).list();
        assertThat(assets).hasSize(5);
        // the owner's own hold on all of them has elapsed: only the Workshop's holds are left (the node's, and every candidate's)
        jdbc.sql("UPDATE app_learning.media_asset SET owner_hold_until=CURRENT_TIMESTAMP - interval '1 day' WHERE asset_id IN (:assets)").param("assets", assets).update();

        media.expireUnattached(1_000);

        for (UUID asset : assets) {
            assertThat(jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:id").param("id", asset).query(String.class).single())
                    .as("asset " + asset).isEqualTo("READY");
            assertThat(media.resolve(owner, asset, null).mimeType()).isEqualTo("image/png");
        }
        assertThat(deleteSession(owner, deck, proposal.session()).getStatus()).isEqualTo(204);
        assertThat(candidateRows(proposal.artifact())).isZero();
        media.expireUnattached(1_000);
        for (UUID asset : assets) {
            assertThat(jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:id").param("id", asset).query(String.class).single())
                    .as("asset " + asset).isEqualTo("DELETED");
        }
    }

    @Test
    void aRevertToTheStateBeforeAnyImageWasFoundFailsTheSlotAgainAndForwardRestoresIt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE + " [[stub:image-none]]"));
        awaitSlot(proposal.artifact(), "FAILED");
        awaitTurn(searchTurn(owner, deck, proposal, "red fox"), "APPLIED");
        Proposal now = fresh(owner, deck, proposal);
        UUID second = now.revision();
        assertThat(slotState(proposal.artifact())).isEqualTo("READY");

        assertThat(revert(owner, deck, now, now.version(), proposal.revision()).getStatus()).isEqualTo(200);
        assertThat(slotState(proposal.artifact())).isEqualTo("FAILED");
        assertThat(slotError(proposal.artifact())).isEqualTo("NO_RESULT");
        assertThat(holds(proposal.artifact())).isZero();
        assertThat(json(getSession(owner, deck, proposal.session())).path("approvableCount").intValue()).isZero();

        Proposal back = fresh(owner, deck, proposal);
        assertThat(revert(owner, deck, back, back.version(), second).getStatus()).isEqualTo(200);
        assertThat(slotState(proposal.artifact())).isEqualTo("READY");
        assertThat(holds(proposal.artifact())).isEqualTo(1);
        assertThat(json(getSession(owner, deck, proposal.session())).path("approvableCount").intValue()).isEqualTo(1);
    }

    @Test
    void removingTheImageKeepsItsCandidatesHeldAndEndsTheNodeHold() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        Proposal now = fresh(owner, deck, proposal);
        UUID picture = id(blocks(detail(owner, deck, now)).stream().filter(block -> block.path("type").stringValue("").equals("image")).findFirst().orElseThrow());

        assertThat(edit(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "REMOVE_MEDIA", null, null, picture)).getStatus()).isEqualTo(202);

        assertThat(slotState(proposal.artifact())).isEqualTo("REMOVED");
        assertThat(holds(proposal.artifact())).isZero();
        assertThat(candidateRows(proposal.artifact())).isEqualTo(1);
        assertThat(detail(owner, deck, proposal).path("mediaSlots")).isEmpty();
    }

    @Test
    void aSearchStartedBeforeItsSlotsFirstStepRanReplacesThatStep() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        // the first search is slow to verify: the person asks for another image meanwhile
        stager.mode = GenerationTestConfiguration.FakeStager.Mode.STAY_VERIFYING;
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "VERIFYING");
        stager.reset();

        UUID turn = searchTurn(owner, deck, proposal, "другая лиса");
        awaitTurn(turn, "APPLIED");

        assertThat(slotState(proposal.artifact())).isEqualTo("READY");
        await("the first step to be cancelled", Duration.ofSeconds(10), () -> jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_step "
                + "WHERE artifact_id=:id AND kind='IMAGE_SEARCH' AND input->>'turnId' IS NULL AND state='CANCELLED'").param("id", proposal.artifact())
                .query(Integer.class).single() == 1);
        assertThat(reservationState(proposal.session())).isNotEqualTo("ACTIVE");
    }

    // ------------------------------------------------ a replaced first search and the slot's fate (M1)

    private int slotStepCount(UUID artifact, String state) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_step WHERE artifact_id=:id AND kind='IMAGE_SEARCH' "
                + "AND input->>'turnId' IS NULL AND state=:state").param("id", artifact).param("state", state).query(Integer.class).single();
    }

    @Test
    void aTurnThatFailsWhileTheReplacedFirstSearchIsStillWaitingLeavesNoSlotStuckOpen() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        // the first search is held inside its verification wait while the person asks for another image, and that turn fails at once
        CountDownLatch held = new CountDownLatch(1);
        stager.gate = held;
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "VERIFYING");
        stager.gate = null;

        UUID turn = searchTurn(owner, deck, proposal, "другая лиса [[stub:image-none]]");
        awaitTurn(turn, "FAILED");

        // the first step was asked to stop but is still RUNNING: it does not decide the slot, the failed turn does
        assertThat(slotStepCount(proposal.artifact(), "RUNNING")).isEqualTo(1);
        awaitSlot(proposal.artifact(), "FAILED");
        assertThat(slotError(proposal.artifact())).isEqualTo("NO_RESULT");
        held.countDown();
        await("the first step to be cancelled", Duration.ofSeconds(10), () -> slotStepCount(proposal.artifact(), "CANCELLED") == 1);

        // the old executor's cancellation leaves the slot as the turn ended it, and the hold ends with the last step
        assertThat(slotState(proposal.artifact())).isEqualTo("FAILED");
        assertThat(slotError(proposal.artifact())).isEqualTo("NO_RESULT");
        assertThat(slotEvents(owner, deck, proposal).stream().filter(event -> event.startsWith("FAILED")).count()).isEqualTo(1);
        assertThat(reservationState(proposal.session())).isNotEqualTo("ACTIVE");
        problem(approve(owner, deck, fresh(owner, deck, proposal), UUID.randomUUID()), 409, "GENERATION_STATE_CONFLICT");
        // and the way out works
        UUID again = searchTurn(owner, deck, proposal, "red fox");
        awaitTurn(again, "APPLIED");
        assertThat(slotState(proposal.artifact())).isEqualTo("READY");
    }

    @Test
    void theReplacedFirstSearchThatEndsFirstLeavesTheSlotToTheTurnThatIsStillWorking() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        CountDownLatch oldHeld = new CountDownLatch(1);
        CountDownLatch newHeld = new CountDownLatch(1);
        stager.gate = oldHeld;
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "VERIFYING");
        stager.gate = newHeld;
        UUID turn = searchTurn(owner, deck, proposal, "red fox");
        await("the turn to stage its candidates", Duration.ofSeconds(10), () -> stager.staged.size() == 1 + ImageSearchExecutor.NEW_PER_TURN);

        oldHeld.countDown();
        await("the first step to be cancelled", Duration.ofSeconds(10), () -> slotStepCount(proposal.artifact(), "CANCELLED") == 1);
        assertThat(slotState(proposal.artifact())).isEqualTo("VERIFYING");
        newHeld.countDown();
        awaitTurn(turn, "APPLIED");

        assertThat(slotState(proposal.artifact())).isEqualTo("READY");
        assertThat(slotEvents(owner, deck, proposal)).noneMatch(event -> event.startsWith("FAILED"));
    }

    @Test
    void aCancelledFirstSearchWhoseTurnEndedWithoutTheSlotFailsTheSlotItself() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        CountDownLatch oldHeld = new CountDownLatch(1);
        CountDownLatch newHeld = new CountDownLatch(1);
        stager.gate = oldHeld;
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "VERIFYING");
        stager.gate = newHeld;
        searchTurn(owner, deck, proposal, "red fox");
        await("the turn to stage its candidates", Duration.ofSeconds(10), () -> stager.staged.size() == 1 + ImageSearchExecutor.NEW_PER_TURN);
        // the turn's step ended without closing the slot (as a path that only cancels it would)
        jdbc.sql("UPDATE app_learning.generation_step SET state='CANCELLED',lease_token=NULL,lease_until=NULL WHERE artifact_id=:id AND input->>'turnId' IS NOT NULL")
                .param("id", proposal.artifact()).update();

        oldHeld.countDown();
        await("the first step to be cancelled", Duration.ofSeconds(10), () -> slotStepCount(proposal.artifact(), "CANCELLED") == 1);

        assertThat(slotState(proposal.artifact())).isEqualTo("FAILED");
        assertThat(slotError(proposal.artifact())).isEqualTo("CANCELLED");
        newHeld.countDown();
    }

    // ------------------------------------------- a retry resumes what the lost attempt recorded (m2, m5)

    @Test
    void aFirstSearchWhoseWorkerDiedBeforeTheTransferResumesTheSameCandidateOnItsOwnAsset() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        stager.crashNextStage.set(true);

        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");

        JsonNode slot = slot(owner, deck, proposal);
        // one candidate: the one the first attempt recorded before it died, now staged under the slot's own asset with its own attribution
        assertThat(slot.path("candidates")).hasSize(1);
        JsonNode candidate = slot.path("candidates").get(0);
        assertThat(candidate.path("assetId").stringValue(null)).isEqualTo(slot.path("assetId").stringValue(null));
        assertThat(candidate.path("title").stringValue(null)).isEqualTo("Stub image 1");
        assertThat(candidate.path("chosen").booleanValue()).isTrue();
        assertThat(stager.staged).containsExactly(UUID.fromString(slot.path("assetId").stringValue(null)));
        assertThat(jdbc.sql("SELECT max(attempts) FROM app_learning.generation_step WHERE artifact_id=:id AND kind='IMAGE_SEARCH'")
                .param("id", proposal.artifact()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void aTurnRetryAppliesTheReadyCandidatesTheLostAttemptStoredWithoutSearchingAgain() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, imageSpec(IMAGE));
        awaitSlot(proposal.artifact(), "READY");
        assertThat(stager.staged).hasSize(1);
        // the turn stages four candidates, then its worker dies while it waits for their verification
        stager.crashNextPoll.set(true);

        UUID turn = searchTurn(owner, deck, proposal, "red fox");
        awaitTurn(turn, "APPLIED");

        assertThat(stager.staged).hasSize(1 + ImageSearchExecutor.NEW_PER_TURN);
        assertThat(candidateRows(proposal.artifact())).isEqualTo(1 + ImageSearchExecutor.NEW_PER_TURN);
        JsonNode slot = slot(owner, deck, proposal);
        assertThat(chosen(slot)).isNotNull();
        assertThat(chosen(slot).path("title").stringValue(null)).isEqualTo("Stub image 1");
        assertThat(slot.path("candidates")).hasSize(1 + ImageSearchExecutor.NEW_PER_TURN);
        assertThat(jdbc.sql("SELECT attempts FROM app_learning.generation_step WHERE artifact_id=:id AND input->>'turnId' IS NOT NULL")
                .param("id", proposal.artifact()).query(Integer.class).single()).isEqualTo(2);
        assertThat(revisions(proposal.artifact())).isEqualTo(2);
    }
}
