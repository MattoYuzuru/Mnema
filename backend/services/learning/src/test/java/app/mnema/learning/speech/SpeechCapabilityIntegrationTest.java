package app.mnema.learning.speech;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** The flag {@code learning.features.speech-to-text.enabled} off (the default): admission and the consent disclosure refuse with {@code CAPABILITY_UNAVAILABLE}, withdrawing and deleting stay open. */
@SpringBootTest(properties = {"learning.runtime.roles=all", "learning.ai.provider=stub", "spring.datasource.hikari.maximum-pool-size=4"})
class SpeechCapabilityIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private SpeechInputController controller;

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    private MockHttpServletResponse send(UUID owner, MockHttpServletRequestBuilder request) throws Exception {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        return mvc.perform(request).andReturn().getResponse();
    }

    @Test
    void aDisabledCapabilityRefusesAdmissionAndTheDisclosureButNotWithdrawalOrDeletion() throws Exception {
        UUID owner = UUID.randomUUID();

        MockHttpServletResponse input = send(owner, post("/speech-inputs").queryParam("purpose", "COMPOSER").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Audio-Duration-Ms", "2000").contentType("audio/ogg").content("clip".getBytes(StandardCharsets.UTF_8)));
        assertThat(input.getStatus()).isEqualTo(409);
        JsonNode problem = JSON.readTree(input.getContentAsString());
        assertThat(problem.path("code").stringValue(null)).isEqualTo("CAPABILITY_UNAVAILABLE");
        assertThat(problem.path("capability").stringValue(null)).isEqualTo("speechToText");
        assertThat(problem.path("reason").stringValue(null)).isEqualTo("DISABLED");

        assertThat(send(owner, get("/speech-consent")).getStatus()).isEqualTo(409);
        assertThat(send(owner, put("/speech-consent").contentType("application/json").content("{\"version\":\"speech-2026-10-2\",\"processing\":\"RU\"}")).getStatus())
                .isEqualTo(409);
        assertThat(send(owner, delete("/speech-consent")).getStatus()).isEqualTo(204);
        assertThat(send(owner, delete("/speech-inputs/" + UUID.randomUUID())).getStatus()).isEqualTo(204);
        assertThat(send(owner, get("/speech-inputs/" + UUID.randomUUID())).getStatus()).isEqualTo(404);
    }
}
