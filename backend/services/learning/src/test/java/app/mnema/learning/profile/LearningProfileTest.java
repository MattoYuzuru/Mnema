package app.mnema.learning.profile;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** {@code GET/PUT /api/learning-profile}: an owner-scoped, replaceable answer; a skip is an answer without a goal. */
@SpringBootTest
class LearningProfileTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private LearningProfileController controller;

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc as(UUID owner) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }

    private MockHttpServletResponse submit(UUID owner, String json) throws Exception {
        return as(owner).perform(put("/learning-profile").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse();
    }

    @Test
    void anUnansweredOwnerHasNoGoalAndNoAnswerTime() throws Exception {
        var response = as(UUID.randomUUID()).perform(get("/learning-profile")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(body(response).path("goal").isNull()).isTrue();
        assertThat(body(response).path("skipped").booleanValue()).isFalse();
        assertThat(body(response).path("answeredAt").isNull()).isTrue();
    }

    @Test
    void aGoalIsStoredReadBackAndReplaceable() throws Exception {
        UUID owner = UUID.randomUUID();

        var first = submit(owner, "{\"goal\":\"EXAMS\"}");
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(body(first).path("goal").stringValue(null)).isEqualTo("EXAMS");
        assertThat(body(first).path("answeredAt").isString()).isTrue();

        submit(owner, "{\"goal\":\"WORK\"}");
        var read = as(owner).perform(get("/learning-profile")).andReturn().getResponse();
        assertThat(body(read).path("goal").stringValue(null)).isEqualTo("WORK");
    }

    @Test
    void aSkipIsAnAnswerWithoutAGoal() throws Exception {
        UUID owner = UUID.randomUUID();

        var skip = submit(owner, "{\"goal\":null,\"skipped\":true}");

        assertThat(skip.getStatus()).isEqualTo(200);
        assertThat(body(skip).path("goal").isNull()).isTrue();
        assertThat(body(skip).path("skipped").booleanValue()).isTrue();
        assertThat(body(skip).path("answeredAt").isString()).isTrue();
    }

    @Test
    void theAnswerIsPerOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        submit(owner, "{\"goal\":\"SELF\"}");

        var other = as(UUID.randomUUID()).perform(get("/learning-profile")).andReturn().getResponse();

        assertThat(body(other).path("goal").isNull()).isTrue();
        assertThat(body(other).path("answeredAt").isNull()).isTrue();
    }

    @Test
    void malformedAnswersAreInvalidRequests() throws Exception {
        UUID owner = UUID.randomUUID();
        for (String json : new String[] {"{}", "[]", "{\"goal\":\"FAME\"}", "{\"goal\":\"exams\"}", "{\"goal\":1}",
                "{\"goal\":null}", "{\"goal\":null,\"skipped\":false}", "{\"goal\":null,\"skipped\":\"true\"}",
                "{\"goal\":\"EXAMS\",\"skipped\":true}", "{\"goal\":\"EXAMS\",\"x\":1}", "{\"goal\":\"EXAMS\",\"goal\":\"WORK\"}",
                "not json", "{\"goal\":\"" + "A".repeat(300) + "\"}"}) {
            var response = submit(owner, json);
            assertThat(response.getStatus()).as(json).isEqualTo(400);
            assertThat(body(response).path("code").stringValue(null)).as(json).isEqualTo("INVALID_REQUEST");
        }
        var read = as(owner).perform(get("/learning-profile")).andReturn().getResponse();
        assertThat(body(read).path("answeredAt").isNull()).isTrue();
    }
}
