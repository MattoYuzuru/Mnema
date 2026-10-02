package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static app.mnema.learning.study.attempt.PreviewRequests.previewFixture;
import static app.mnema.learning.support.ContractFixtures.JSON;
import static app.mnema.learning.support.ContractFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExercisePreviewControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ExercisePreviewController(new ExercisePreviewService()))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void everyActionReturnsItsFixtureResultPrivatelyAndWithoutCaching() throws Exception {
        String[][] cases = {{"submitCloze", "submitClozeResult"}, {"submitChoice", "submitChoiceResult"},
                {"submitMatchAfterMistake", "submitMatchAfterMistakeResult"},
                {"submitFreeResponse", "submitFreeResponseResult"}, {"pairCheck", "pairCheckResult"},
                {"hint", "hintResult"}};
        for (String[] pair : cases) {
            String body = mvc.perform(post("/exercise-previews").contentType(MediaType.APPLICATION_JSON)
                            .content(previewFixture(pair[0]).toString()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(JSON.readTree(body)).isEqualTo(fixture("preview.json").path(pair[1]));
        }
    }

    @Test
    void validationFailuresAreOpaqueStableProblemsThatEchoNothingOfTheDraft() throws Exception {
        ObjectNode secret = previewFixture("submitFreeResponse");
        secret.withObject("exercise").withObject("answerKey").put("extra", "TOP-SECRET-ANSWER");
        String body = mvc.perform(post("/exercise-previews").contentType(MediaType.APPLICATION_JSON)
                        .content(secret.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("TOP-SECRET-ANSWER", "Erinnerung", "extra", "Exception");

        for (String invalid : new String[] {"{}", "[]", "{", ""}) {
            mvc.perform(post("/exercise-previews").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void aBodyOverSixtyFourKibIsRejectedWithoutBeingParsedIntoAnExercise() throws Exception {
        JsonNode valid = previewFixture("submitFreeResponse");
        String padded = valid.toString().replaceFirst("\\}$", ",\"padding\":\"" + "x".repeat(65_536) + "\"}");
        mvc.perform(post("/exercise-previews").contentType(MediaType.APPLICATION_JSON).content(padded))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void onlyJsonPostsAreServed() throws Exception {
        mvc.perform(post("/exercise-previews").contentType(MediaType.TEXT_PLAIN)
                        .content(previewFixture("hint").toString())).andExpect(status().isUnsupportedMediaType());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/exercise-previews"))
                .andExpect(status().isMethodNotAllowed());
    }
}
