package app.mnema.learning.community;

import app.mnema.learning.library.PublicationMetadata;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The constants of {@code contracts/community/catalog.json} are the constants of the code: the frontend reads the same file, so a change of one without the other fails here. */
class CatalogContractTest {
    private static final JsonNode CONSTANTS = CatalogHttpTest.CONTRACT.path("constants");

    private static List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.propertyNames().forEach(names::add);
        return names;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.stringValue(null)));
        return values;
    }

    @Test
    void theLimitsAreTheContractsLimits() {
        assertThat(CatalogQuery.QUERY_MAX).isEqualTo(CONSTANTS.path("queryMaxLength").intValue());
        assertThat(CatalogQuery.PAGE_DEFAULT).isEqualTo(CONSTANTS.path("pageDefault").intValue());
        assertThat(CatalogQuery.PAGE_MAX).isEqualTo(CONSTANTS.path("pageMax").intValue());
        assertThat(CatalogQueries.SEARCH_CANDIDATES).isEqualTo(CONSTANTS.path("searchCandidates").intValue());
        assertThat(CatalogService.SHELF_MIN).isEqualTo(CONSTANTS.path("shelfMinDecks").intValue());
        assertThat(CatalogService.SHELF_MAX).isEqualTo(CONSTANTS.path("shelfMaxDecks").intValue());
        assertThat(CatalogService.TOPIC_SHELVES_MAX).isEqualTo(CONSTANTS.path("topicShelvesMax").intValue());
        assertThat(EditorialService.MAX).isEqualTo(CONSTANTS.path("editorialMax").intValue());
        assertThat(AudienceCounts.MIN_SHOWN).isEqualTo(CONSTANTS.path("audienceMinShown").intValue());
        assertThat(CatalogQuery.FRESH_DAYS).isEqualTo(CONSTANTS.path("freshDays").intValue());
        assertThat(CatalogService.SITEMAP_MAX_URLS).isEqualTo(50_000);
        assertThat(CatalogQueries.BROWSE_COUNT_CAP).isEqualTo(1_000);
    }

    @Test
    void theVocabulariesAreTheContractsVocabularies() {
        assertThat(CatalogQuery.SORTS).containsExactlyInAnyOrderElementsOf(names(CONSTANTS.path("sorts")));
        assertThat(CatalogQuery.SIZES).containsExactlyInAnyOrderElementsOf(names(CONSTANTS.path("sizes")));
        assertThat(PublicationMetadata.LEVELS).containsExactlyElementsOf(strings(CONSTANTS.path("levels")));
        assertThat(java.util.Arrays.stream(CatalogMedia.values()).map(CatalogMedia::wire).toList()).containsExactlyElementsOf(strings(CONSTANTS.path("media")));
        assertThat(java.util.Arrays.stream(CatalogMechanic.values()).map(CatalogMechanic::wire).toList()).containsExactlyElementsOf(names(CONSTANTS.path("mechanics")));
    }

    @Test
    void theRoutesOfTheContractAreTheRoutesOfTheServer() {
        List<String> paths = new ArrayList<>();
        CatalogHttpTest.CONTRACT.path("routes").forEach(route -> paths.add(route.path("method").stringValue(null) + " " + route.path("path").stringValue(null).replaceAll("\\?.*$", "")));
        assertThat(paths).contains("GET /api/public/community/facets", "GET /api/public/community/shelves", "GET /api/public/community/decks", "GET /api/public/community/authors/{accountId}/decks",
                "GET /api/public/sitemap.xml", "GET /api/admin/community/editorial", "PUT /api/admin/community/editorial");
        assertThat(CONSTANTS.path("basePath").stringValue(null)).isEqualTo("/api/public/community");
    }
}
