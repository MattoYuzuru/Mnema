package app.mnema.learning.catalog;

import app.mnema.learning.catalog.item.ItemHubContractProbe;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code contracts/decks/hub.json} is executable: examples are read by the production parsers and its invariants hold. */
class DeckHubContractFixtureTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode HUB = hub();
    private static final List<String> MECHANICS =
            List.of("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER", "CATEGORIZE");

    @Test
    void insightsExamplesSatisfyTheDocumentedInvariants() {
        for (String name : List.of("response", "emptyDeck")) {
            JsonNode insights = HUB.path("insights").path(name);
            JsonNode coverage = insights.path("coverage");
            int total = coverage.path("total").intValue();
            assertThat(coverage.path("withExercises").intValue() + coverage.path("withoutExercises").intValue()).isEqualTo(total);
            assertThat(names(insights.path("states"))).containsExactlyElementsOf(HUB.path("constants").path("states").valueStream()
                    .map(JsonNode::stringValue).toList());
            assertThat(insights.path("states").valueStream().mapToInt(JsonNode::intValue).sum()).isEqualTo(total);
            assertThat(names(insights.path("exercisesByMechanic"))).containsExactlyElementsOf(MECHANICS);
            JsonNode days = insights.path("dueByDay");
            assertThat(days).hasSize(7);
            for (int day = 0; day < 7; day++) {
                assertThat(LocalDate.parse(days.get(day).path("date").stringValue(null)))
                        .isEqualTo(LocalDate.parse(days.get(0).path("date").stringValue(null)).plusDays(day));
            }
            assertThat(days.valueStream().mapToInt(value -> value.path("materials").intValue()).sum()).isLessThanOrEqualTo(total);
            assertThat(insights.path("timezone").stringValue(null)).isEqualTo(HUB.path("constants").path("defaultTimezone").stringValue(null));
        }
        assertThat(HUB.path("constants").path("mechanics").valueStream().map(JsonNode::stringValue).toList()).isEqualTo(MECHANICS);
        assertThat(HUB.path("insights").path("widgetActions").size()).isEqualTo(5);
    }

    @Test
    void sortedPageIsOrderedByCountThenOrdinalAndCarriesEveryField() {
        JsonNode page = HUB.path("items").path("sortedPage");
        int count = -1;
        int ordinal = -1;
        for (JsonNode item : page.path("items")) {
            assertThat(item.path("exerciseCount").intValue()).isGreaterThanOrEqualTo(count);
            if (item.path("exerciseCount").intValue() == count) assertThat(item.path("ordinal").intValue()).isGreaterThan(ordinal);
            count = item.path("exerciseCount").intValue();
            ordinal = item.path("ordinal").intValue();
            assertThat(names(item)).contains("memberKey", "itemRevisionId", "itemVersion", "ordinal", "title", "exerciseCount", "exemplar");
        }
        assertThat(page.path("exemplars").path("limit").intValue()).isEqualTo(HUB.path("constants").path("exemplarLimit").intValue());
        long marked = page.path("items").valueStream().filter(item -> item.path("exemplar").booleanValue()).count();
        assertThat(marked).isLessThanOrEqualTo(page.path("exemplars").path("count").longValue());
        assertThat(page.path("nextCursor").stringValue(null)).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void commandsAndResultsAreAcceptedByTheProductionParsersAndAreConsistent() {
        ItemHubContractProbe.accept(HUB);
        JsonNode exemplar = HUB.path("exemplar");
        assertThat(exemplar.path("acknowledgement").path("exemplarCount").intValue())
                .isLessThanOrEqualTo(HUB.path("constants").path("exemplarLimit").intValue());
        assertThat(exemplar.path("limitReached").path("limit").intValue()).isEqualTo(HUB.path("constants").path("exemplarLimit").intValue());
        JsonNode bulk = HUB.path("bulkDelete");
        for (String name : List.of("completed", "partial")) {
            JsonNode result = bulk.path(name);
            assertThat(result.path("requested").intValue())
                    .isEqualTo(result.path("deleted").intValue() + result.path("notDeleted").size());
            assertThat(result.path("status").stringValue(null)).isEqualTo(result.path("notDeleted").isEmpty() ? "COMPLETED" : "PARTIAL");
        }
        assertThat(bulk.path("partial").path("deleted").intValue()).isPositive();
        assertThat(bulk.path("partial").path("requested").intValue())
                .isGreaterThan(HUB.path("constants").path("bulkDeleteChunkSize").intValue());
        assertThat(bulk.path("selectionTooLarge").path("limit").intValue())
                .isEqualTo(HUB.path("constants").path("bulkDeleteMaxSelection").intValue());
        Set<String> codes = new HashSet<>();
        for (String path : List.of("/exemplar/limitReached", "/exemplar/staleItem", "/bulkDelete/staleDeck", "/bulkDelete/selectionTooLarge",
                "/insights/notFound")) {
            JsonNode problem = HUB.at(path);
            assertThat(names(problem)).contains("type", "title", "status", "detail", "instance", "code");
            assertThat(problem.path("type").stringValue(null))
                    .isEqualTo("urn:mnema:problem:" + problem.path("code").stringValue(null).toLowerCase().replace('_', '-'));
            codes.add(problem.path("code").stringValue(null));
        }
        assertThat(codes).contains("EXEMPLAR_LIMIT_REACHED", "BULK_SELECTION_TOO_LARGE", "VERSION_CONFLICT");
    }

    private static List<String> names(JsonNode object) {
        return StreamSupport.stream(object.propertyNames().spliterator(), false).toList();
    }

    private static JsonNode hub() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/decks/hub.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/decks/hub.json")));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
