package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.InvalidRequestException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.function.Consumer;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.ContractFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptCommandTest {
    @ParameterizedTest
    @ValueSource(strings = {"selfCheck", "freeResponse", "cloze", "choice", "match", "order", "orderEquivalent",
            "categorize", "cancel"})
    void everyContractSubmitParsesToItsResponseKind(String name) {
        AttemptCommand command = read(submit(name));
        Class<?> expected = switch (name) {
            case "selfCheck" -> AttemptCommand.SelfCheckResponse.class;
            case "freeResponse" -> AttemptCommand.TextResponse.class;
            case "cloze" -> AttemptCommand.ClozeResponse.class;
            case "choice" -> AttemptCommand.ChoiceResponse.class;
            case "match" -> AttemptCommand.MatchResponse.class;
            case "order", "orderEquivalent" -> AttemptCommand.OrderResponse.class;
            case "categorize" -> AttemptCommand.CategorizeResponse.class;
            default -> AttemptCommand.CancelResponse.class;
        };
        assertThat(command.response()).isInstanceOf(expected);
        assertThat(command.envelope(UUID.randomUUID(), UUID.randomUUID()).has("deckId")).isTrue();
        assertThat(command.payload().has("hintsUsed")).isFalse();
    }

    @Test
    void attemptsFixtureParsesWithoutClientHintAuthority() {
        JsonNode attempts = fixture("attempts.json");
        for (String name : new String[] {"freeResponseSubmit", "selfCheckSubmit", "choiceSubmit", "cancelSubmit"}) {
            assertThat(read(attempts.path(name)).presentationId()).isNotNull();
        }
    }

    @Test
    void hintsAndAuthorityFieldsAreUnknownFields() {
        for (String field : new String[] {"hintsUsed", "mode", "bindings", "correctAnswer", "deckRevisionId"}) {
            ObjectNode body = submit("freeResponse");
            body.putArray(field);
            assertInvalid(body);
        }
        assertInvalid(mutate("freeResponse", body -> body.remove("confidence")));
        assertInvalid(mutate("freeResponse", body -> body.put("durationMs", 3_600_001)));
        assertInvalid(mutate("freeResponse", body -> body.put("durationMs", -1)));
        assertInvalid(mutate("freeResponse", body -> body.put("durationMs", "1")));
        assertInvalid(mutate("freeResponse", body -> body.put("confidence", "SURE")));
        assertInvalid(mutate("freeResponse", body -> body.put("nonce", "short")));
        assertInvalid(mutate("freeResponse", body -> body.put("attemptId", "bad")));
        assertInvalid(mutate("freeResponse", body -> body.put("presentationId", UUID.randomUUID().toString().toUpperCase())));
    }

    @Test
    void textAndSelfCheckResponsesAreStrict() {
        assertInvalid(mutate("freeResponse", body -> response(body).put("text", "x".repeat(4_097))));
        assertInvalid(mutate("freeResponse", body -> response(body).put("extra", 1)));
        assertInvalid(mutate("freeResponse", body -> response(body).put("kind", "SPEECH")));
        assertInvalid(mutate("freeResponse", body -> response(body).put("kind", "TYPED")));
        assertInvalid(mutate("selfCheck", body -> response(body).put("rating", "GREAT")));
        assertInvalid(mutate("cancel", body -> response(body).put("reason", "tired")));
        assertThat(read(mutate("freeResponse", body -> response(body).put("text", ""))).response())
                .isEqualTo(new AttemptCommand.TextResponse(""));
    }

    @Test
    void clozeResponsesNeedUniqueBlankIdsAndBoundedText() {
        assertInvalid(mutate("cloze", body -> response(body).withArray("blanks").removeAll()));
        assertInvalid(mutate("cloze", body -> ((ObjectNode) response(body).withArray("blanks").get(1))
                .put("blankId", response(body).path("blanks").get(0).path("blankId").stringValue(null))));
        assertInvalid(mutate("cloze", body -> ((ObjectNode) response(body).withArray("blanks").get(0)).put("text", "x".repeat(1_025))));
        assertInvalid(mutate("cloze", body -> ((ObjectNode) response(body).withArray("blanks").get(0)).put("hinted", true)));
        assertInvalid(mutate("cloze", body -> ((ObjectNode) response(body).withArray("blanks").get(0)).remove("text")));
        assertInvalid(mutate("cloze", body -> {
            ArrayNode blanks = response(body).withArray("blanks");
            for (int index = 0; index < 10; index++) blanks.addObject().put("blankId", UUID.randomUUID().toString()).put("text", "x");
        }));
        assertInvalid(mutate("cloze", body -> response(body).put("kind", "CLOZE_SINGLE")));
    }

    @Test
    void choiceResponsesRejectEmptyDuplicateOversizedAndScalarSelections() {
        assertThat(((AttemptCommand.ChoiceResponse) read(submit("choice")).response()).optionIds()).hasSize(2);
        assertInvalid(mutate("choice", body -> response(body).withArray("optionIds").removeAll()));
        assertInvalid(mutate("choice", body -> response(body).withArray("optionIds").add(
                response(body).path("optionIds").get(0).stringValue(null))));
        assertInvalid(mutate("choice", body -> {
            response(body).remove("optionIds");
            response(body).put("optionId", UUID.randomUUID().toString());
        }));
        assertInvalid(mutate("choice", body -> {
            ArrayNode ids = response(body).withArray("optionIds");
            for (int index = 0; index < 11; index++) ids.add(UUID.randomUUID().toString());
        }));
    }

    @Test
    void matchResponsesNeedTwoToSixDistinctPairsWithTheNewSideNames() {
        assertThat(((AttemptCommand.MatchResponse) read(submit("match")).response()).pairs()).hasSize(4);
        assertInvalid(mutate("match", body -> ((ObjectNode) response(body).withArray("pairs").get(0)).put("rightId",
                response(body).path("pairs").get(1).path("rightId").stringValue(null))));
        assertInvalid(mutate("match", body -> ((ObjectNode) response(body).withArray("pairs").get(0)).put("leftId",
                response(body).path("pairs").get(1).path("leftId").stringValue(null))));
        assertInvalid(mutate("match", body -> drop(response(body).withArray("pairs"), 3, 2, 1)));
        assertInvalid(mutate("match", body -> {
            ArrayNode pairs = response(body).withArray("pairs");
            for (int index = 0; index < 3; index++) {
                pairs.addObject().put("leftId", UUID.randomUUID().toString()).put("rightId", UUID.randomUUID().toString());
            }
        }));
        assertInvalid(mutate("match", body -> {
            ObjectNode pair = (ObjectNode) response(body).withArray("pairs").get(0);
            pair.put("cueId", pair.remove("leftId").stringValue(null));
        }));
        assertInvalid(mutate("match", body -> {
            ObjectNode pair = (ObjectNode) response(body).withArray("pairs").get(0);
            pair.put("optionId", pair.remove("rightId").stringValue(null));
        }));
    }

    @Test
    void orderResponsesAreTwoToTwelveDistinctIdsInTheSubmittedOrder() {
        AttemptCommand.OrderResponse order = (AttemptCommand.OrderResponse) read(submit("order")).response();
        assertThat(order.sequence()).hasSize(6);
        assertThat(order.sequence().get(1).toString()).isEqualTo("0d000000-0000-4000-8000-000000000003");
        assertInvalid(mutate("order", body -> response(body).withArray("sequence").set(1,
                response(body).path("sequence").get(0))));
        assertInvalid(mutate("order", body -> response(body).withArray("sequence").add(
                response(body).path("sequence").get(0).stringValue(null))));
        assertInvalid(mutate("order", body -> drop(response(body).withArray("sequence"), 5, 4, 3, 2, 1)));
        assertInvalid(mutate("order", body -> response(body).withArray("sequence").removeAll()));
        assertInvalid(mutate("order", body -> {
            ArrayNode sequence = response(body).withArray("sequence");
            while (sequence.size() < 13) sequence.add(UUID.randomUUID().toString());
        }));
        assertInvalid(mutate("order", body -> response(body).put("positions", 1)));
        assertInvalid(mutate("order", body -> response(body).withArray("sequence").set(0, JsonNodeFactory.instance.stringNode("x"))));
        assertInvalid(mutate("order", body -> response(body).put("sequence", "not-an-array")));
        assertInvalid(mutate("order", body -> response(body).put("kind", "SEQUENCE")));
        assertInvalid(mutate("order", body -> response(body).remove("sequence")));
        assertThat(((AttemptCommand.OrderResponse) read(mutate("order", body -> {
            ArrayNode sequence = response(body).withArray("sequence");
            while (sequence.size() < 12) sequence.add(UUID.randomUUID().toString());
        })).response()).sequence()).hasSize(12);
    }

    @Test
    void categorizeResponsesMapDistinctItemsToAnyCategory() {
        AttemptCommand.CategorizeResponse categorize =
                (AttemptCommand.CategorizeResponse) read(submit("categorize")).response();
        assertThat(categorize.assignments()).hasSize(4);
        // a category repeated across assignments is the point of the mechanic
        assertThat(categorize.assignments().stream().map(AttemptCommand.CategoryAssignment::categoryId).distinct())
                .hasSizeLessThan(categorize.assignments().size());
        assertInvalid(mutate("categorize", body -> ((ObjectNode) response(body).withArray("assignments").get(1))
                .put("itemId", response(body).path("assignments").get(0).path("itemId").stringValue(null))));
        assertInvalid(mutate("categorize", body -> drop(response(body).withArray("assignments"), 3, 2, 1)));
        assertInvalid(mutate("categorize", body -> {
            ArrayNode assignments = response(body).withArray("assignments");
            while (assignments.size() < 13) {
                assignments.addObject().put("itemId", UUID.randomUUID().toString())
                        .put("categoryId", UUID.randomUUID().toString());
            }
        }));
        assertInvalid(mutate("categorize", body -> ((ObjectNode) response(body).withArray("assignments").get(0))
                .put("label", "Глагол")));
        assertInvalid(mutate("categorize", body -> ((ObjectNode) response(body).withArray("assignments").get(0))
                .remove("categoryId")));
        assertInvalid(mutate("categorize", body -> {
            ObjectNode assignment = (ObjectNode) response(body).withArray("assignments").get(0);
            assignment.put("optionId", assignment.remove("categoryId").stringValue(null));
        }));
        assertInvalid(mutate("categorize", body -> ((ObjectNode) response(body).withArray("assignments").get(0))
                .put("categoryId", "not-a-uuid")));
        assertInvalid(mutate("categorize", body -> response(body).put("kind", "GROUPS")));
        assertInvalid(mutate("categorize", body -> response(body).put("assignments", "x")));
    }

    @Test
    void oversizedAndMalformedBodiesAreInvalid() {
        assertThatThrownBy(() -> AttemptCommand.read(new java.io.ByteArrayInputStream("[]".getBytes())))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> AttemptCommand.read(new java.io.ByteArrayInputStream(new byte[0])))
                .isInstanceOf(InvalidRequestException.class);
    }

    private static ObjectNode submit(String name) {
        return (ObjectNode) fixture("mechanics.json").path("submits").path(name).deepCopy();
    }

    private static ObjectNode mutate(String name, Consumer<ObjectNode> change) {
        ObjectNode body = submit(name);
        change.accept(body);
        return body;
    }

    private static ObjectNode response(ObjectNode body) { return body.withObject("response"); }

    private static AttemptCommand read(JsonNode value) { return AttemptCommand.read(bytes(value)); }

    private static void assertInvalid(JsonNode value) {
        assertThatThrownBy(() -> read(value)).as(value.toString()).isInstanceOf(InvalidRequestException.class);
    }

    private static void drop(tools.jackson.databind.node.ArrayNode array, int... indexes) {
        for (int index : indexes) array.remove(index);
    }
}
