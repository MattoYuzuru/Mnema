package app.mnema.identityaccount.contract;

import app.mnema.identityaccount.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the exact RFC 9457 bodies and statuses of the Identity API, for both the security-filter
 * writer and the MVC exception advice, so a JSON-library upgrade cannot change the wire contract.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ProblemBodyCharacterizationTest extends PostgresIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Test
    void securityFilterProblemsHaveTheExactBodies() throws Exception {
        mvc.perform(get("/api/accounts/me").secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Unauthorized","status":401,
                         "detail":"authentication_failed","code":"authentication_failed"}""", JsonCompareMode.STRICT));
        mvc.perform(post("/api/accounts/login").secure(true).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Forbidden","status":403,
                         "detail":"operation_denied","code":"operation_denied"}""", JsonCompareMode.STRICT));
    }

    @Test
    void unreadableOrInvalidRequestBodiesAreTheStableBadRequestProblem() throws Exception {
        for (String body : new String[]{
                "{}", "{", "[]", "{\"login\":\"a\",\"password\":\"b\",\"extra\":1}",
                "{\"login\":null,\"password\":\"b\"}", "{\"login\":{\"x\":1},\"password\":\"b\"}"}) {
            login(body).andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(content().json(INVALID_REQUEST, JsonCompareMode.STRICT));
        }
        mvc.perform(withCsrf(post("/api/accounts/login")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(INVALID_REQUEST, JsonCompareMode.STRICT));
    }

    @Test
    void failedLoginIsTheStableAuthenticationProblemWithoutDisclosingTheAccount() throws Exception {
        login("{\"login\":\"nobody\",\"password\":\"b\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Unauthorized","status":401,"detail":"authentication_failed",
                         "instance":"/api/accounts/login","code":"authentication_failed"}""", JsonCompareMode.STRICT));
    }

    private static final String INVALID_REQUEST = """
            {"type":"about:blank","title":"Bad Request","status":400,"detail":"invalid_request",
             "instance":"/api/accounts/login","code":"invalid_request"}""";

    private org.springframework.test.web.servlet.ResultActions login(String body) throws Exception {
        return mvc.perform(withCsrf(post("/api/accounts/login")).content(body));
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.secure(true).with(csrf()).contentType("application/json");
    }
}
