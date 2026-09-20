package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.ApiExceptionHandler;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AttemptControllerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID actor = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID presentation = UUID.randomUUID();
    private final AttemptService service = mock(AttemptService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(actor.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new AttemptController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void exactRetryIsPrivateAndPresentationExpiryHasStableProblemCode() throws Exception {
        ObjectNode outcome = JSON.createObjectNode().put("attemptId", UUID.randomUUID().toString())
                .put("presentationId", presentation.toString()).put("mode", "SCHEDULED")
                .put("status", "NOT_ASSESSED");
        outcome.putNull("evidence"); outcome.putObject("feedback").put("result", "NOT_ASSESSED");
        outcome.putNull("transition");
        when(service.submit(eq(actor), eq(deck), eq(session), any()))
                .thenReturn(new AttemptService.SubmitResult(outcome, true));
        mvc.perform(post("/api/decks/" + deck + "/study-sessions/" + session + "/attempts")
                        .contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string("Cache-Control", "private, no-store"));

        when(service.submit(eq(actor), eq(deck), eq(session), any())).thenThrow(new PresentationExpiredException());
        mvc.perform(post("/decks/" + deck + "/study-sessions/" + session + "/attempts")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("PRESENTATION_EXPIRED"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    void malformedRouteAndAuthorityFieldsFailBeforeService() throws Exception {
        mvc.perform(post("/decks/bad/study-sessions/" + session + "/attempts")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest());
        ObjectNode body = (ObjectNode) JSON.readTree(body());
        body.put("mode", "SCHEDULED");
        mvc.perform(post("/decks/" + deck + "/study-sessions/" + session + "/attempts")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private String body() {
        ObjectNode body = JSON.createObjectNode().put("attemptId", UUID.randomUUID().toString())
                .put("presentationId", presentation.toString()).put("nonce", "1234567890123456");
        body.putObject("response").put("kind", "CANCEL"); body.putArray("hintsUsed");
        body.putNull("confidence"); body.put("durationMs", 0);
        return body.toString();
    }
}
