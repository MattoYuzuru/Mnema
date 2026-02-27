package app.mnema.ai.service;

import app.mnema.ai.client.core.CoreApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CardNoveltyServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void filterCandidatesDropsExactPrimarySemanticAndEmpty() {
        CoreApiClient coreApiClient = mock(CoreApiClient.class);
        CardNoveltyService noveltyService = new CardNoveltyService(coreApiClient);
        UUID deckId = UUID.randomUUID();
        String token = "token";
        List<String> fields = List.of("front", "back");

        String sharedBack = "Mitochondria is the powerhouse of the cell and drives ATP synthesis in aerobic respiration.";
        CoreApiClient.CoreUserCardResponse existing = new CoreApiClient.CoreUserCardResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                false,
                card("alpha", sharedBack)
        );
        when(coreApiClient.getUserCards(eq(deckId), eq(1), eq(200), eq(token)))
                .thenReturn(new CoreApiClient.CoreUserCardPage(List.of(existing)));

        CardNoveltyService.NoveltyIndex index = noveltyService.buildIndex(deckId, token, fields);
        List<ObjectNode> candidates = List.of(
                card("alpha", sharedBack), // exact
                card("alpha", "A different explanation"), // same primary
                card("beta", sharedBack), // semantic-near
                card("", ""), // empty
                card("gamma", "Completely new card")
        );

        CardNoveltyService.FilterResult<ObjectNode> filtered = noveltyService.filterCandidates(
                candidates,
                node -> node,
                fields,
                index,
                10
        );

        assertEquals(1, filtered.accepted().size());
        assertEquals("gamma", filtered.accepted().getFirst().path("front").asText());
        assertEquals(1, filtered.droppedExact());
        assertEquals(1, filtered.droppedPrimary());
        assertEquals(1, filtered.droppedSemantic());
        assertEquals(1, filtered.droppedEmpty());
    }

    @Test
    void filterCandidatesUsesAcceptedCardsAsImmediateIndex() {
        CardNoveltyService noveltyService = new CardNoveltyService(mock(CoreApiClient.class));
        List<String> fields = List.of("front", "back");
        CardNoveltyService.NoveltyIndex index = new CardNoveltyService.NoveltyIndex();

        List<ObjectNode> candidates = List.of(
                card("term", "definition"),
                card("term", "definition"),
                card("term-2", "definition-2")
        );

        CardNoveltyService.FilterResult<ObjectNode> filtered = noveltyService.filterCandidates(
                candidates,
                node -> node,
                fields,
                index,
                2
        );

        assertEquals(2, filtered.accepted().size());
        assertEquals(1, filtered.droppedExact());
        assertEquals(0, filtered.droppedPrimary());
        assertEquals(0, filtered.droppedSemantic());
        assertEquals(0, filtered.droppedEmpty());
    }

    private static ObjectNode card(String front, String back) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("front", front);
        node.put("back", back);
        return node;
    }
}
