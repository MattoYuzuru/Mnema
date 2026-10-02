package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.deck.DeckInsightsController;
import app.mnema.learning.catalog.deck.DeckInsightsService;
import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP boundary of the Deck hub routes: headers, ETag/replay semantics and the Problem Details with {@code limit}. */
class ItemHubControllerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID actor = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID revision = UUID.randomUUID();
    private final ItemExemplarService exemplars = mock(ItemExemplarService.class);
    private final ItemBulkDeleteService deletions = mock(ItemBulkDeleteService.class);
    private final DeckInsightsService insights = mock(DeckInsightsService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "RS256").subject(actor.toString())
                .claim("zoneinfo", "Asia/Tokyo").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new ItemHubController(exemplars, deletions), new DeckInsightsController(insights))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void exemplarCommandIsPrivateAndMarksReplaysWithoutAnETag() throws Exception {
        ObjectNode acknowledgement = JSON.createObjectNode().put("exemplar", true);
        when(exemplars.set(eq(actor), eq(deck), eq(member), any())).thenReturn(new ItemService.WriteResult(acknowledgement, false));
        mvc.perform(post("/decks/" + deck + "/items/" + member + "/exemplar").contentType(MediaType.APPLICATION_JSON)
                        .content(exemplarBody())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().doesNotExist("ETag")).andExpect(header().doesNotExist("Idempotency-Replayed"));
        when(exemplars.set(eq(actor), eq(deck), eq(member), any())).thenReturn(new ItemService.WriteResult(acknowledgement, true));
        mvc.perform(post("/decks/" + deck + "/items/" + member + "/exemplar").contentType(MediaType.APPLICATION_JSON)
                        .content(exemplarBody())).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));
    }

    @Test
    void limitReachedIsA422ProblemWithTheNumericLimit() throws Exception {
        when(exemplars.set(eq(actor), eq(deck), eq(member), any())).thenThrow(new ExemplarLimitReachedException());
        mvc.perform(post("/decks/" + deck + "/items/" + member + "/exemplar").contentType(MediaType.APPLICATION_JSON)
                        .content(exemplarBody())).andExpect(status().isUnprocessableContent())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value("EXEMPLAR_LIMIT_REACHED")).andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.type").value("urn:mnema:problem:exemplar-limit-reached"))
                .andExpect(jsonPath("$.title").value("Exemplar limit reached")).andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("The deck already has the maximum number of exemplars."))
                .andExpect(jsonPath("$.instance").value("/decks/" + deck + "/items/" + member + "/exemplar"));
    }

    @Test
    void exemplarBodiesAreStrict() throws Exception {
        mvc.perform(post("/decks/" + deck + "/items/" + member + "/exemplar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"" + UUID.randomUUID() + "\",\"exemplar\":true}"))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(post("/decks/" + deck + "/items/bad/exemplar").contentType(MediaType.APPLICATION_JSON).content(exemplarBody()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(exemplars);
    }

    @Test
    void bulkDeleteReturnsTheFreshETagOnlyAndPartialIsStillA200() throws Exception {
        ObjectNode partial = JSON.createObjectNode().put("status", "PARTIAL").put("deckVersion", "8");
        when(deletions.delete(eq(actor), eq(deck), eq(7L), any())).thenReturn(new ItemService.WriteResult(partial, false));
        mvc.perform(post("/decks/" + deck + "/items/deletions").header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON).content(deleteBody()))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"8\""))
                .andExpect(jsonPath("$.status").value("PARTIAL"));
        when(deletions.delete(eq(actor), eq(deck), eq(7L), any())).thenReturn(new ItemService.WriteResult(partial, true));
        mvc.perform(post("/decks/" + deck + "/items/deletions").header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON).content(deleteBody()))
                .andExpect(header().doesNotExist("ETag")).andExpect(header().string("Idempotency-Replayed", "true"));
        mvc.perform(post("/decks/" + deck + "/items/deletions").contentType(MediaType.APPLICATION_JSON).content(deleteBody()))
                .andExpect(status().isPreconditionRequired());
        when(deletions.delete(eq(actor), eq(deck), eq(9L), any())).thenThrow(new VersionConflictException());
        mvc.perform(post("/decks/" + deck + "/items/deletions").header("If-Match", "\"9\"")
                        .contentType(MediaType.APPLICATION_JSON).content(deleteBody()))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void tooLargeSelectionIsA422ProblemAndPreviewIsPrivate() throws Exception {
        when(deletions.delete(eq(actor), eq(deck), eq(7L), any())).thenThrow(new BulkSelectionTooLargeException());
        mvc.perform(post("/decks/" + deck + "/items/deletions").header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON).content(deleteBody()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("BULK_SELECTION_TOO_LARGE"))
                .andExpect(jsonPath("$.limit").value(500)).andExpect(jsonPath("$.title").value("Bulk selection too large"));
        when(deletions.preview(eq(actor), eq(deck), any())).thenReturn(JSON.createObjectNode().put("materialCount", 1));
        mvc.perform(post("/decks/" + deck + "/items/deletions/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedDeckRevisionId\":\"" + revision + "\",\"allInDeck\":true}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.materialCount").value(1));
    }

    @Test
    void insightsArePrivateTakeTheAccountZoneAndNoParameters() throws Exception {
        when(insights.read(actor, deck, "Asia/Tokyo")).thenReturn(JSON.createObjectNode().put("timezone", "Asia/Tokyo"));
        mvc.perform(get("/decks/" + deck + "/insights")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.timezone").value("Asia/Tokyo"));
        mvc.perform(get("/decks/" + deck + "/insights").param("zone", "UTC")).andExpect(status().isBadRequest());
        mvc.perform(get("/decks/nope/insights")).andExpect(status().isBadRequest());
    }

    @Test
    void problemExtensionsAreClosedAndNeverOverrideStandardMembers() {
        org.assertj.core.api.Assertions.assertThat(ProblemExtension.none().members()).isEmpty();
        for (String name : new String[] {"code", "type", "title", "status", "detail", "instance", "Bad", "x-y", ""}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProblemExtension(Map.of(name, 1L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProblemExtension(Map.of("limit", 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThat(new ProblemExtension(Map.of("flag", true)).members()).containsEntry("flag", true);
    }

    private String exemplarBody() {
        return "{\"commandId\":\"" + UUID.randomUUID() + "\",\"expectedItemRevisionId\":\"" + revision + "\",\"exemplar\":true}";
    }

    private String deleteBody() {
        return "{\"commandId\":\"" + UUID.randomUUID() + "\",\"expectedDeckRevisionId\":\"" + revision + "\",\"allInDeck\":true}";
    }
}
