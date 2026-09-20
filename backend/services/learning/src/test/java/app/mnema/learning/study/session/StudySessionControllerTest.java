package app.mnema.learning.study.session;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudySessionControllerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID actor = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final StudySessionService service = mock(StudySessionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(actor.toString())
                .claim("zoneinfo", "Europe/Moscow").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new StudySessionController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void startReadAndResumeUsePrivateBoundedResources() throws Exception {
        ObjectNode preparing = JSON.createObjectNode().put("sessionId", session.toString())
                .put("mode", "SCHEDULED").put("status", "PREPARING").put("statusUrl", "/status");
        when(service.start(eq(actor), eq(deck), eq("Europe/Moscow"), any()))
                .thenReturn(new StudySessionService.StartResult(preparing, false, true));
        String body = "{\"commandId\":\"" + UUID.randomUUID()
                + "\",\"mode\":\"SCHEDULED\",\"budget\":{\"maxPresentations\":20}}";

        mvc.perform(post("/api/decks/" + deck + "/study-sessions").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Location", "/api/decks/" + deck + "/study-sessions/" + session));

        ObjectNode active = preparing.deepCopy().put("status", "ACTIVE");
        when(service.read(actor, deck, session)).thenReturn(active);
        when(service.presentations(actor, deck, session)).thenReturn(active);
        when(service.replaySources(actor, deck, "Europe/Moscow")).thenReturn(JSON.createObjectNode()
                .put("asOf", "2026-09-20T10:00:00Z").put("localStudyDate", "2026-09-20")
                .set("items", JSON.createArrayNode()));
        mvc.perform(get("/decks/" + deck + "/study-sessions/" + session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get("/decks/" + deck + "/study-sessions/replay-sources"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(post("/decks/" + deck + "/study-sessions/" + session + "/presentations"))
                .andExpect(status().isOk());
    }

    @Test
    void malformedIdentifiersAndBodiesFailBeforeTheService() throws Exception {
        mvc.perform(get("/decks/bad/study-sessions/" + session)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/decks/" + deck + "/study-sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
