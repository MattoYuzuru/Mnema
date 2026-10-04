package app.mnema.learning.generation;

import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.study.retention.StudyRetentionService;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Acceptance of #291 (AI-13) on the Stub, PostgreSQL and the real catalog: an {@code EXERCISES} session proposes validated
 * exercises (at least three mechanics, repaired once, failed and never shown when the key stays broken), the objective is reused
 * across a batch and across sessions, approval (single, bulk, mixed with a material, edited) is one transaction with the
 * catalog, a moved material is followed or reported, and the «Новое» mark lives and dies by its rules.
 */
class GenerationExercisesIntegrationTest extends GenerationReviewSupport {
    private static final String FIRST = "Планировщик выбирает план выполнения";
    private static final String SECOND = "Статистика таблицы обновляется командой ANALYZE";

    @Autowired private app.mnema.learning.catalog.exercise.ExerciseController exerciseController;
    @Autowired private AttemptService attempts;
    @Autowired private StudyRetentionService retention;

    // ------------------------------------------------------------------------ helpers

    private StudyFixtures.Material material(UUID owner, UUID deck, String marker) {
        return fixtures.addMaterial(owner, deck, FIRST + " " + marker, SECOND);
    }

    private ObjectNode exercisesSpec(String quantity, String priority, StudyFixtures.Material... targets) throws Exception {
        ObjectNode spec = JSON.createObjectNode().put("kind", "EXERCISES").put("outputLanguage", "ru");
        ArrayNode list = spec.putArray("targets");
        for (StudyFixtures.Material target : targets) {
            list.addObject().put("memberKey", target.member().toString()).put("itemRevisionId", target.itemRevision().toString());
        }
        ObjectNode settings = spec.putObject("settings");
        settings.put("mechanics", "AUTO").put("priority", priority);
        settings.set("quantity", JSON.readTree(quantity));
        return spec;
    }

    private ObjectNode exercisesSpec(String quantity, StudyFixtures.Material... targets) throws Exception {
        return exercisesSpec(quantity, "UNCOVERED_FIRST", targets);
    }

    private static final String EXACT_3 = "{\"mode\":\"EXACT\",\"perTarget\":3}";
    private static final String EXACT_2 = "{\"mode\":\"EXACT\",\"perTarget\":2}";
    private static final String EXACT_1 = "{\"mode\":\"EXACT\",\"perTarget\":1}";

    private JsonNode artifact(UUID owner, UUID deck, UUID session, UUID artifact) throws Exception {
        MockHttpServletResponse response = send(owner, get(base(deck, session) + "/artifacts/" + artifact));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json(response);
    }

    private JsonNode command(UUID owner, UUID deck, Proposal proposal) throws Exception {
        return artifact(owner, deck, proposal.session(), proposal.artifact()).path("revision").path("payload").path("command");
    }

    /** The catalog's own HTTP surface (the base class serves the generation controller only). */
    private MockHttpServletResponse sendCatalog(UUID owner, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mvc(owner);
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(exerciseController)
                .setControllerAdvice(new app.mnema.learning.platform.api.ApiExceptionHandler())
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .build().perform(request).andReturn().getResponse();
    }

    private List<String> states(UUID session) {
        return artifactStates(session);
    }

    private String ifMatch(UUID deck) {
        return "\"" + deckVersion(deck) + "\"";
    }

    private UUID started(UUID owner, UUID deck, ObjectNode spec) throws Exception {
        UUID session = start(owner, deck, spec);
        awaitState(session, "REVIEW");
        return session;
    }

    private int objectivesOf(StudyFixtures.Material material) {
        return jdbc.sql("SELECT count(*) FROM app_learning.memory_objective WHERE deck_id=:deck AND member_key=:member")
                .param("deck", material.deck()).param("member", material.member()).query(Integer.class).single();
    }

    private List<GenerationTestConfiguration.Call> exerciseCalls(String marker) {
        return provider.callsOf(marker).stream().filter(call -> call.prompt().contains("<task kind=\"exercises\">")).toList();
    }

    /** A new revision of the material with the same node ids: the text of the second paragraph changes. */
    private UUID reviseText(StudyFixtures.Material material) {
        return revise(material, document -> ((ObjectNode) document.path("root").path("content").get(1).path("content").get(0).path("attrs"))
                .put("text", SECOND + " (уточнено)"), null);
    }

    /** A new revision of the material with a paragraph appended: every node that was there is as it was. */
    private UUID reviseAppendingParagraph(StudyFixtures.Material material) {
        UUID paragraph = UUID.randomUUID();
        JsonNode head = items.read(material.actor(), material.deck(), material.member(), null);
        JsonNode deck = decks.read(material.actor(), material.deck());
        ObjectNode document = (ObjectNode) head.path("document").deepCopy();
        ArrayNode content = (ArrayNode) document.path("root").path("content");
        int index = content.size();
        ObjectNode node = content.addObject().put("id", paragraph.toString()).put("type", "paragraph").put("version", 1);
        node.putObject("attrs");
        ObjectNode text = node.putArray("content").addObject().put("id", UUID.randomUUID().toString()).put("type", "text").put("version", 1);
        text.putObject("attrs").put("text", "Добавленный абзац");
        text.putArray("content");
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", head.path("itemRevisionId").stringValue(null)).put("expectedOrdinal", 0);
        body.set("document", document);
        body.putArray("edits").addObject().put("type", "insert").put("nodeId", paragraph.toString())
                .put("parentId", material.root().toString()).put("childIndex", index);
        JsonNode ack = items.publish(material.actor(), material.deck(), Long.parseLong(deck.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readSave(new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)), material.member()))
                .acknowledgement();
        return UUID.fromString(ack.path("changes").get(0).path("itemRevisionId").stringValue(null));
    }

    /** A new revision of the material without its second paragraph (a quoted node vanishes). */
    private UUID reviseWithoutSecondParagraph(StudyFixtures.Material material) {
        return revise(material, document -> {
            ArrayNode content = (ArrayNode) document.path("root").path("content");
            content.remove(1);
        }, material.distractor());
    }

    private UUID revise(StudyFixtures.Material material, java.util.function.Consumer<JsonNode> change, UUID deletedNode) {
        JsonNode head = items.read(material.actor(), material.deck(), material.member(), null);
        JsonNode deck = decks.read(material.actor(), material.deck());
        ObjectNode document = (ObjectNode) head.path("document").deepCopy();
        change.accept(document);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", head.path("itemRevisionId").stringValue(null)).put("expectedOrdinal", 0);
        body.set("document", document);
        if (deletedNode != null) body.putArray("edits").addObject().put("type", "delete").put("nodeId", deletedNode.toString());
        JsonNode ack = items.publish(material.actor(), material.deck(), Long.parseLong(deck.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readSave(new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)), material.member()))
                .acknowledgement();
        return UUID.fromString(ack.path("changes").get(0).path("itemRevisionId").stringValue(null));
    }

    private List<String> mechanics(UUID owner, UUID deck, List<Proposal> proposals) throws Exception {
        List<String> mechanics = new ArrayList<>();
        for (Proposal proposal : proposals) {
            mechanics.add(artifact(owner, deck, proposal.session(), proposal.artifact()).path("display").path("mechanic").stringValue(null));
        }
        return mechanics;
    }

    // ------------------------------------------------------------------------- generation

    @Test
    void aMaterialYieldsAtLeastThreeMechanicsEachValidatedBeforeItIsShown() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:three]]");
        UUID session = started(owner, deck, exercisesSpec("{\"mode\":\"AUTO\"}", material));

        List<Proposal> proposals = proposals(owner, deck, session);
        assertThat(proposals).hasSize(5);
        assertThat(states(session)).containsOnly("PROPOSED");
        Set<String> mechanics = new LinkedHashSet<>(mechanics(owner, deck, proposals));
        assertThat(mechanics).hasSizeGreaterThanOrEqualTo(3);

        for (Proposal proposal : proposals) {
            JsonNode detail = artifact(owner, deck, session, proposal.artifact());
            assertThat(detail.path("targetKind").stringValue(null)).isEqualTo("EXERCISE");
            assertThat(detail.path("sourceRefs").get(0).path("memberKey").stringValue(null)).isEqualTo(material.member().toString());
            assertThat(detail.path("sourceRefs").get(0).path("itemRevisionId").stringValue(null)).isEqualTo(material.itemRevision().toString());
            JsonNode payload = detail.path("revision").path("payload");
            assertThat(payload.path("kind").stringValue(null)).isEqualTo("EXERCISE_COMMAND");
            // the stored command is the one the publication endpoint parses (the envelope fields are supplied at approval)
            ObjectNode envelope = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                    .put("expectedDeckRevisionId", deckRevision(deck).toString());
            envelope.set("objective", payload.path("command").path("objective").deepCopy());
            envelope.set("exercise", payload.path("command").path("exercise").deepCopy());
            ExerciseCommand parsed = ExerciseCommand.readCreate(new ByteArrayInputStream(envelope.toString().getBytes(StandardCharsets.UTF_8)));
            assertThat(parsed.exercise().subject().memberKey()).isEqualTo(material.member());
            assertThat(payload.path("command").path("exercise").has("bindings")).isFalse();
            assertThat(payload.toString()).doesNotContain("whyWrong");
            assertThat(detail.path("display").path("mechanic").stringValue(null)).isEqualTo(parsed.exercise().type().name());
            assertThat(detail.path("display").path("objectiveTitle").stringValue(null)).isEqualTo("Основная мысль материала");
            assertThat(detail.path("title").stringValue("")).isNotBlank();
            assertThat(detail.path("title").stringValue("").codePointCount(0, detail.path("title").stringValue("").length())).isLessThanOrEqualTo(240);
            // every quoted node reads as text in the pinned revision
            JsonNode quotes = detail.path("display").path("quotes");
            JSON.readTree(payload.toString()).findValues("nodeId").forEach(node -> assertThat(quotes.has(node.stringValue(null))).isTrue());
        }
        // the model's request had no transaction and no connection open, and the batch took one call
        List<GenerationTestConfiguration.Call> calls = exerciseCalls("[[t:three]]");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().transactionAtCall()).isFalse();
        assertThat(calls.getFirst().connectionsAtCall()).isZero();
        assertThat(calls.getFirst().prompt()).contains("<data_policy>").contains("<material id=\"m1\">").contains("[[b1]]").contains("Механики:");
        // five exercises are charged as five exercises (eight credits), the hold is released, the owner is told
        assertThat(debits(owner)).isEqualTo(8);
        assertThat(notificationKinds(owner)).contains("GENERATION_READY");
        JsonNode ready = notification(owner, "GENERATION_READY");
        assertThat(ready.path("sessionKind").stringValue(null)).isEqualTo("EXERCISES");
        assertThat(ready.path("sessionId").stringValue(null)).isEqualTo(session.toString());
        assertThat(ready.path("artifactCount").intValue()).isEqualTo(5);
        assertThat(ready.path("approvableCount").intValue()).isEqualTo(5);
        JsonNode events = json(events(owner, deck, session, "?after=0&limit=100")).path("events");
        events.forEach(event -> assertThat(event.path("type").stringValue(null)).isNotEqualTo("BLOCKS_APPENDED"));
        assertThat(json(getSession(owner, deck, session)).path("kind").stringValue(null)).isEqualTo("EXERCISES");
    }

    @Test
    void anExerciseProposalAndItsApprovalHaveTheMembersOfTheExamplesOfTheContract() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:shape]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_1, material));
        Proposal proposal = proposals(owner, deck, session).getFirst();

        JsonNode examples = JSON.readTree(java.nio.file.Files.readString(repositoryRoot().resolve("contracts/generation/http.json"))).path("examples");
        // the quotes are a map by node id: its keys are data, the rest of the detail has the members of the example
        ObjectNode expectedDetail = (ObjectNode) examples.path("artifactDetailExercise").deepCopy();
        ((ObjectNode) expectedDetail.path("display")).remove("quotes");
        ObjectNode actualDetail = (ObjectNode) artifact(owner, deck, session, proposal.artifact()).deepCopy();
        assertThat(actualDetail.path("display").path("quotes").isObject()).isTrue();
        ((ObjectNode) actualDetail.path("display")).remove("quotes");
        sameShape(expectedDetail, actualDetail, "getArtifact");

        MockHttpServletResponse approved = approve(owner, deck, proposal, UUID.randomUUID());
        assertThat(approved.getStatus()).isEqualTo(200);
        sameShape(examples.path("approvalAckExercise"), json(approved), "approveArtifact");
    }

    private static java.nio.file.Path repositoryRoot() {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("contracts/generation/http.json"))) root = root.getParent();
        return root;
    }

    /** The same members at every object, the same kind of value at every scalar; a null on either side is a nullable member. */
    private static void sameShape(JsonNode expected, JsonNode actual, String path) {
        if (expected.isNull() || actual.isNull()) return;
        if (expected.isObject()) {
            assertThat(actual.isObject()).as(path + " is an object").isTrue();
            assertThat(new java.util.TreeSet<>(expected.propertyNames())).as(path + " members").isEqualTo(new java.util.TreeSet<>(actual.propertyNames()));
            expected.propertyNames().forEach(name -> sameShape(expected.path(name), actual.path(name), path + "/" + name));
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as(path + " is an array").isTrue();
            if (!expected.isEmpty() && !actual.isEmpty()) sameShape(expected.get(0), actual.get(0), path + "[0]");
        } else {
            assertThat(actual.getNodeType()).as(path + " kind").isEqualTo(expected.getNodeType());
        }
    }

    @Test
    void aBrokenKeyIsRepairedOnceAndTheBatchIsStillShown() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:repair]] [[stub:broken-key]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_3, material));

        assertThat(states(session)).containsOnly("PROPOSED");
        List<GenerationTestConfiguration.Call> calls = exerciseCalls("[[t:repair]]");
        assertThat(calls).extracting(GenerationTestConfiguration.Call::repair).containsExactly(false, true);
        // only the failed exercise is asked for again, and the message names the rule, not the content
        assertThat(calls.get(1).prompt()).contains("ровно из 1 упражнений").contains("упражнение 1: CHOICE_CORRECT_COUNT (options)");
        assertThat(calls.get(1).prompt()).doesNotContain("Противоположное");
        assertThat(mechanics(owner, deck, proposals(owner, deck, session))).doesNotContain((String) null);
        // the repaired exercise is paid like the others: three exercises, five credits
        assertThat(debits(owner)).isEqualTo(5);
    }

    @Test
    void aKeyThatStaysBrokenFailsItsArtifactAfterTheStrongRouteAndNothingIsShownOrApprovable() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:always]] [[stub:broken-key-always]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_3, material));

        // the exercises that passed fill the artifacts in order, so the one that never passed is the last
        assertThat(states(session)).containsExactly("PROPOSED", "PROPOSED", "FAILED");
        assertThat(artifactErrors(session)).containsExactly("-", "-", "INVALID_OUTPUT");
        List<GenerationTestConfiguration.Call> calls = exerciseCalls("[[t:always]]");
        assertThat(calls).extracting(GenerationTestConfiguration.Call::route).containsExactly(app.mnema.learning.ai.AiRoute.TEXT_FAST,
                app.mnema.learning.ai.AiRoute.TEXT_FAST, app.mnema.learning.ai.AiRoute.TEXT_STRONG);
        assertThat(calls.stream().map(GenerationTestConfiguration.Call::repair)).containsExactly(false, true, true);
        List<Proposal> proposals = proposals(owner, deck, session);
        JsonNode failed = artifact(owner, deck, session, proposals.get(2).artifact());
        assertThat(failed.path("revision").isNull()).isTrue();
        assertThat(failed.has("display")).isFalse();
        // it cannot be approved (no revision, no PROPOSED state), and it is the only thing that failed
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedArtifactVersion", Long.toString(proposals.get(2).version()))
                .put("expectedRevisionId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", deckRevision(deck).toString());
        MockHttpServletResponse refused = approve(owner, deck, proposals.get(2), body, ifMatch(deck));
        assertThat(refused.getStatus()).isIn(409, 412);
        assertThat(materialsAndExercises(owner, deck, material)).isZero();
        // two valid exercises of three are paid: ceil(5 * 2 / 3) of the step's five credits
        assertThat(debits(owner)).isEqualTo(4);
        assertThat(notificationKinds(owner)).contains("GENERATION_PARTIAL");
        JsonNode partial = notification(owner, "GENERATION_PARTIAL");
        assertThat(partial.path("sessionKind").stringValue(null)).isEqualTo("EXERCISES");
        assertThat(partial.path("approvableCount").intValue()).isEqualTo(2);
        assertThat(partial.path("failedCount").intValue()).isEqualTo(1);
    }

    private JsonNode notification(UUID owner, String kind) throws Exception {
        return JSON.readTree(jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id=:owner AND kind=:kind")
                .param("owner", owner).param("kind", kind).query(String.class).single());
    }

    private int materialsAndExercises(UUID owner, UUID deck, StudyFixtures.Material material) {
        return exercises.list(owner, deck, material.member(), null, null).path("total").intValue();
    }

    @Test
    void anAnswerThatIsNotJsonFailsEveryArtifactAfterTheRepairsAndTheStrongRoute() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:prose]] [[fake:not-json]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_2, material));

        assertThat(states(session)).containsOnly("FAILED");
        assertThat(artifactErrors(session)).containsOnly("INVALID_OUTPUT");
        List<GenerationTestConfiguration.Call> calls = exerciseCalls("[[t:prose]]");
        assertThat(calls).hasSize(3);
        assertThat(calls.get(1).prompt()).contains("ответ: SCHEMA_INVALID ($)").contains("ровно из 2 упражнений");
        assertThat(debits(owner)).isZero();
        assertThat(notificationKinds(owner)).contains("GENERATION_FAILED");
        assertThat(notification(owner, "GENERATION_FAILED").path("errorCode").stringValue(null)).isEqualTo("INVALID_OUTPUT");
        // a failed batch can be written again: the session stays in review with retryable artifacts
        assertThat(sessionState(session)).isEqualTo("REVIEW");
    }

    @Test
    void aCodeFenceAroundTheAnswerIsToleratedAndATruncatedAnswerIsRepaired() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material fenced = material(owner, deck, "[[t:fence]] [[fake:fenced]]");
        assertThat(states(started(owner, deck, exercisesSpec(EXACT_2, fenced)))).containsOnly("PROPOSED");
        assertThat(exerciseCalls("[[t:fence]]")).hasSize(1);

        StudyFixtures.Material cut = material(owner, deck, "[[t:cut]] [[fake:length-once]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_2, cut));
        assertThat(states(session)).containsOnly("PROPOSED");
        List<GenerationTestConfiguration.Call> calls = exerciseCalls("[[t:cut]]");
        assertThat(calls).extracting(GenerationTestConfiguration.Call::repair).containsExactly(false, true);
    }

    @Test
    void aWorkerThatDiesMidStepIsRecoveredAndTheTargetIsWrittenOnceAndCancellingAbortsTheCall() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material crashing = material(owner, deck, "[[t:crash]] [[fake:crash-once]]");
        UUID recovered = started(owner, deck, exercisesSpec(EXACT_2, crashing));
        assertThat(states(recovered)).containsOnly("PROPOSED");
        assertThat(steps.step(stepOf(recovered)).orElseThrow().attempts()).isEqualTo(2);
        assertThat(debits(owner)).isEqualTo(4);

        StudyFixtures.Material slow = material(owner, deck, "[[t:slow]] [[fake:block]]");
        UUID session = start(owner, deck, exercisesSpec(EXACT_2, slow));
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(states(session)).containsOnly("GENERATING");
        assertThat(cancel(owner, deck, session, UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(states(session)).containsOnly("FAILED");
        assertThat(artifactErrors(session)).containsOnly("CANCELLED");
        provider.release.countDown();
        await("the aborted step to end", java.time.Duration.ofSeconds(10), () -> steps.step(stepOf(session)).orElseThrow().state().equals("CANCELLED"));
        assertThat(states(session)).containsOnly("FAILED");
        assertThat(debits(owner)).isEqualTo(4);
    }

    @Test
    void entitiesAndQuotesInTheMaterialSurviveTheRoundTripThroughThePrompt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, "Планировщик \"выбирает\" план & <стоимость> [[t:escape]]",
                "Статистика \"таблицы\" & <индексы> обновляется командой ANALYZE");
        UUID session = started(owner, deck, exercisesSpec(EXACT_3, material));
        assertThat(states(session)).containsOnly("PROPOSED");
        // the model saw the escaped text and copied it; what is stored is the material as it is
        assertThat(exerciseCalls("[[t:escape]]").getFirst().prompt()).contains("&quot;выбирает&quot;").contains("&amp;").contains("&lt;стоимость&gt;");
        List<Proposal> proposals = proposals(owner, deck, session);
        String stored = JSON.writeValueAsString(proposals.stream().map(proposal -> {
            try {
                return command(owner, deck, proposal);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).toList());
        assertThat(stored).doesNotContain("&quot;").doesNotContain("&amp;").doesNotContain("&lt;").doesNotContain("&gt;");
        assertThat(mechanics(owner, deck, proposals)).contains("CLOZE", "FREE_RESPONSE");
        JsonNode cloze = command(owner, deck, proposals.get(2)).path("exercise").path("content").path("passage");
        assertThat(cloze.toString()).contains("\\\"выбирает\\\"").contains("& <стоимость>");
    }

    @Test
    void aLaterFailureKeepsTheExercisesAlreadyAcceptedAndFailsOnlyTheRest() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:partial]] [[stub:broken-key]] [[fake:fail-on-repair]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_3, material));

        // the first answer had two good exercises and one broken; the repair call failed: the two are kept and paid, the third fails alone
        assertThat(states(session)).containsExactly("PROPOSED", "PROPOSED", "FAILED");
        assertThat(artifactErrors(session)).containsExactly("-", "-", "PROVIDER_UNAVAILABLE");
        assertThat(exerciseCalls("[[t:partial]]")).extracting(GenerationTestConfiguration.Call::repair).containsExactly(false, true);
        assertThat(debits(owner)).isEqualTo(4);
        assertThat(notificationKinds(owner)).contains("GENERATION_PARTIAL");
    }

    @Test
    void anExerciseThatAsksWhatAnotherOneAlreadyAsksIsDroppedAndTheRepairNamesWhatIsKept() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:dup]] [[fake:no-variants]]");
        ObjectNode spec = exercisesSpec(EXACT_2, material);
        ((ObjectNode) spec.path("settings")).putArray("mechanics").add("CHOICE");
        UUID session = started(owner, deck, spec);

        // both questions came back as "variant 1": the second repeats the first, so it is asked for again
        assertThat(states(session)).containsOnly("PROPOSED");
        List<GenerationTestConfiguration.Call> calls = exerciseCalls("[[t:dup]]");
        assertThat(calls).extracting(GenerationTestConfiguration.Call::repair).containsExactly(false, true);
        assertThat(calls.get(1).prompt()).contains("ровно из 1 упражнений").contains("Уже приняты, не повторяй их:")
                .contains("упражнение 2: DUPLICATE_EXERCISE").contains("Какое утверждение соответствует материалу (вариант 1)?");
        Proposal first = proposals(owner, deck, session).getFirst();
        assertThat(approve(owner, deck, first, UUID.randomUUID()).getStatus()).isEqualTo(200);

        // and a later session that repeats a current exercise of the material is told so the same way
        UUID later = started(owner, deck, spec(EXACT_1, current(owner, deck, material), "CHOICE"));
        assertThat(states(later)).containsOnly("PROPOSED");
        List<GenerationTestConfiguration.Call> laterCalls = exerciseCalls("[[t:dup]]").stream().filter(call -> call.stepId().equals(stepOf(later))).toList();
        assertThat(laterCalls).extracting(GenerationTestConfiguration.Call::repair).containsExactly(false, true);
        assertThat(laterCalls.get(0).prompt()).contains("CHOICE · Какое утверждение соответствует материалу (вариант 1)?");
    }

    private ObjectNode spec(String quantity, StudyFixtures.Material material, String mechanic) throws Exception {
        ObjectNode spec = exercisesSpec(quantity, material);
        ((ObjectNode) spec.path("settings")).putArray("mechanics").add(mechanic);
        return spec;
    }

    @Test
    void twoApprovalsRacingForTheSameObjectiveLeaveOneObjective() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:race-objective]]");
        UUID firstSession = started(owner, deck, exercisesSpec(EXACT_1, material));
        UUID secondSession = started(owner, deck, exercisesSpec(EXACT_1, current(owner, deck, material)));
        Proposal first = proposals(owner, deck, firstSession).getFirst();
        Proposal second = proposals(owner, deck, secondSession).getFirst();
        UUID revision = deckRevision(deck);
        String match = ifMatch(deck);
        ObjectNode firstBody = approvalBody(UUID.randomUUID(), first, revision);
        ObjectNode secondBody = approvalBody(UUID.randomUUID(), second, revision);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> approve(owner, deck, first, firstBody, match).getStatus());
            var two = pool.submit(() -> approve(owner, deck, second, secondBody, match).getStatus());
            List<Integer> statuses = List.of(one.get(), two.get());
            // the deck version is a compare-and-set: one approval wins, the other is a stale precondition (412), never a second objective
            assertThat(statuses).containsExactlyInAnyOrder(200, 412);
        } finally {
            pool.shutdownNow();
        }
        Proposal loser = artifactState(first.artifact()).equals("PUBLISHED") ? fresh(owner, deck, second) : fresh(owner, deck, first);
        assertThat(approve(owner, deck, loser, UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(objectivesOf(material)).isEqualTo(1);
        assertThat(materialsAndExercises(owner, deck, material)).isEqualTo(2);
    }

    // -------------------------------------------------------------------------- admission

    @Test
    void anExercisesSessionOrdersTargetsByCoverageAndSharesTheQuantityInThatOrder() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material covered = material(owner, deck, "[[t:cov]]");
        StudyFixtures.Material bare = material(owner, deck, "[[t:bare]]");
        fixtures.publish(covered, fixtures.selfCheck(covered, StudyFixtures.blocks(StudyFixtures.text("Вспомните")),
                StudyFixtures.blocks(StudyFixtures.quote(covered, covered.node()))));

        UUID uncoveredFirst = started(owner, deck, exercisesSpec(EXACT_2, "UNCOVERED_FIRST", covered, bare));
        assertThat(sourcesOf(uncoveredFirst)).containsExactly(bare.member(), bare.member(), covered.member(), covered.member());
        UUID balanced = started(owner, deck, exercisesSpec(EXACT_2, "BALANCED", covered, bare));
        assertThat(sourcesOf(balanced)).containsExactly(covered.member(), covered.member(), bare.member(), bare.member());
        // one step per target, each filling its own artifacts
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_step WHERE session_id=:id AND kind='TEXT_DRAFT'")
                .param("id", uncoveredFirst).query(Integer.class).single()).isEqualTo(2);
        // the targets are pinned as SOURCE items in the order of the request
        assertThat(jdbc.sql("SELECT member_key FROM app_learning.generation_session_source WHERE session_id=:id ORDER BY ordinal")
                .param("id", uncoveredFirst).query(UUID.class).list()).containsExactly(covered.member(), bare.member());
    }

    private List<UUID> sourcesOf(UUID session) {
        return jdbc.sql("SELECT (source_refs->0->>'memberKey')::uuid FROM app_learning.generation_artifact WHERE session_id=:id ORDER BY ordinal")
                .param("id", session).query(UUID.class).list();
    }

    @Test
    void theSharesOfTheStepsAddUpToTheReservationAndTheQuantityIsSpreadFromTheFirstTarget() {
        assertThat(ExercisesSpec.spread(7, 3)).containsExactly(3, 2, 2);
        assertThat(ExercisesSpec.spread(8, 2)).containsExactly(4, 4);
        assertThat(ExercisesSpec.spread(2, 2)).containsExactly(1, 1);
        assertThat(ExercisesSpec.spread(60, 20)).containsOnly(3);
        assertThat(ExercisesSpec.spread(5, 1)).containsExactly(5);
    }

    @Test
    void aTargetThatIsNotTheHeadAnymoreAndALimitAndAPlannedSpecAreRefusedBeforeAnythingIsCreated() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:head]]");
        reviseText(material);
        MockHttpServletResponse moved = create(owner, deck, exercisesSpec(EXACT_1, material), UUID.randomUUID());
        problem(moved, 409, "SOURCE_UNAVAILABLE");

        StudyFixtures.Material current = new StudyFixtures.Material(owner, deck, material.member(),
                UUID.fromString(items.read(owner, deck, material.member(), null).path("itemRevisionId").stringValue(null)),
                material.node(), material.distractor(), material.divider(), material.root());
        // a plan-first exercises spec is the planner's (AI-14, GenerationPlanIntegrationTest)
        ObjectNode planned = exercisesSpec(EXACT_1, current);
        ((ObjectNode) planned.path("settings")).put("planFirst", true);
        MockHttpServletResponse accepted = create(owner, deck, planned, UUID.randomUUID());
        assertThat(accepted.getStatus()).isEqualTo(201);
        assertThat(json(accepted).path("state").stringValue(null)).isEqualTo("PLANNING");
        deleteSession(owner, deck, UUID.fromString(json(accepted).path("sessionId").stringValue(null)));

        ObjectNode tooMany = exercisesSpec(EXACT_1, current);
        ArrayNode targets = (ArrayNode) tooMany.path("targets");
        for (int index = 0; index < 20; index++) {
            targets.addObject().put("memberKey", UUID.randomUUID().toString()).put("itemRevisionId", UUID.randomUUID().toString());
        }
        MockHttpServletResponse limit = create(owner, deck, tooMany, UUID.randomUUID());
        problem(limit, 422, "RESOURCE_LIMIT_EXCEEDED");
        assertThat(json(limit).path("limit").stringValue(null)).isEqualTo("EXERCISE_TARGETS");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_session WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single()).isZero();
    }

    // ----------------------------------------------------------------------------- objectives

    @Test
    void approvingThreeExercisesOfOneDirectionCreatesOneObjectiveAndALaterSessionReusesIt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:objective]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_3, material));
        List<Proposal> three = proposals(owner, deck, session);
        // every proposal says it creates the objective: nothing existed when they were written
        for (Proposal proposal : three) {
            assertThat(command(owner, deck, proposal).path("objective").path("operation").stringValue(null)).isEqualTo("create");
        }

        MockHttpServletResponse approved = approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), deckRevision(deck), three), ifMatch(deck));
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        assertThat(objectivesOf(material)).isEqualTo(1);
        JsonNode ack = json(approved);
        Set<String> objectives = new LinkedHashSet<>();
        ack.path("artifacts").forEach(entry -> {
            assertThat(entry.path("publishedRef").path("kind").stringValue(null)).isEqualTo("EXERCISE");
            objectives.add(entry.path("publishedRef").path("objectiveId").stringValue(null));
        });
        assertThat(objectives).hasSize(1);
        assertThat(materialsAndExercises(owner, deck, material)).isEqualTo(3);
        assertThat(sessionState(session)).isEqualTo("CLOSED");
        // one chain of deck revisions: three publications moved the deck three versions, and the ETag is the last
        assertThat(approved.getHeader("ETag")).isEqualTo("\"" + deckVersion(deck) + "\"");
        assertThat(ack.path("deckRevisionId").stringValue(null)).isEqualTo(deckRevision(deck).toString());

        // a repeated generation offers the objective (t1) and the proposal reuses it; approving it adds no objective
        UUID second = started(owner, deck, exercisesSpec(EXACT_1, current(owner, deck, material)));
        Proposal proposal = proposals(owner, deck, second).getFirst();
        JsonNode objective = command(owner, deck, proposal).path("objective");
        assertThat(objective.path("operation").stringValue(null)).isEqualTo("reuse");
        assertThat(objective.path("objectiveId").stringValue(null)).isEqualTo(objectives.iterator().next());
        assertThat(exerciseCalls("[[t:objective]]").getLast().prompt()).contains("t1 · Основная мысль материала");
        assertThat(approve(owner, deck, proposal, UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(objectivesOf(material)).isEqualTo(1);
        assertThat(materialsAndExercises(owner, deck, material)).isEqualTo(4);
    }

    private StudyFixtures.Material current(UUID owner, UUID deck, StudyFixtures.Material material) {
        return new StudyFixtures.Material(owner, deck, material.member(),
                UUID.fromString(items.read(owner, deck, material.member(), null).path("itemRevisionId").stringValue(null)),
                material.node(), material.distractor(), material.divider(), material.root());
    }

    // ------------------------------------------------------------------------------- approval

    @Test
    void anApprovalOfOneExerciseIsOneTransactionAndItsReplayChangesNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:single]]");
        Proposal proposal = proposals(owner, deck, started(owner, deck, exercisesSpec(EXACT_1, material))).getFirst();
        long before = deckVersion(deck);
        UUID revisionBefore = deckRevision(deck);
        UUID command = UUID.randomUUID();

        MockHttpServletResponse response = approve(owner, deck, proposal, command);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode published = json(response).path("artifacts").get(0);
        assertThat(published.path("state").stringValue(null)).isEqualTo("PUBLISHED");
        JsonNode reference = published.path("publishedRef");
        assertThat(reference.propertyNames()).containsExactlyInAnyOrder("kind", "exerciseId", "exerciseRevisionId", "objectiveId", "objectiveRevisionId");
        assertThat(deckVersion(deck)).isEqualTo(before + 1);
        JsonNode read = exercises.read(owner, deck, UUID.fromString(reference.path("exerciseId").stringValue(null)), null);
        assertThat(read.path("exerciseRevisionId").stringValue(null)).isEqualTo(reference.path("exerciseRevisionId").stringValue(null));
        assertThat(read.path("subject").path("itemRevisionId").stringValue(null)).isEqualTo(material.itemRevision().toString());
        assertThat(artifactState(proposal.artifact())).isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_provenance WHERE artifact_id=:id AND NOT edited AND owner_id=:owner")
                .param("id", proposal.artifact()).param("owner", owner).query(Integer.class).single()).isEqualTo(1);

        MockHttpServletResponse replay = approve(owner, deck, proposal, approvalBody(command, proposal, revisionBefore), "\"" + before + "\"");
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(deckVersion(deck)).isEqualTo(before + 1);
        assertThat(materialsAndExercises(owner, deck, material)).isEqualTo(1);
        // a published exercise cannot be approved again and a handoff of an exercise is not a valid request
        problem(approve(owner, deck, fresh(owner, deck, proposal), UUID.randomUUID()), 409, "GENERATION_STATE_CONFLICT");
        problem(handoff(owner, deck, fresh(owner, deck, proposal), UUID.randomUUID()), 400, "INVALID_REQUEST");
    }

    @Test
    void aBulkApprovalMixesAMaterialAndExercisesInOneChainAndRollsBackEntirelyWhenOneFails() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:mixed]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_3, material));
        Proposal draftedMaterial = proposal(owner, deck, spec("тема для материала"));
        UUID graft = graft(owner, session, draftedMaterial);
        List<Proposal> all = proposals(owner, deck, session);
        assertThat(all).hasSize(4);
        List<Proposal> mixed = List.of(all.get(3), all.get(0), all.get(1), all.get(2));
        long version = deckVersion(deck);
        int materialsBefore = materials(owner, deck);

        // one of the exercises is corrupted behind the contract's back: the parser of the catalog rejects it after two were published
        corrupt(all.get(2).revision());
        MockHttpServletResponse failed = approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), deckRevision(deck), mixed), ifMatch(deck));
        problem(failed, 400, "INVALID_REQUEST");
        assertThat(deckVersion(deck)).isEqualTo(version);
        assertThat(materials(owner, deck)).isEqualTo(materialsBefore);
        assertThat(materialsAndExercises(owner, deck, material)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.exercise_new_mark WHERE deck_id=:deck").param("deck", deck).query(Integer.class).single()).isZero();
        assertThat(states(session)).containsOnly("PROPOSED");
        assertThat(artifactState(graft)).isEqualTo("PROPOSED");

        // without the corrupted one the mixed command is one chain: the material first, then the exercises, one deck version each
        List<Proposal> healthy = List.of(fresh(owner, deck, all.get(3)), fresh(owner, deck, all.get(0)), fresh(owner, deck, all.get(1)));
        MockHttpServletResponse approved = approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), deckRevision(deck), healthy), ifMatch(deck));
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        JsonNode ack = json(approved);
        assertThat(ack.path("artifacts")).hasSize(3);
        assertThat(ack.path("artifacts").get(0).path("publishedRef").path("kind").stringValue(null)).isEqualTo("ITEM");
        assertThat(ack.path("artifacts").get(1).path("publishedRef").path("kind").stringValue(null)).isEqualTo("EXERCISE");
        assertThat(deckVersion(deck)).isEqualTo(version + 3);
        assertThat(ack.path("deckVersion").stringValue(null)).isEqualTo(Long.toString(version + 3));
        assertThat(materials(owner, deck)).isEqualTo(materialsBefore + 1);
        assertThat(materialsAndExercises(owner, deck, material)).isEqualTo(2);
        assertThat(objectivesOf(material)).isEqualTo(1);
    }

    /** Puts a copy of a material proposal of another session into this exercise session (a mixed command cannot arise otherwise yet). */
    private UUID graft(UUID owner, UUID session, Proposal materialProposal) {
        UUID artifactId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO app_learning.generation_artifact(artifact_id,session_id,owner_id,target_kind,ordinal,state,source_refs,row_version,"
                    + "created_at,updated_at) VALUES (:id,:session,:owner,'ITEM',59,'QUEUED','[]',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                    .param("id", artifactId).param("session", session).param("owner", owner).update();
            jdbc.sql("INSERT INTO app_learning.generation_artifact_revision(revision_id,artifact_id,session_id,owner_id,revision_no,cause,payload,"
                    + "handles,prompt_version,model_route,validation,created_at) SELECT :revision,:artifact,:session,:owner,1,'INITIAL',payload,handles,"
                    + "prompt_version,model_route,validation,CURRENT_TIMESTAMP FROM app_learning.generation_artifact_revision WHERE revision_id=:old")
                    .param("revision", revisionId).param("artifact", artifactId).param("session", session).param("owner", owner)
                    .param("old", materialProposal.revision()).update();
            jdbc.sql("UPDATE app_learning.generation_artifact SET state='PROPOSED',current_revision_id=:revision,revision_count=1,title='Материал' "
                    + "WHERE artifact_id=:id").param("revision", revisionId).param("id", artifactId).update();
        });
        return artifactId;
    }

    private void corrupt(UUID revision) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.generation_artifact_revision SET payload=jsonb_set(payload,'{command,objective,operation}','\"bogus\"') "
                    + "WHERE revision_id=:id").param("id", revision).update();
        });
    }

    @Test
    void anEditedExerciseIsPublishedInsteadOfTheProposalAndIsOnlyValidForTheSameMaterial() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:edit]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_2, material));
        List<Proposal> two = proposals(owner, deck, session);
        ObjectNode edited = (ObjectNode) command(owner, deck, two.getFirst()).deepCopy();
        ((ObjectNode) edited.path("exercise").path("content").path("prompt").get(0)).put("text", "Своими словами: отредактированное условие.");

        // only the shape {objective, exercise}, the same subject material, and a valid exercise
        ObjectNode withReplacement = approvalBody(UUID.randomUUID(), two.getFirst(), deckRevision(deck));
        withReplacement.set("replacement", edited.deepCopy().put("extra", 1));
        problem(approve(owner, deck, two.getFirst(), withReplacement, ifMatch(deck)), 400, "INVALID_REQUEST");
        ObjectNode foreign = edited.deepCopy();
        ((ObjectNode) foreign.path("exercise").path("subject")).put("memberKey", UUID.randomUUID().toString());
        ObjectNode foreignBody = approvalBody(UUID.randomUUID(), two.getFirst(), deckRevision(deck));
        foreignBody.set("replacement", foreign);
        problem(approve(owner, deck, two.getFirst(), foreignBody, ifMatch(deck)), 400, "INVALID_REQUEST");
        ObjectNode broken = edited.deepCopy();
        ((ObjectNode) broken.path("exercise")).put("type", "TELEPATHY");
        ObjectNode brokenBody = approvalBody(UUID.randomUUID(), two.getFirst(), deckRevision(deck));
        brokenBody.set("replacement", broken);
        problem(approve(owner, deck, two.getFirst(), brokenBody, ifMatch(deck)), 400, "INVALID_REQUEST");
        assertThat(artifactState(two.getFirst().artifact())).isEqualTo("PROPOSED");

        ObjectNode body = approvalBody(UUID.randomUUID(), two.getFirst(), deckRevision(deck));
        body.set("replacement", edited);
        MockHttpServletResponse approved = approve(owner, deck, two.getFirst(), body, ifMatch(deck));
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        UUID exercise = UUID.fromString(json(approved).path("artifacts").get(0).path("publishedRef").path("exerciseId").stringValue(null));
        assertThat(exercises.read(owner, deck, exercise, null).path("content").path("prompt").get(0).path("text").stringValue(null))
                .isEqualTo("Своими словами: отредактированное условие.");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_provenance WHERE artifact_id=:id AND edited")
                .param("id", two.getFirst().artifact()).query(Integer.class).single()).isEqualTo(1);

        // an edit belongs to one approval: not to a bulk entry, and not to a material
        ObjectNode bulk = bulkBody(UUID.randomUUID(), deckRevision(deck), List.of(fresh(owner, deck, two.get(1))));
        ((ObjectNode) bulk.path("artifacts").get(0)).set("replacement", edited);
        problem(approveMany(owner, deck, session, bulk, ifMatch(deck)), 400, "INVALID_REQUEST");
        Proposal drafted = proposal(owner, deck, spec("тема"));
        ObjectNode onMaterial = approvalBody(UUID.randomUUID(), drafted, deckRevision(deck));
        onMaterial.set("replacement", edited);
        problem(approve(owner, deck, drafted, onMaterial, ifMatch(deck)), 400, "INVALID_REQUEST");
    }

    // ---------------------------------------------------------------------------------- re-pin

    @Test
    void aMovedMaterialIsFollowedOnlyWhenEverythingTheModelSawIsUnchanged() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, FIRST, SECOND);
        UUID session = started(owner, deck, exercisesSpec(EXACT_2, material));
        List<Proposal> two = proposals(owner, deck, session);

        // a paragraph is appended: every block the model read is as it was, so the proposal follows the material silently
        UUID appended = reviseAppendingParagraph(material);
        MockHttpServletResponse approved = approve(owner, deck, two.get(0), UUID.randomUUID());
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        UUID exercise = UUID.fromString(json(approved).path("artifacts").get(0).path("publishedRef").path("exerciseId").stringValue(null));
        assertThat(exercises.read(owner, deck, exercise, null).path("subject").path("itemRevisionId").stringValue(null)).isEqualTo(appended.toString());
        JsonNode followed = artifact(owner, deck, session, two.get(0).artifact());
        assertThat(followed.path("revision").path("cause").stringValue(null)).isEqualTo("REPIN");
        assertThat(followed.path("revisions")).hasSize(2);
        assertThat(json(events(owner, deck, session, "?after=0&limit=100")).path("events").toString()).contains("AUTO_REPINNED");

        // a block the model read now says something else: the exercise was written about a different text, so the user decides
        UUID edited = reviseText(material);
        MockHttpServletResponse stale = approve(owner, deck, fresh(owner, deck, two.get(1)), UUID.randomUUID());
        problem(stale, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(stale).path("reason").stringValue(null)).isEqualTo("SOURCE_STALE");
        JsonNode lost = artifact(owner, deck, session, two.get(1).artifact());
        assertThat(lost.path("state").stringValue(null)).isEqualTo("STALE");
        assertThat(lost.path("repinStatus").stringValue(null)).isEqualTo("NEEDS_USER_DECISION");

        // a retry regenerates it against the head, with its own small reservation, and the new proposal can be approved
        Proposal staleOne = fresh(owner, deck, two.get(1));
        MockHttpServletResponse retried = retry(owner, deck, staleOne, UUID.randomUUID(), staleOne.version());
        assertThat(retried.getStatus()).as(retried.getContentAsString()).isEqualTo(200);
        awaitState(session, "REVIEW");
        JsonNode regenerated = artifact(owner, deck, session, two.get(1).artifact());
        assertThat(regenerated.path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(regenerated.path("sourceRefs").get(0).path("itemRevisionId").stringValue(null)).isEqualTo(edited.toString());
        assertThat(regenerated.path("repinStatus").isNull()).isTrue();
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
        assertThat(approve(owner, deck, fresh(owner, deck, two.get(1)), UUID.randomUUID()).getStatus()).isEqualTo(200);
    }

    @Test
    void aFailedExerciseWhoseMaterialMovedIsWrittenAgainAgainstTheHead() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:failed-moved]] [[stub:broken-key-always]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_2, material));
        assertThat(states(session)).containsExactly("PROPOSED", "FAILED");
        UUID head = reviseText(material);

        Proposal failed = proposals(owner, deck, session).get(1);
        assertThat(retry(owner, deck, failed, UUID.randomUUID(), failed.version()).getStatus()).isEqualTo(200);
        awaitState(session, "REVIEW");
        // the marker still breaks the first exercise of every answer: the point is the pin, which followed the head
        JsonNode again = artifact(owner, deck, session, failed.artifact());
        assertThat(again.path("sourceRefs").get(0).path("itemRevisionId").stringValue(null)).isEqualTo(head.toString());
        assertThat(exerciseCalls("[[t:failed-moved]]").getLast().prompt()).contains("(уточнено)");
    }

    @Test
    void aDeletedMaterialMakesItsProposalsStaleForTheUserToDecideAndARetryNeedsTheMaterial() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:gone]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_2, material));
        List<Proposal> two = proposals(owner, deck, session);

        JsonNode head = decks.read(owner, deck);
        ObjectNode delete = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        delete.putArray("changes").addObject().put("operation", "delete").put("memberKey", material.member().toString())
                .put("expectedItemRevisionId", material.itemRevision().toString()).put("expectedOrdinal", 0);
        items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readBulk(new ByteArrayInputStream(delete.toString().getBytes(StandardCharsets.UTF_8))));

        MockHttpServletResponse refused = approve(owner, deck, two.getFirst(), UUID.randomUUID());
        problem(refused, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("SOURCE_STALE");
        JsonNode stale = artifact(owner, deck, session, two.getFirst().artifact());
        assertThat(stale.path("state").stringValue(null)).isEqualTo("STALE");
        assertThat(stale.path("repinStatus").stringValue(null)).isEqualTo("NEEDS_USER_DECISION");
        // regenerating needs the material: it is gone, so the retry is refused and nothing is reserved
        Proposal staleOne = fresh(owner, deck, two.getFirst());
        problem(retry(owner, deck, staleOne, UUID.randomUUID(), staleOne.version()), 409, "SOURCE_UNAVAILABLE");
        assertThat(artifactState(two.getFirst().artifact())).isEqualTo("STALE");
        // the user can still reject it
        assertThat(reject(owner, deck, staleOne, UUID.randomUUID(), staleOne.version()).getStatus()).isEqualTo(200);
    }

    @Test
    void anEditedProposalIsPublishedOnlyWhenItStandsOnTheHeadAndOtherwiseItIsStaleNeverAnOpaque404() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck, FIRST, SECOND);
        UUID session = started(owner, deck, exercisesSpec(EXACT_3, material));
        List<Proposal> three = proposals(owner, deck, session);
        UUID head = reviseAppendingParagraph(material);

        // written against the old revision: its pins are not the head
        ObjectNode old = (ObjectNode) command(owner, deck, three.get(1)).deepCopy();
        ObjectNode oldBody = approvalBody(UUID.randomUUID(), three.get(1), deckRevision(deck));
        oldBody.set("replacement", old);
        MockHttpServletResponse stale = approve(owner, deck, three.get(1), oldBody, ifMatch(deck));
        problem(stale, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(stale).path("reason").stringValue(null)).isEqualTo("SOURCE_STALE");
        assertThat(artifactState(three.get(1).artifact())).isEqualTo("STALE");

        // written against the head (the editor pinned it there): published, whatever the proposal's own pin says
        ObjectNode current = (ObjectNode) command(owner, deck, three.get(0)).deepCopy();
        ((ObjectNode) current.path("exercise").path("subject")).put("itemRevisionId", head.toString());
        SessionViews.quoted(current.path("exercise").path("content"), quote -> ((ObjectNode) quote).put("itemRevisionId", head.toString()));
        ObjectNode body = approvalBody(UUID.randomUUID(), three.get(0), deckRevision(deck));
        body.set("replacement", current);
        MockHttpServletResponse approved = approve(owner, deck, three.get(0), body, ifMatch(deck));
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        UUID exercise = UUID.fromString(json(approved).path("artifacts").get(0).path("publishedRef").path("exerciseId").stringValue(null));
        assertThat(exercises.read(owner, deck, exercise, null).path("subject").path("itemRevisionId").stringValue(null)).isEqualTo(head.toString());
        // an edit made when the head had not moved is still published by an approval that has no drift to deal with
        assertThat(artifactState(three.get(0).artifact())).isEqualTo("PUBLISHED");
    }

    @Test
    void aReusedObjectiveWhoseRevisionMovedIsPublishedAgainstItsHeadAndAGoneOneMakesTheProposalStale() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:objective-moved]]");
        // the material already has an objective, so the proposals reuse it (t1)
        UUID first = started(owner, deck, exercisesSpec(EXACT_1, material));
        assertThat(approve(owner, deck, proposals(owner, deck, first).getFirst(), UUID.randomUUID()).getStatus()).isEqualTo(200);
        StudyFixtures.Material current = current(owner, deck, material);
        UUID second = started(owner, deck, exercisesSpec(EXACT_2, current));
        List<Proposal> two = proposals(owner, deck, second);
        JsonNode reuse = command(owner, deck, two.get(0)).path("objective");
        assertThat(reuse.path("operation").stringValue(null)).isEqualTo("reuse");

        // the owner renames the objective since: its revision moved
        UUID objectiveId = UUID.fromString(reuse.path("objectiveId").stringValue(null));
        ObjectNode revise = fixtures.createBody(current, fixtures.selfCheck(current, StudyFixtures.blocks(StudyFixtures.text("Ручное")),
                StudyFixtures.blocks(StudyFixtures.quote(current, current.node()))), "ignored");
        revise.putObject("objective").put("operation", "revise").put("objectiveId", objectiveId.toString())
                .put("expectedObjectiveRevisionId", reuse.path("objectiveRevisionId").stringValue(null)).put("title", "Новое название цели");
        exercises.publish(owner, deck, null, deckVersion(deck), ExerciseCommand.readCreate(new ByteArrayInputStream(revise.toString().getBytes(StandardCharsets.UTF_8))));

        MockHttpServletResponse approved = approve(owner, deck, two.get(0), UUID.randomUUID());
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        String revisionNow = jdbc.sql("SELECT revision_id::text FROM app_learning.objective_head WHERE objective_id=:id").param("id", objectiveId)
                .query(String.class).single();
        assertThat(json(approved).path("artifacts").get(0).path("publishedRef").path("objectiveRevisionId").stringValue(null)).isEqualTo(revisionNow);

        // an objective that does not exist (behind the contract's back) is a moved source, not a version conflict
        corruptObjective(two.get(1).revision());
        MockHttpServletResponse gone = approve(owner, deck, fresh(owner, deck, two.get(1)), UUID.randomUUID());
        problem(gone, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(gone).path("reason").stringValue(null)).isEqualTo("SOURCE_STALE");
        assertThat(artifactState(two.get(1).artifact())).isEqualTo("STALE");
    }

    private void corruptObjective(UUID revision) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.generation_artifact_revision SET payload=jsonb_set(payload,'{command,objective,objectiveId}',to_jsonb(CAST(:id AS text))) "
                    + "WHERE revision_id=:revision").param("id", UUID.randomUUID().toString()).param("revision", revision).update();
        });
    }

    @Test
    void aPinThatIsNotTheHeadWhenTheCatalogIsReachedIsAMovedSourceAndNotAnOpaque404() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:race]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_2, material));
        List<Proposal> two = proposals(owner, deck, session);
        // the proposal's subject pin names a revision that is not the head, with no drift visible in its source refs (a lost race)
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.generation_artifact_revision SET payload=jsonb_set(payload,'{command,exercise,subject,itemRevisionId}',"
                    + "to_jsonb(CAST(:id AS text))) WHERE revision_id=:revision").param("id", UUID.randomUUID().toString())
                    .param("revision", two.get(1).revision()).update();
        });
        long version = deckVersion(deck);
        MockHttpServletResponse refused = approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), deckRevision(deck), two), ifMatch(deck));
        problem(refused, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("SOURCE_STALE");
        assertThat(json(refused).path("artifactIds").get(0).stringValue(null)).isEqualTo(two.get(1).artifact().toString());
        // the whole bulk rolled back, and only the proposal that lost its source is stale
        assertThat(deckVersion(deck)).isEqualTo(version);
        assertThat(artifactState(two.get(0).artifact())).isEqualTo("PROPOSED");
        assertThat(artifactState(two.get(1).artifact())).isEqualTo("STALE");
    }

    // ---------------------------------------------------------------------------------- retry

    @Test
    void anExerciseThatFailedBecauseTheProviderWasDownIsWrittenAgainByARetry() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:outage]]");
        provider.outage = true;
        UUID session = started(owner, deck, exercisesSpec(EXACT_2, material));
        assertThat(states(session)).containsOnly("FAILED");
        assertThat(artifactErrors(session)).containsOnly("PROVIDER_UNAVAILABLE");
        assertThat(debits(owner)).isZero();
        assertThat(notificationKinds(owner)).contains("GENERATION_FAILED");
        provider.outage = false;

        Proposal failed = proposals(owner, deck, session).get(1);
        MockHttpServletResponse retried = retry(owner, deck, failed, UUID.randomUUID(), failed.version());
        assertThat(retried.getStatus()).as(retried.getContentAsString()).isEqualTo(200);
        awaitState(session, "REVIEW");
        assertThat(states(session)).containsExactly("FAILED", "PROPOSED");
        // one exercise, two credits
        assertThat(debits(owner)).isEqualTo(2);
        Proposal done = fresh(owner, deck, failed);
        assertThat(approve(owner, deck, done, UUID.randomUUID()).getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------------- the «Новое» mark

    @Test
    void theNewMarkIsSetByApprovalClearedByOpeningOrAnsweringAndExpiresAfterItsTtl() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:mark]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_3, material));
        List<Proposal> three = proposals(owner, deck, session);
        for (Proposal proposal : three) assertThat(approve(owner, deck, fresh(owner, deck, proposal), UUID.randomUUID()).getStatus()).isEqualTo(200);

        // every exercise of the material list says it is new
        List<UUID> ids = exerciseIds(owner, deck, material);
        assertThat(ids).hasSize(3);
        assertThat(newFlags(owner, deck, material)).containsExactly(true, true, true);
        assertThat(exercises.list(owner, deck, null, null).path("exercises")).allSatisfy(entry -> assertThat(entry.path("isNew").booleanValue(false)).isTrue());

        // Study issues them by the normal policy and says so; answering one clears its mark in the attempt's own transaction
        StudyFixtures.Issued first = fixtures.issue(material, "SCHEDULED", null).getFirst();
        assertThat(first.json().path("isNew").booleanValue(false)).isTrue();
        UUID answered = UUID.fromString(jdbc.sql("SELECT exercise_id FROM app_learning.study_presentation WHERE presentation_id=:id")
                .param("id", first.id()).query(String.class).single());
        attempts.submit(owner, deck, first.session(), StudyFixtures.attempt(first, JSON.createObjectNode().put("kind", "CANCEL")));
        assertThat(markedExercises(deck)).doesNotContain(answered).hasSize(2);

        // opening the editor clears another one: 204, idempotent; a stranger's or an absent exercise is the opaque 404
        UUID opened = ids.stream().filter(id -> !id.equals(answered)).findFirst().orElseThrow();
        assertThat(sendCatalog(owner, delete("/decks/" + deck + "/exercises/" + opened + "/new-mark")).getStatus()).isEqualTo(204);
        assertThat(sendCatalog(owner, delete("/decks/" + deck + "/exercises/" + opened + "/new-mark")).getStatus()).isEqualTo(204);
        assertThat(markedExercises(deck)).doesNotContain(opened).hasSize(1);
        assertThat(exercises.read(owner, deck, opened, null)).isNotNull();
        UUID stranger = UUID.randomUUID();
        problem(sendCatalog(stranger, delete("/decks/" + deck + "/exercises/" + opened + "/new-mark")), 404, "RESOURCE_NOT_FOUND");
        problem(sendCatalog(owner, delete("/decks/" + deck + "/exercises/" + UUID.randomUUID() + "/new-mark")), 404, "RESOURCE_NOT_FOUND");
        assertThat(markedExercises(deck)).hasSize(1);

        // the last one expires by age: nothing says new any more, in the list or in a presentation, and the worker purges the row
        jdbc.sql("UPDATE app_learning.exercise_new_mark SET marked_at=statement_timestamp() - interval '8 days' WHERE deck_id=:deck")
                .param("deck", deck).update();
        assertThat(newFlags(owner, deck, material)).containsOnly(false);
        StudyFixtures.Issued later = fixtures.issue(material, "PRACTICE", null).stream().findFirst().orElseThrow();
        assertThat(later.json().path("isNew").booleanValue(true)).isFalse();
        assertThat(retention.purgeBatch().newMarks()).isGreaterThanOrEqualTo(1);
        assertThat(markedExercises(deck)).isEmpty();
    }

    private List<UUID> exerciseIds(UUID owner, UUID deck, StudyFixtures.Material material) {
        List<UUID> ids = new ArrayList<>();
        exercises.list(owner, deck, material.member(), null, null).path("exercises")
                .forEach(entry -> ids.add(UUID.fromString(entry.path("exerciseId").stringValue(null))));
        return ids;
    }

    private List<Boolean> newFlags(UUID owner, UUID deck, StudyFixtures.Material material) {
        List<Boolean> flags = new ArrayList<>();
        exercises.list(owner, deck, material.member(), null, null).path("exercises").forEach(entry -> flags.add(entry.path("isNew").booleanValue(false)));
        return flags;
    }

    private List<UUID> markedExercises(UUID deck) {
        return jdbc.sql("SELECT exercise_id FROM app_learning.exercise_new_mark WHERE deck_id=:deck").param("deck", deck).query(UUID.class).list();
    }

    @Test
    void aReplayPresentationIsNeverNew() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material material = material(owner, deck, "[[t:replay]]");
        UUID session = started(owner, deck, exercisesSpec(EXACT_1, material));
        assertThat(approve(owner, deck, proposals(owner, deck, session).getFirst(), UUID.randomUUID()).getStatus()).isEqualTo(200);
        StudyFixtures.Issued issued = fixtures.issue(material, "SCHEDULED", null).getFirst();
        assertThat(issued.json().path("isNew").booleanValue(false)).isTrue();
        // answering clears the mark, so a replay of the finished session has a copy that says false, and so does the stored original
        attempts.submit(owner, deck, issued.session(), StudyFixtures.attempt(issued, JSON.createObjectNode().put("kind", "CANCEL")));
        List<StudyFixtures.Issued> replay = fixtures.issue(material, "REPLAY", issued.session());
        assertThat(replay).isNotEmpty().allSatisfy(copy -> assertThat(copy.json().path("isNew").booleanValue(true)).isFalse());
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_presentation WHERE session_id=:session AND is_new")
                .param("session", replay.getFirst().session()).query(Integer.class).single()).isZero();
    }
}
