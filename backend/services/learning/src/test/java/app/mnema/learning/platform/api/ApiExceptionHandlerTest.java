package app.mnema.learning.platform.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

@WebMvcTest(
        controllers = TestFailureController.class,
        properties = "server.servlet.context-path="
)
@Import(ApiExceptionHandler.class)
// MVC error-contract unit slice only; full real HTTP security is covered independently.
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("api-handler-fixture")
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsStableIdempotencyConflictProblem() throws Exception {
        mockMvc.perform(get("/_test/idempotency"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", containsString(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
                .andExpect(jsonPath("$.type").value("urn:mnema:problem:idempotency-conflict"))
                .andExpect(jsonPath("$.title").value("Idempotency conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.instance").value("/_test/idempotency"));
    }

    @Test
    void returnsStableVersionConflictProblem() throws Exception {
        mockMvc.perform(get("/_test/version"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("urn:mnema:problem:version-conflict"))
                .andExpect(jsonPath("$.status").value(412))
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void returnsPreconditionRequiredProblem() throws Exception {
        mockMvc.perform(get("/_test/precondition-required"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type").value("urn:mnema:problem:precondition-required"))
                .andExpect(jsonPath("$.status").value(428))
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void normalizesFrameworkValidationAndMethodErrors() throws Exception {
        mockMvc.perform(post("/_test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(not(containsString("NotBlank"))));

        mockMvc.perform(get("/_test/validation"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        mockMvc.perform(post("/_test/validation")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/_test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void hidesUnexpectedExceptionMessagesAndTypes() throws Exception {
        mockMvc.perform(get("/_test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed."))
                .andExpect(content().string(not(containsString("database-password"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

}
