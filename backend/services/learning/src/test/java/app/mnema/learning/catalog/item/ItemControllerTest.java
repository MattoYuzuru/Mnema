package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ItemControllerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID actor = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final UUID deckRevision = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID itemRevision = UUID.randomUUID();
    private final ItemService service = mock(ItemService.class);
    private MockMvc mvc;
    private ObjectNode acknowledgement;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "RS256").subject(actor.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new ItemController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        acknowledgement = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("deckId", deck.toString()).put("deckRevisionId", UUID.randomUUID().toString())
                .put("deckVersion", "1").put("memberCount", 1);
        acknowledgement.putArray("changes").addObject().put("operation", "create")
                .put("memberKey", member.toString()).put("itemRevisionId", itemRevision.toString())
                .put("itemVersion", "0").put("ordinal", 0);
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void createSaveAndBulkExposeFreshDeckValidatorAndReplayBoundary() throws Exception {
        when(service.publish(eq(actor), eq(deck), eq(0L), any()))
                .thenReturn(new ItemService.WriteResult(acknowledgement, false));
        mvc.perform(post("/api/decks/" + deck + "/items").contextPath("/api")
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Location", "/api/decks/" + deck + "/items/" + member));
        when(service.publish(eq(actor), eq(deck), eq(1L), any()))
                .thenReturn(new ItemService.WriteResult(acknowledgement, true));
        mvc.perform(put("/decks/" + deck + "/items/" + member).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(saveBody()))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Idempotency-Replayed", "true"));
        mvc.perform(post("/decks/" + deck + "/items/publications").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(bulkBody()))
                .andExpect(status().isOk()).andExpect(content().json(acknowledgement.toString()));
    }

    @Test
    void listAndExactRevisionReadReturnPrivateBoundedRepresentations() throws Exception {
        ObjectNode page = JSON.createObjectNode().put("deckId", deck.toString())
                .put("deckRevisionId", deckRevision.toString()).put("deckVersion", "2").put("total", 0);
        page.putArray("items"); page.putNull("nextCursor");
        when(service.list(actor, deck, "20", "cursor")).thenReturn(page);
        mvc.perform(get("/decks/" + deck + "/items").param("limit", "20").param("cursor", "cursor"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
        ObjectNode detail = JSON.createObjectNode().put("deckVersion", "2").put("memberKey", member.toString());
        when(service.read(actor, deck, member, itemRevision)).thenReturn(detail);
        mvc.perform(get("/decks/" + deck + "/items/" + member).param("revisionId", itemRevision.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    void strictInputsAndOpaqueNotFoundUseStableProblemDetails() throws Exception {
        mvc.perform(post("/decks/" + deck + "/items").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(post("/decks/" + deck + "/items").header("If-Match", "\"0\"", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isBadRequest());
        ObjectNode missingItemRevision = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckRevision.toString());
        missingItemRevision.set("document", document());
        mvc.perform(put("/decks/" + deck + "/items/" + member).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(missingItemRevision.toString()))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(get("/decks/bad/items")).andExpect(status().isBadRequest());
        mvc.perform(get("/decks/" + deck + "/items/" + member).param("revisionId", itemRevision.toString(), itemRevision.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        when(service.read(actor, deck, member, null)).thenThrow(new ResourceNotFoundException());
        mvc.perform(get("/decks/" + deck + "/items/" + member)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    private String createBody() {
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckRevision.toString());
        body.set("document", document());
        return body.toString();
    }

    private String saveBody() {
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckRevision.toString())
                .put("expectedItemRevisionId", itemRevision.toString());
        body.set("document", document());
        return body.toString();
    }

    private String bulkBody() {
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckRevision.toString());
        body.putArray("changes").addObject().put("operation", "reorder").put("memberKey", member.toString())
                .put("expectedItemRevisionId", itemRevision.toString()).put("ordinal", 0);
        return body.toString();
    }

    private ObjectNode document() {
        ObjectNode root = JSON.createObjectNode().put("id", UUID.randomUUID().toString()).put("type", "doc").put("version", 1);
        root.putObject("attrs");
        ObjectNode paragraph = root.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs"); paragraph.putArray("content");
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1); document.set("root", root);
        return document;
    }
}
