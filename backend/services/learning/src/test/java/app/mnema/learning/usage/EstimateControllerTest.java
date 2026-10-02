package app.mnema.learning.usage;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The controller reads the bounded body before any transaction starts and hands raw bytes to the service. */
class EstimateControllerTest {
    private final UUID owner = UUID.randomUUID();
    private final EstimateService service = mock(EstimateService.class);
    private MockMvc mvc;

    private void signIn() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new EstimateController(service)).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theBodyIsFullyReadBeforeTheServiceIsCalled() throws Exception {
        signIn();
        UUID deck = UUID.randomUUID();
        byte[] body = "{\"spec\":{\"kind\":\"MATERIALS\"}}".getBytes(StandardCharsets.UTF_8);
        // A client that sends its body slowly is represented by a service that sees whether the stream was consumed.
        ArgumentCaptor<byte[]> raw = ArgumentCaptor.forClass(byte[].class);
        when(service.estimate(eq(owner), eq(deck), raw.capture())).thenReturn(null);

        mvc.perform(post("/decks/" + deck + "/generation-estimates").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(service).estimate(eq(owner), eq(deck), any(byte[].class));
        assertThat(raw.getValue()).isEqualTo(body);
    }

    @Test
    void anOversizedBodyIsCutOffAtTheLimitPlusOneByteForTheServiceToRefuse() throws Exception {
        signIn();
        UUID deck = UUID.randomUUID();
        ArgumentCaptor<byte[]> raw = ArgumentCaptor.forClass(byte[].class);
        when(service.estimate(eq(owner), eq(deck), raw.capture())).thenReturn(null);

        mvc.perform(post("/decks/" + deck + "/generation-estimates").contentType(MediaType.APPLICATION_JSON)
                .content(new byte[EstimateService.MAX_BODY_BYTES * 2])).andExpect(status().isOk());

        assertThat(raw.getValue()).hasSize(EstimateService.MAX_BODY_BYTES + 1);
    }

    @Test
    void aBadDeckIdIsRefusedBeforeTheServiceAndTheBody() throws Exception {
        signIn();
        mvc.perform(post("/decks/nope/generation-estimates").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
