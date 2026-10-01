package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import com.fasterxml.jackson.databind.json.JsonMapper;
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

class PairCheckControllerTest {
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
        mvc = MockMvcBuilders.standaloneSetup(new PairCheckController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void acknowledgesOnlyThePairResultWithPrivateCaching() throws Exception {
        when(service.checkPair(eq(actor), eq(deck), eq(session), any())).thenReturn(true);
        mvc.perform(post("/api/decks/" + deck + "/study-sessions/" + session + "/pair-checks")
                        .contextPath("/api").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.correct").value(true))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        when(service.checkPair(eq(actor), eq(deck), eq(session), any())).thenThrow(new PresentationExpiredException());
        mvc.perform(post("/decks/" + deck + "/study-sessions/" + session + "/pair-checks")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("PRESENTATION_EXPIRED"));
    }

    @Test
    void rejectsMalformedIdsUnknownAuthorityAndInvalidEnvelopes() throws Exception {
        mvc.perform(post("/decks/bad/study-sessions/" + session + "/pair-checks")
                        .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());
        for (String body : new String[] { body().replace("leftId", "correctLeft"), body().replace("1234567890123456", "short"),
                body().replace(presentation.toString(), "bad"), "[]", "{", " ".repeat(1025) }) {
            mvc.perform(post("/decks/" + deck + "/study-sessions/" + session + "/pair-checks")
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    private String body() {
        return JSON.createObjectNode().put("presentationId", presentation.toString()).put("nonce", "1234567890123456")
                .put("leftId", UUID.randomUUID().toString()).put("rightId", UUID.randomUUID().toString()).toString();
    }
}
