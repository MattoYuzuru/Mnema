package app.mnema.learning.catalog.deck;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC wire/error tests only; this deliberately does not claim real Identity authorization. */
class DeckControllerTest {
    private final UUID actor = UUID.randomUUID();
    private final DeckService service = mock(DeckService.class);
    private MockMvc mvc;
    private JsonNode fixture;
    private ObjectNode acknowledgement;
    private UUID deck;

    @BeforeEach
    void setUp() throws Exception {
        fixture = JsonMapper.builder().build().readTree(Files.readString(Path.of("../../../contracts/decks/metadata.json")));
        deck = UUID.fromString(fixture.path("detail").path("deckId").textValue());
        acknowledgement = JsonMapper.builder().build().createObjectNode();
        acknowledgement.set("commandId", fixture.path("command").path("commandId"));
        acknowledgement.set("deck", fixture.path("detail"));
        Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "RS256").subject(actor.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new DeckController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void createAndDetailUseExactFixturePrivateHeadersAndFreshValidator() throws Exception {
        when(service.create(eq(actor), any())).thenReturn(new DeckService.WriteResult(acknowledgement, false));
        mvc.perform(post("/api/decks").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content(fixture.path("command").toString()))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/decks/" + deck))
                .andExpect(header().string("ETag", "\"0\"")).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(content().json(acknowledgement.toString()));
        when(service.read(actor, deck)).thenReturn((ObjectNode) fixture.path("detail"));
        mvc.perform(get("/api/decks/" + deck).contextPath("/api"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(content().json(fixture.path("detail").toString()));
    }

    @Test
    void patchAndBothReplaysExposeAcknowledgementWithoutStaleValidator() throws Exception {
        when(service.save(eq(actor), eq(deck), eq(0L), any())).thenReturn(new DeckService.WriteResult(acknowledgement, false));
        mvc.perform(patch("/decks/" + deck).contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"0\"")
                        .content(fixture.path("command").toString()))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""));
        when(service.save(eq(actor), eq(deck), eq(0L), any())).thenReturn(new DeckService.WriteResult(acknowledgement, true));
        mvc.perform(patch("/decks/" + deck).contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"0\"")
                        .content(fixture.path("command").toString()))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Idempotency-Replayed", "true"));
        when(service.create(eq(actor), any())).thenReturn(new DeckService.WriteResult(acknowledgement, true));
        mvc.perform(post("/decks").contentType(MediaType.APPLICATION_JSON).content(fixture.path("command").toString()))
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Idempotency-Replayed", "true"));
    }

    @Test
    void validatesBeforeServiceAndUsesStableSafeProblemSchema() throws Exception {
        mvc.perform(post("/decks").contentType(MediaType.APPLICATION_JSON).content("{\"private\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(patch("/decks/" + deck).contentType(MediaType.APPLICATION_JSON).content(fixture.path("command").toString()))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(patch("/decks/" + deck).contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"0\"", "\"0\"")
                        .content(fixture.path("command").toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/decks/bad-id")).andExpect(status().isBadRequest());
        mvc.perform(get("/decks").param("limit", "1", "2")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        when(service.read(actor, deck)).thenThrow(new ResourceNotFoundException());
        mvc.perform(get("/api/decks/" + deck).contextPath("/api"))
                .andExpect(status().isNotFound()).andExpect(content().json(fixture.path("notFound").toString()));
        when(service.save(eq(actor), eq(deck), eq(0L), any())).thenThrow(new VersionConflictException());
        mvc.perform(patch("/decks/" + deck).contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"0\"")
                        .content(fixture.path("command").toString()))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        when(service.create(eq(actor), any())).thenThrow(new IdempotencyConflictException());
        mvc.perform(post("/decks").contentType(MediaType.APPLICATION_JSON).content(fixture.path("command").toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void listBindsOnlyAuthenticatedOwnerAndBoundedPaginationInputs() throws Exception {
        ObjectNode page = JsonMapper.builder().build().createObjectNode().putNull("nextCursor");
        page.putArray("items");
        when(service.list(actor, null, null)).thenReturn(page);
        mvc.perform(get("/decks")).andExpect(status().isOk()).andExpect(content().json(page.toString()));
        when(service.list(actor, "20", "opaque")).thenReturn(page);
        mvc.perform(get("/decks").param("limit", "20").param("cursor", "opaque"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"));
        verify(service).list(actor, "20", "opaque");
    }
}
