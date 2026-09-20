package app.mnema.learning.catalog.exercise;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.platform.api.ResourceNotFoundException;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExerciseControllerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID actor = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final UUID exercise = UUID.randomUUID();
    private final UUID exerciseRevision = UUID.randomUUID();
    private final ExerciseService service = mock(ExerciseService.class);
    private MockMvc mvc;
    private ObjectNode acknowledgement;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(actor.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new ExerciseController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        acknowledgement = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("deckId", deck.toString()).put("deckRevisionId", UUID.randomUUID().toString())
                .put("deckVersion", "2").put("objectiveId", UUID.randomUUID().toString())
                .put("objectiveKey", UUID.randomUUID().toString()).put("objectiveRevisionId", UUID.randomUUID().toString())
                .put("exerciseId", exercise.toString()).put("exerciseRevisionId", exerciseRevision.toString())
                .put("enabled", true);
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void createUpdateListAndReadExposePrivateValidatorsAndLocations() throws Exception {
        when(service.publish(eq(actor), eq(deck), eq(null), eq(1L), any()))
                .thenReturn(new ExerciseService.WriteResult(acknowledgement, false));
        mvc.perform(post("/api/decks/" + deck + "/exercises").contextPath("/api").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(ExerciseCommandTest.valid("TYPED").toString()))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(header().string("Location", "/api/decks/" + deck + "/exercises/" + exercise))
                .andExpect(header().string("Cache-Control", "private, no-store"));

        ObjectNode update = ExerciseCommandTest.valid("TYPED")
                .put("expectedExerciseRevisionId", exerciseRevision.toString());
        when(service.publish(eq(actor), eq(deck), eq(exercise), eq(2L), any()))
                .thenReturn(new ExerciseService.WriteResult(acknowledgement, true));
        mvc.perform(put("/decks/" + deck + "/exercises/" + exercise).header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content(update.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().doesNotExist("ETag"));

        ObjectNode page = JSON.createObjectNode().put("deckVersion", "2"); page.putArray("exercises");
        UUID member = UUID.randomUUID();
        when(service.list(actor, deck, member, "20", "cursor")).thenReturn(page);
        mvc.perform(get("/decks/" + deck + "/exercises").param("memberKey", member.toString())
                        .param("limit", "20").param("cursor", "cursor"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
        when(service.read(actor, deck, exercise, exerciseRevision)).thenReturn(acknowledgement);
        mvc.perform(get("/decks/" + deck + "/exercises/" + exercise)
                        .param("revisionId", exerciseRevision.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    void malformedIdsParametersAndMissingPreconditionsFailBeforeService() throws Exception {
        mvc.perform(post("/decks/" + deck + "/exercises").contentType(MediaType.APPLICATION_JSON)
                        .content(ExerciseCommandTest.valid("TYPED").toString()))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(get("/decks/bad/exercises")).andExpect(status().isBadRequest());
        mvc.perform(get("/decks/" + deck + "/exercises").param("memberKey", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/decks/" + deck + "/exercises/" + exercise)
                        .param("revisionId", exerciseRevision.toString(), exerciseRevision.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        when(service.read(actor, deck, exercise, null)).thenThrow(new ResourceNotFoundException());
        mvc.perform(get("/decks/" + deck + "/exercises/" + exercise)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
