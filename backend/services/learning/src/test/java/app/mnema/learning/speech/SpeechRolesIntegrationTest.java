package app.mnema.learning.speech;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
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

/**
 * {@code learning.runtime.roles=api}: HTTP admits and serves speech inputs, the worker that claims and transcribes them does not exist, so an input stays
 * QUEUED here (another process, or the sweeper of a {@code worker} process, takes it). With the capability flag off the endpoints refuse with the
 * contract's {@code CAPABILITY_UNAVAILABLE}.
 */
@SpringBootTest(properties = {"learning.runtime.roles=api", "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true",
        "learning.usage.entitlements.default-plan=PLUS", "spring.datasource.hikari.maximum-pool-size=4"})
class SpeechRolesIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private ApplicationContext context;
    @Autowired private SpeechInputController controller;
    @Autowired private JdbcClient jdbc;

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
    void anApiProcessAdmitsAnInputButNeverTranscribesIt() throws Exception {
        assertThat(context.getBeanNamesForType(SpeechInputWorker.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SpeechInputController.class)).hasSize(1);
        UUID owner = UUID.randomUUID();
        assertThat(send(owner, put("/speech-consent").contentType("application/json").content("{\"version\":\"speech-2026-10\",\"processing\":\"RU\"}")).getStatus())
                .isEqualTo(200);

        MockHttpServletResponse response = send(owner, post("/speech-inputs").queryParam("purpose", "COMPOSER").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Audio-Duration-Ms", "2000").contentType("audio/webm;codecs=opus").content("clip".getBytes(StandardCharsets.UTF_8)));

        assertThat(response.getStatus()).isEqualTo(202);
        UUID id = UUID.fromString(JSON.readTree(response.getContentAsString()).path("speechInputId").stringValue(null));
        Thread.sleep(600);
        JsonNode polled = JSON.readTree(send(owner, get("/speech-inputs/" + id)).getContentAsString());
        assertThat(polled.path("state").stringValue(null)).isEqualTo("QUEUED");
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.speech_input_audio WHERE speech_input_id=:id").param("id", id).query(Integer.class).single()).isEqualTo(1);
        // the owner can still take it back
        assertThat(send(owner, delete("/speech-inputs/" + id)).getStatus()).isEqualTo(204);
    }
}
