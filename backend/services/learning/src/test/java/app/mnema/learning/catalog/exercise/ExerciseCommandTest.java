package app.mnema.learning.catalog.exercise;

import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.ContractFixtures.mechanic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExerciseCommandTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CHOICE_A = "dddddddd-dddd-4ddd-8ddd-ddddddddddd1";
    private static final String BLANK_1 = "b1a00000-0000-4000-8000-000000000001";
    private static final String LEFT_1 = "1e000000-0000-4000-8000-000000000001";
    private static final String LEFT_2 = "1e000000-0000-4000-8000-000000000002";
    private static final String RIGHT_1 = "7e000000-0000-4000-8000-000000000001";
    private static final String ASSET = "aaaaaaaa-0000-4000-8000-000000000003";

    @ParameterizedTest
    @ValueSource(strings = {"createSelfCheck", "createFreeResponseAudio", "createCloze", "createChoiceVideoMultiple",
            "createMatchMixed"})
    void everyContractFixtureOfTheFiveMechanicsParsesAndKeepsItsStoredShape(String name) {
        ObjectNode fixture = mechanic(name);
        ExerciseCommand command = ExerciseCommand.readCreate(bytes(fixture));
        assertThat(command.exercise().type().name()).isEqualTo(fixture.path("exercise").path("type").textValue());
        // numbers are read as big integers, so compare the persisted text rather than node classes
        assertThat(command.exercise().content().toString()).isEqualTo(fixture.path("exercise").path("content").toString());
        assertThat(command.exercise().answerKey().toString()).isEqualTo(fixture.path("exercise").path("answerKey").toString());
        assertThat(command.exercise().evaluatorPolicy().toString())
                .isEqualTo(fixture.path("exercise").path("evaluatorPolicy").toString());
        assertThat(command.objective()).isInstanceOf(ExerciseCommand.CreateObjective.class);
        assertThat(command.exercise().subject().memberKey().toString())
                .isEqualTo(fixture.path("exercise").path("subject").path("memberKey").textValue());
        assertThat(command.exercise().requiresSemanticAssessment()).isFalse();
        assertThat(command.exercise().requiresSpeechToText()).isFalse();
    }

    @Test
    void mediaMaterialsAndCapabilitiesAreDerivedFromEverySlot() {
        ExerciseCommand match = ExerciseCommand.readCreate(bytes(mechanic("createMatchMixed")));
        assertThat(match.exercise().assets()).extracting(MediaCatalog.ExerciseAsset::kind)
                .containsExactlyInAnyOrder(MediaCatalog.Kind.AUDIO, MediaCatalog.Kind.AUDIO, MediaCatalog.Kind.IMAGE,
                        MediaCatalog.Kind.VIDEO);
        assertThat(match.exercise().materials()).singleElement().satisfies(material ->
                assertThat(material.maxText()).isEqualTo(Slot.COMPACT.maxText()));

        ExerciseCommand selfCheck = ExerciseCommand.readCreate(bytes(mechanic("createSelfCheck")));
        assertThat(selfCheck.exercise().assets()).extracting(MediaCatalog.ExerciseAsset::kind)
                .containsExactly(MediaCatalog.Kind.IMAGE, MediaCatalog.Kind.AUDIO);
        assertThat(selfCheck.exercise().materials()).singleElement().satisfies(material ->
                assertThat(material.maxText()).isEqualTo(Slot.REFERENCE.maxText()));

        ExerciseCommand semantic = ExerciseCommand.readCreate(bytes(mechanic("rejectedAiAssessment")));
        assertThat(semantic.exercise().requiresSemanticAssessment()).isTrue();
        assertThat(semantic.exercise().requiresSpeechToText()).isFalse();
        ExerciseCommand speech = ExerciseCommand.readCreate(bytes(mechanic("rejectedSpeechInput")));
        assertThat(speech.exercise().requiresSpeechToText()).isTrue();
        assertThat(speech.exercise().requiresSemanticAssessment()).isFalse();
    }

    @Test
    void envelopePinsDeckVersionAndPathIdentity() {
        ExerciseCommand command = ExerciseCommand.readCreate(bytes(mechanic("createCloze")));
        UUID deck = UUID.randomUUID();
        UUID exercise = UUID.randomUUID();
        assertThat(command.envelope(deck, exercise, 4).path("expectedDeckVersion").textValue()).isEqualTo("4");
        assertThat(command.envelope(deck, exercise, 4).path("exerciseId").textValue()).isEqualTo(exercise.toString());
        assertThat(command.envelope(deck, null, 4).has("exerciseId")).isFalse();
        command.payload().put("tampered", true);
        assertThat(command.payload().has("tampered")).isFalse();
    }

    @Test
    void objectiveOperationsReuseReviseAndTheUpdatePreconditionAreStrict() {
        ObjectNode reuse = mechanic("createChoiceVideoMultiple");
        reuse.set("objective", mechanic("reuseObjective"));
        assertThat(ExerciseCommand.readCreate(bytes(reuse)).objective()).isInstanceOf(ExerciseCommand.ReuseObjective.class);

        ObjectNode revise = mechanic("createChoiceVideoMultiple");
        revise.set("objective", mechanic("reviseObjective"));
        revise.put("expectedExerciseRevisionId", UUID.randomUUID().toString());
        ExerciseCommand parsed = ExerciseCommand.readUpdate(bytes(revise));
        assertThat(parsed.objective()).isInstanceOf(ExerciseCommand.ReviseObjective.class);
        assertThat(parsed.expectedExerciseRevisionId()).isNotNull();
        revise.remove("expectedExerciseRevisionId");
        assertThatThrownBy(() -> ExerciseCommand.readUpdate(bytes(revise)))
                .isInstanceOf(VersionPreconditionRequiredException.class);
        ObjectNode noDeckPin = mechanic("createCloze");
        noDeckPin.remove("expectedDeckRevisionId");
        assertThatThrownBy(() -> ExerciseCommand.readCreate(bytes(noDeckPin)))
                .isInstanceOf(VersionPreconditionRequiredException.class);

        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                body -> objective(body).put("operation", "replace"),
                body -> objective(body).remove("title"),
                body -> objective(body).put("title", "   "),
                body -> objective(body).put("title", "x".repeat(161)),
                body -> objective(body).put("answerContract", "legacy"),
                body -> objective(body).put("objectiveId", UUID.randomUUID().toString()),
                body -> {
                    body.set("objective", mechanic("reviseObjective"));
                    objective(body).remove("expectedObjectiveRevisionId");
                },
                body -> {
                    body.set("objective", mechanic("reuseObjective"));
                    objective(body).put("title", "extra");
                },
                body -> body.put("updatedBy", "client"),
                body -> body.put("expectedExerciseRevisionId", UUID.randomUUID().toString()),
                body -> body.put("commandId", "not-a-uuid"))) {
            assertInvalid("createCloze", change);
        }
        ObjectNode longTitle = mechanic("createCloze");
        // 160 UTF-16 units: 80 supplementary characters count twice.
        objective(longTitle).put("title", "😀".repeat(80));
        ExerciseCommand.readCreate(bytes(longTitle));
        objective(longTitle).put("title", "😀".repeat(81));
        assertThatThrownBy(() -> ExerciseCommand.readCreate(bytes(longTitle))).isInstanceOf(InvalidRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TYPED", "LISTEN_TYPE", "CLOZE_SINGLE", "SINGLE_CHOICE", "LISTEN_CHOICE",
            "AUDIO_TEXT_MATCH", "ORDER", "CATEGORIZE", "choice", "Choice", ""})
    void legacyAndUnknownMechanicNamesAreRejectedWithoutAliases(String type) {
        assertInvalid("createChoiceVideoMultiple", body -> exercise(body).put("type", type));
    }

    @Test
    void typeContentAnswerKeyAndEvaluatorMustAgree() {
        assertInvalid("createFreeResponseAudio", body -> exercise(body).set("answerKey",
                mechanic("createCloze").path("exercise").path("answerKey")));
        assertInvalid("createCloze", body -> exercise(body).set("answerKey",
                mechanic("createFreeResponseAudio").path("exercise").path("answerKey")));
        assertInvalid("createSelfCheck", body -> exercise(body).set("answerKey",
                mechanic("createFreeResponseAudio").path("exercise").path("answerKey")));
        assertInvalid("createMatchMixed", body -> exercise(body).set("answerKey",
                mechanic("createChoiceVideoMultiple").path("exercise").path("answerKey")));
        assertInvalid("createChoiceVideoMultiple", body -> exercise(body).set("content",
                mechanic("createMatchMixed").path("exercise").path("content")));
        assertInvalid("createCloze", body -> exercise(body).put("type", "FREE_RESPONSE"));
        assertInvalid("createChoiceVideoMultiple", body -> evaluator(body).put("id", "deterministic-match"));
        assertInvalid("createChoiceVideoMultiple", body -> evaluator(body).put("version", "2"));
        assertInvalid("createFreeResponseAudio", body -> evaluator(body).put("id", "ai-semantic"));
        assertInvalid("createCloze", body -> exercise(body).set("evaluatorPolicy",
                mechanic("rejectedAiAssessment").path("exercise").path("evaluatorPolicy")));
        assertInvalid("createChoiceVideoMultiple", body -> evaluator(body).put("rubric", "extra"));
        assertInvalid("createChoiceVideoMultiple", body -> exercise(body).put("schemaVersion", 1));
        assertInvalid("createChoiceVideoMultiple", body -> exercise(body).put("schemaVersion", "2"));
        assertInvalid("createChoiceVideoMultiple", body -> exercise(body).put("enabled", "yes"));
        assertInvalid("createChoiceVideoMultiple", body -> exercise(body).remove("answerKey"));
        // bindings are server-derived, never client input
        assertInvalid("createChoiceVideoMultiple", body -> exercise(body).putArray("bindings"));
        assertInvalid("createChoiceVideoMultiple", body -> exercise(body).put("prompt", "legacy"));
        assertInvalid("createChoiceVideoMultiple", body -> exercise(body).putObject("subject").put("memberKey", "x"));
        assertInvalid("createChoiceVideoMultiple", body -> ((ObjectNode) exercise(body).path("subject")).put("nodeId", ASSET));
    }

    @Test
    void unknownFieldsAreRejectedAtEveryLevel() {
        assertInvalid("createSelfCheck", body -> content(body).put("hints", "x"));
        assertInvalid("createSelfCheck", body -> ((ObjectNode) content(body).withArray("prompt").get(1)).put("style", "bold"));
        assertInvalid("createChoiceVideoMultiple", body -> ((ObjectNode) content(body).withArray("options").get(0)).put("correct", true));
        assertInvalid("createChoiceVideoMultiple", body -> answerKey(body).put("accepted", "x"));
        assertInvalid("createMatchMixed", body -> ((ObjectNode) content(body).withArray("left").get(0)).put("order", 1));
        assertInvalid("createMatchMixed", body -> ((ObjectNode) answerKey(body).withArray("pairs").get(0)).put("score", 1));
        assertInvalid("createCloze", body -> ((ObjectNode) content(body).withArray("passage").get(1)).put("answer", "map"));
        assertInvalid("createCloze", body -> ((ObjectNode) answerKey(body).withArray("blanks").get(0)).put("firstLetter", "m"));
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).put("hintsUsed", 1));
        assertInvalid("createFreeResponseAudio", body -> evaluator(body).put("model", "x"));
    }

    @Test
    void choiceCardinalityAndIdentifiersAreEnforced() {
        // SINGLE with two correct ids
        assertInvalid("createChoiceVideoMultiple", body -> content(body).put("selectionMode", "SINGLE"));
        ExerciseCommand.readCreate(bytes(patched("createChoiceVideoMultiple", body -> {
            content(body).put("selectionMode", "SINGLE");
            answerKey(body).withArray("correctOptionIds").remove(1);
        })));
        // MULTIPLE with a single correct option is valid
        ExerciseCommand.readCreate(bytes(patched("createChoiceVideoMultiple",
                body -> answerKey(body).withArray("correctOptionIds").remove(1))));
        assertInvalid("createChoiceVideoMultiple", body -> content(body).put("selectionMode", "MANY"));
        assertInvalid("createChoiceVideoMultiple", body -> answerKey(body).withArray("correctOptionIds").removeAll());
        assertInvalid("createChoiceVideoMultiple", body -> answerKey(body).withArray("correctOptionIds")
                .set(1, JSON.getNodeFactory().textNode(UUID.randomUUID().toString())));
        assertInvalid("createChoiceVideoMultiple", body -> answerKey(body).withArray("correctOptionIds")
                .set(1, JSON.getNodeFactory().textNode(CHOICE_A)));
        assertInvalid("createChoiceVideoMultiple", body -> ((ObjectNode) content(body).withArray("options").get(1))
                .put("optionId", CHOICE_A));
        assertInvalid("createChoiceVideoMultiple", body -> drop(content(body).withArray("options"), 3, 2, 1));
        assertInvalid("createChoiceVideoMultiple", body -> {
            ArrayNode options = content(body).withArray("options");
            for (int index = 0; index < 9; index++) options.add(option(UUID.randomUUID()));
        });
        assertInvalid("createChoiceVideoMultiple", body -> ((ObjectNode) content(body).withArray("options").get(0))
                .put("optionId", CHOICE_A.toUpperCase()));
        assertInvalid("createChoiceVideoMultiple", body -> ((ObjectNode) content(body).withArray("options").get(0))
                .put("optionId", "00000000-0000-0000-0000-000000000000"));
        // twelve options are valid
        ExerciseCommand.readCreate(bytes(patched("createChoiceVideoMultiple", body -> {
            ArrayNode options = content(body).withArray("options");
            for (int index = 0; index < 8; index++) options.add(option(UUID.randomUUID()));
        })));
    }

    @Test
    void matchMustBeAnExactBijectionOfTwoToSixPairs() {
        assertInvalid("createMatchMixed", body -> ((ObjectNode) answerKey(body).withArray("pairs").get(1)).put("rightId", RIGHT_1));
        assertInvalid("createMatchMixed", body -> ((ObjectNode) answerKey(body).withArray("pairs").get(1)).put("leftId", LEFT_1));
        assertInvalid("createMatchMixed", body -> answerKey(body).withArray("pairs").remove(3));
        assertInvalid("createMatchMixed", body -> ((ObjectNode) answerKey(body).withArray("pairs").get(0))
                .put("leftId", UUID.randomUUID().toString()));
        assertInvalid("createMatchMixed", body -> ((ObjectNode) answerKey(body).withArray("pairs").get(0))
                .put("rightId", UUID.randomUUID().toString()));
        // duplicate item ids across the two sides and within a side
        assertInvalid("createMatchMixed", body -> ((ObjectNode) content(body).withArray("right").get(0)).put("itemId", LEFT_1));
        assertInvalid("createMatchMixed", body -> ((ObjectNode) content(body).withArray("left").get(1)).put("itemId", LEFT_1));
        // sides of different size
        assertInvalid("createMatchMixed", body -> content(body).withArray("right").remove(3));
        // one pair per side
        assertInvalid("createMatchMixed", body -> {
            drop(content(body).withArray("left"), 3, 2, 1);
            drop(content(body).withArray("right"), 3, 2, 1);
            drop(answerKey(body).withArray("pairs"), 3, 2, 1);
        });
        // seven pairs
        assertInvalid("createMatchMixed", body -> addPairs(body, 3));
        // six pairs and two pairs are valid
        ExerciseCommand.readCreate(bytes(patched("createMatchMixed", body -> addPairs(body, 2))));
        ExerciseCommand.readCreate(bytes(patched("createMatchMixed", body -> {
            drop(content(body).withArray("left"), 3, 2);
            drop(content(body).withArray("right"), 3, 2);
            drop(answerKey(body).withArray("pairs"), 3, 2);
        })));
    }

    @Test
    void clozeBlanksAndKeysMustAgreeExactly() {
        // missing key
        assertInvalid("createCloze", body -> answerKey(body).withArray("blanks").remove(2));
        // extra key
        assertInvalid("createCloze", body -> answerKey(body).withArray("blanks").add(Blocks.blankKey(UUID.randomUUID())));
        // key naming a blank twice, passage naming a blank twice, key for a blank that is not in the passage
        assertInvalid("createCloze", body -> ((ObjectNode) answerKey(body).withArray("blanks").get(1)).put("blankId", BLANK_1));
        assertInvalid("createCloze", body -> ((ObjectNode) content(body).withArray("passage").get(3)).put("blankId", BLANK_1));
        assertInvalid("createCloze", body -> ((ObjectNode) answerKey(body).withArray("blanks").get(2))
                .put("blankId", UUID.randomUUID().toString()));
        // ANSWER_LENGTH with answers of different lengths
        assertInvalid("createCloze", body -> ((ObjectNode) answerKey(body).withArray("blanks").get(0))
                .withArray("accepted").add("maps"));
        // the length is counted in NFC code points: decomposed and precomposed "é" are the same width
        ExerciseCommand.readCreate(bytes(patched("createCloze", body -> ((ObjectNode) answerKey(body).withArray("blanks").get(0))
                .withArray("accepted").removeAll().add("caf\u00e9").add("cafe\u0301"))));
        assertInvalid("createCloze", body -> ((ObjectNode) answerKey(body).withArray("blanks").get(0))
                .withArray("accepted").removeAll().add("a".repeat(81)));
        // fixed length bounds and size modes
        assertInvalid("createCloze", body -> size(body, 3).put("length", 4));
        assertInvalid("createCloze", body -> size(body, 3).put("length", 21));
        assertInvalid("createCloze", body -> size(body, 1).put("length", 3));
        assertInvalid("createCloze", body -> size(body, 1).put("mode", "AUTO"));
        // a text segment is required and text may not be empty
        assertInvalid("createCloze", body -> {
            ArrayNode passage = content(body).withArray("passage");
            for (int index = passage.size() - 1; index >= 0; index--) {
                if ("TEXT".equals(passage.get(index).path("kind").textValue())) passage.remove(index);
            }
        });
        assertInvalid("createCloze", body -> ((ObjectNode) content(body).withArray("passage").get(0)).put("text", ""));
        assertInvalid("createCloze", body -> ((ObjectNode) content(body).withArray("passage").get(0)).put("kind", "CODE"));
        assertInvalid("createCloze", body -> ((ObjectNode) content(body).withArray("passage").get(1)).put("firstLetterHint", "yes"));
        // too much passage text, too many blanks or segments
        assertInvalid("createCloze", body -> ((ObjectNode) content(body).withArray("passage").get(0)).put("text", "x".repeat(4_001)));
        assertInvalid("createCloze", body -> addBlanks(body, 10));
        assertInvalid("createCloze", body -> {
            ArrayNode passage = content(body).withArray("passage");
            for (int index = 0; index < 62; index++) passage.add(Blocks.text(" "));
        });
        // whitespace-only segments are legal (indentation between blanks) and text is kept verbatim
        ExerciseCommand cloze = ExerciseCommand.readCreate(bytes(patched("createCloze", body -> content(body)
                .withArray("passage").insert(1, Blocks.text("\n    ")))));
        assertThat(cloze.exercise().content().path("passage").get(1).path("text").textValue()).isEqualTo("\n    ");
        // twelve blanks are valid
        ExerciseCommand.readCreate(bytes(patched("createCloze", body -> addBlanks(body, 9))));
    }

    @Test
    void textRulesBoundAcceptedAnswersNormalizationAndSoftMatching() {
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).withArray("accepted").removeAll());
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).withArray("accepted").add("Erinnerung"));
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).withArray("accepted").add("  "));
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).withArray("accepted").add("x".repeat(513)));
        assertInvalid("createFreeResponseAudio", body -> {
            ArrayNode accepted = answerKey(body).withArray("accepted").removeAll();
            for (int index = 0; index < 21; index++) accepted.add("answer" + index);
        });
        ExerciseCommand.readCreate(bytes(patched("createFreeResponseAudio", body -> {
            ArrayNode accepted = answerKey(body).withArray("accepted").removeAll();
            for (int index = 0; index < 20; index++) accepted.add("answer" + index);
        })));
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).withArray("normalization").removeAll());
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).withArray("normalization").add("TRIM"));
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).withArray("normalization").add("REVERSE"));
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).put("matchingMode", "FUZZY"));
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).remove("matchingMode"));
        // an entry that soft normalization erases would match every empty-looking answer
        assertInvalid("createFreeResponseAudio", body -> {
            answerKey(body).put("matchingMode", "SOFT");
            answerKey(body).withArray("accepted").add("--");
        });
        assertInvalid("createFreeResponseAudio", body -> answerKey(body).put("kind", "TEXT_ALIASES"));
        // blank-level limits are tighter than the free-response limits
        assertInvalid("createCloze", body -> ((ObjectNode) answerKey(body).withArray("blanks").get(2))
                .withArray("accepted").removeAll().add("x".repeat(201)));
        assertInvalid("createCloze", body -> {
            ArrayNode accepted = ((ObjectNode) answerKey(body).withArray("blanks").get(2)).withArray("accepted").removeAll();
            for (int index = 0; index < 11; index++) accepted.add("a" + index);
        });
    }

    @Test
    void slotProfilesBoundKindsBlockCountsAndTextLengthsInUtf16Units() {
        // YOUTUBE is allowed in prompts and references but not in compact slots
        ExerciseCommand.readCreate(bytes(patched("createFreeResponseAudio",
                body -> content(body).withArray("reference").add(Blocks.youtube()))));
        ExerciseCommand.readCreate(bytes(patched("createSelfCheck",
                body -> content(body).withArray("prompt").add(Blocks.youtube()))));
        assertInvalid("createChoiceVideoMultiple", body -> option(body, 0).withArray("blocks").removeAll().add(Blocks.youtube()));
        assertInvalid("createMatchMixed", body -> ((ObjectNode) content(body).withArray("left").get(0))
                .withArray("blocks").removeAll().add(Blocks.youtube()));
        assertInvalid("createFreeResponseAudio", body -> content(body).withArray("reference").add(Blocks.youtube("short")));
        // compact slots: one or two blocks, at most one text-like and at most one media block
        assertInvalid("createChoiceVideoMultiple", body -> option(body, 0).withArray("blocks")
                .add(Blocks.text("b")).add(Blocks.image(ASSET)));
        assertInvalid("createChoiceVideoMultiple", body -> option(body, 0).withArray("blocks").add(Blocks.text("second")));
        assertInvalid("createChoiceVideoMultiple", body -> option(body, 2).withArray("blocks").add(Blocks.image(ASSET)));
        assertInvalid("createChoiceVideoMultiple", body -> option(body, 0).withArray("blocks").removeAll());
        assertInvalid("createChoiceVideoMultiple", body -> option(body, 0).withArray("blocks").add(Blocks.material()));
        // 300 UTF-16 units fit, 301 do not; a supplementary character counts twice
        ExerciseCommand.readCreate(bytes(patched("createChoiceVideoMultiple", body -> optionText(body, "x".repeat(300)))));
        ExerciseCommand.readCreate(bytes(patched("createChoiceVideoMultiple", body -> optionText(body, "😀".repeat(150)))));
        assertInvalid("createChoiceVideoMultiple", body -> optionText(body, "x".repeat(301)));
        assertInvalid("createChoiceVideoMultiple", body -> optionText(body, "😀".repeat(151)));
        assertInvalid("createMatchMixed", body -> ((ObjectNode) ((ObjectNode) content(body).withArray("left").get(0))
                .withArray("blocks").get(0)).put("text", "x".repeat(301)));
        // prompt and reference allow 4000 verbatim units, newlines and indentation included
        ExerciseCommand text = ExerciseCommand.readCreate(bytes(patched("createFreeResponseAudio", body ->
                ((ObjectNode) content(body).withArray("prompt").get(1)).put("text", "  a\n\tb  " + "x".repeat(3_990)))));
        assertThat(text.exercise().content().path("prompt").get(1).path("text").textValue()).startsWith("  a\n\tb  ");
        assertInvalid("createFreeResponseAudio", body -> ((ObjectNode) content(body).withArray("prompt").get(1))
                .put("text", "x".repeat(4_001)));
        assertInvalid("createFreeResponseAudio", body -> ((ObjectNode) content(body).withArray("prompt").get(1)).put("text", " \n "));
        // prompt block counts
        assertInvalid("createFreeResponseAudio", body -> content(body).withArray("prompt").removeAll());
        assertInvalid("createFreeResponseAudio", body -> {
            ArrayNode prompt = content(body).withArray("prompt");
            while (prompt.size() < 9) prompt.add(Blocks.text("more"));
        });
        ExerciseCommand.readCreate(bytes(patched("createFreeResponseAudio", body -> {
            ArrayNode prompt = content(body).withArray("prompt");
            while (prompt.size() < 8) prompt.add(Blocks.text("more"));
        })));
        assertInvalid("createSelfCheck", body -> content(body).withArray("reference").removeAll());
        ExerciseCommand.readCreate(bytes(patched("createFreeResponseAudio", body -> content(body).withArray("reference").removeAll())));
        assertInvalid("createFreeResponseAudio", body -> content(body).put("responseInput", "SPEECH"));
        assertInvalid("createFreeResponseAudio", body -> content(body).remove("responseInput"));
        // media blocks: labels, transcripts and alt text
        assertInvalid("createSelfCheck", body -> ((ObjectNode) content(body).withArray("prompt").get(0)).put("alt", " "));
        assertInvalid("createSelfCheck", body -> ((ObjectNode) content(body).withArray("prompt").get(0)).put("alt", "a".repeat(1_025)));
        assertInvalid("createSelfCheck", body -> ((ObjectNode) content(body).withArray("reference").get(1)).put("title", " "));
        assertInvalid("createSelfCheck", body -> ((ObjectNode) content(body).withArray("reference").get(1)).put("transcript", " "));
        assertInvalid("createSelfCheck", body -> ((ObjectNode) content(body).withArray("reference").get(1)).put("transcript", "t".repeat(16_385)));
        assertInvalid("createSelfCheck", body -> ((ObjectNode) content(body).withArray("reference").get(1)).remove("title"));
        assertInvalid("createSelfCheck", body -> ((ObjectNode) content(body).withArray("reference").get(1)).put("assetId", "bad"));
        assertInvalid("createSelfCheck", body -> ((ObjectNode) content(body).withArray("reference").get(0)).remove("nodeId"));
        assertInvalid("createSelfCheck", body -> ((ObjectNode) content(body).withArray("reference").get(0)).put("kind", "NODE_TEXT"));
        assertInvalid("createSelfCheck", body -> content(body).withArray("reference").add(JSON.getNodeFactory().textNode("loose")));
    }

    @Test
    void anAssetCannotBeTwoMediaKindsAndMediaBlocksAreBounded() {
        assertInvalid("createChoiceVideoMultiple", body -> ((ObjectNode) option(body, 3).withArray("blocks").get(1))
                .put("assetId", ASSET));
        // the same asset in two slots with the same kind is one reference
        ExerciseCommand reused = ExerciseCommand.readCreate(bytes(patched("createChoiceVideoMultiple", body ->
                option(body, 0).withArray("blocks").removeAll().add(Blocks.image(ASSET)))));
        assertThat(reused.exercise().assets().stream().filter(asset -> asset.assetId().toString().equals(ASSET))).hasSize(1);
        // the structural maximum (eight prompt and twelve option media blocks) fits the bound of 32
        ExerciseCommand.readCreate(bytes(patched("createChoiceVideoMultiple", body -> {
            ArrayNode prompt = content(body).withArray("prompt").removeAll();
            for (int index = 0; index < 8; index++) prompt.add(Blocks.youtube());
            ArrayNode options = content(body).withArray("options");
            for (int index = 0; index < 8; index++) options.add(option(UUID.randomUUID(), Blocks.image(ASSET)));
        })));
        assertThat(ExerciseCommand.MAX_MEDIA_BLOCKS).isEqualTo(32);
    }

    @Test
    void rubricStructureIsStrictlyValidated() {
        assertThat(ExerciseCommand.readCreate(bytes(mechanic("rejectedAiAssessment"))).exercise().policy().semantic()).isTrue();
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                rubric -> rubric.remove("referenceAnswer"),
                rubric -> rubric.put("referenceAnswer", " "),
                rubric -> rubric.put("referenceAnswer", "x".repeat(4_001)),
                rubric -> rubric.remove("criteria"),
                rubric -> rubric.withArray("criteria").removeAll(),
                rubric -> { for (int index = 0; index < 9; index++) criterion(rubric, UUID.randomUUID().toString()); },
                rubric -> ((ObjectNode) rubric.withArray("criteria").get(1)).put("criterionId",
                        rubric.path("criteria").get(0).path("criterionId").textValue()),
                rubric -> ((ObjectNode) rubric.withArray("criteria").get(0)).put("critical", "yes"),
                rubric -> ((ObjectNode) rubric.withArray("criteria").get(0)).put("description", "d".repeat(501)),
                rubric -> ((ObjectNode) rubric.withArray("criteria").get(0)).put("weight", 3),
                rubric -> rubric.withArray("levels").remove(2),
                rubric -> rubric.withArray("levels").add(rubric.path("levels").get(0)),
                rubric -> ((ObjectNode) rubric.withArray("levels").get(0)).put("level", "PARTIAL"),
                rubric -> ((ObjectNode) rubric.withArray("levels").get(2)).put("level", "UNSURE"),
                rubric -> ((ObjectNode) rubric.withArray("levels").get(1)).put("description", ""),
                rubric -> rubric.put("score", 100))) {
            assertInvalid("rejectedAiAssessment", body -> change.accept((ObjectNode) evaluator(body).path("rubric")));
        }
        // ten criteria are valid; the semantic evaluator needs its rubric and version 1
        ExerciseCommand.readCreate(bytes(patched("rejectedAiAssessment", body -> {
            ObjectNode rubric = (ObjectNode) evaluator(body).path("rubric");
            for (int index = 0; index < 8; index++) criterion(rubric, UUID.randomUUID().toString());
        })));
        assertInvalid("rejectedAiAssessment", body -> evaluator(body).put("version", "2"));
        assertInvalid("rejectedAiAssessment", body -> evaluator(body).remove("rubric"));
    }

    @Test
    void oversizedMalformedAndNonObjectBodiesAreInvalid() {
        assertThatThrownBy(() -> ExerciseCommand.readCreate(new java.io.ByteArrayInputStream(new byte[0])))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> ExerciseCommand.readCreate(new java.io.ByteArrayInputStream("[]".getBytes())))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> ExerciseCommand.readCreate(new java.io.ByteArrayInputStream(
                ("{\"x\":\"" + "a".repeat(262_144) + "\"}").getBytes()))).isInstanceOf(InvalidRequestException.class);
    }

    private static void addPairs(ObjectNode body, int count) {
        for (int index = 0; index < count; index++) {
            UUID left = UUID.randomUUID();
            UUID right = UUID.randomUUID();
            content(body).withArray("left").add(item(left, Blocks.text("l" + index)));
            content(body).withArray("right").add(item(right, Blocks.text("r" + index)));
            answerKey(body).withArray("pairs").addObject().put("leftId", left.toString()).put("rightId", right.toString());
        }
    }

    private static void addBlanks(ObjectNode body, int count) {
        ArrayNode passage = content(body).withArray("passage");
        ArrayNode blanks = answerKey(body).withArray("blanks");
        for (int index = 0; index < count; index++) {
            UUID id = UUID.randomUUID();
            passage.add(Blocks.text(" ")).add(Blocks.blank(id));
            blanks.add(Blocks.blankKey(id));
        }
    }

    private static ObjectNode size(ObjectNode body, int passageIndex) {
        return (ObjectNode) content(body).withArray("passage").get(passageIndex).path("size");
    }

    private static void criterion(ObjectNode rubric, String id) {
        rubric.withArray("criteria").addObject().put("criterionId", id).put("description", "More").put("critical", false);
    }

    private static ObjectNode option(ObjectNode body, int index) {
        return (ObjectNode) content(body).withArray("options").get(index);
    }

    private static void optionText(ObjectNode body, String text) {
        ((ObjectNode) option(body, 0).withArray("blocks").get(0)).put("text", text);
    }

    private static ObjectNode option(UUID id, ObjectNode... blocks) {
        ObjectNode option = JSON.createObjectNode().put("optionId", id.toString());
        ArrayNode values = option.putArray("blocks");
        if (blocks.length == 0) values.add(Blocks.text("extra " + id));
        for (ObjectNode block : blocks) values.add(block);
        return option;
    }

    private static ObjectNode item(UUID id, ObjectNode block) {
        ObjectNode item = JSON.createObjectNode().put("itemId", id.toString());
        item.putArray("blocks").add(block);
        return item;
    }

    private static ObjectNode patched(String fixture, Consumer<ObjectNode> change) {
        ObjectNode body = mechanic(fixture);
        change.accept(body);
        return body;
    }

    private static void assertInvalid(String fixture, Consumer<ObjectNode> change) {
        ObjectNode body = patched(fixture, change);
        assertThatThrownBy(() -> ExerciseCommand.readCreate(bytes(body)))
                .as(body.toString()).isInstanceOf(InvalidRequestException.class);
    }

    private static ObjectNode objective(ObjectNode body) { return body.withObject("objective"); }

    private static ObjectNode exercise(ObjectNode body) { return body.withObject("exercise"); }

    private static ObjectNode content(ObjectNode body) { return exercise(body).withObject("content"); }

    private static ObjectNode answerKey(ObjectNode body) { return exercise(body).withObject("answerKey"); }

    private static ObjectNode evaluator(ObjectNode body) { return exercise(body).withObject("evaluatorPolicy"); }

    /** Small block builders local to this test, independent of the Spring-aware fixtures. */
    private static final class Blocks {
        static ObjectNode text(String value) { return JSON.createObjectNode().put("kind", "TEXT").put("text", value); }

        static ObjectNode image(String asset) {
            return JSON.createObjectNode().put("kind", "IMAGE").put("assetId", asset).put("alt", "alt");
        }

                static ObjectNode youtube() { return youtube("dQw4w9WgXcQ"); }

        static ObjectNode youtube(String videoId) {
            return JSON.createObjectNode().put("kind", "YOUTUBE").put("videoId", videoId).put("title", "clip");
        }

        static ObjectNode material() {
            return JSON.createObjectNode().put("kind", "MATERIAL").put("memberKey", LEFT_1)
                    .put("itemRevisionId", LEFT_2).put("nodeId", RIGHT_1);
        }

        static ObjectNode blank(UUID id) {
            ObjectNode blank = JSON.createObjectNode().put("kind", "BLANK").put("blankId", id.toString());
            blank.putObject("size").put("mode", "FIXED").put("length", 6);
            return blank.put("firstLetterHint", false);
        }

        static ObjectNode blankKey(UUID id) {
            ObjectNode key = JSON.createObjectNode().put("blankId", id.toString());
            key.putArray("accepted").add("answer");
            key.putArray("normalization").add("TRIM");
            return key.put("matchingMode", "STRICT");
        }
    }

    private static void drop(com.fasterxml.jackson.databind.node.ArrayNode array, int... indexes) {
        for (int index : indexes) array.remove(index);
    }
}
