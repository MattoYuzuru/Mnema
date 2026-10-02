package app.mnema.learning.platform.api;

import app.mnema.learning.usage.Bucket;
import app.mnema.learning.usage.Plan;
import app.mnema.learning.usage.SpecNotSupportedException;
import app.mnema.learning.usage.Unit;
import app.mnema.learning.usage.UsageLimitReachedException;
import app.mnema.learning.usage.Window;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The typed extension members of a problem: what is accepted, and how the handler writes them. */
class ProblemExtensionTest {

    @Test
    void membersKeepInsertionOrderAndNormalizeInstantsEnumsAndNestedValues() {
        ProblemExtension extension = ProblemExtension.builder().put("bucket", Bucket.CREDITS)
                .put("renewsAt", Instant.parse("2026-10-31T21:00:00Z")).put("limit", null).put("count", 3)
                .put("big", 5_000_000_000L).put("flag", true)
                .put("limits", Map.of("maxExerciseTargets", 20)).put("ids", List.of("a", "b")).build();

        assertThat(extension.members().keySet()).containsExactly("bucket", "renewsAt", "limit", "count", "big", "flag",
                "limits", "ids");
        assertThat(extension.members()).containsEntry("bucket", "CREDITS")
                .containsEntry("renewsAt", "2026-10-31T21:00:00Z").containsEntry("limit", null)
                .containsEntry("limits", Map.of("maxExerciseTargets", 20));
        assertThat(ProblemExtension.none().members()).isEmpty();
        assertThat(ProblemExtension.builder().build()).isSameAs(ProblemExtension.none());
        assertThatThrownBy(() -> extension.members().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reservedMalformedDuplicateAndUnsupportedMembersAreProducerBugs() {
        for (String reserved : List.of("type", "title", "status", "detail", "instance", "code")) {
            assertThatThrownBy(() -> ProblemExtension.builder().put(reserved, "x")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ProblemExtension.builder().put("Upper", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemExtension.builder().put("with-dash", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemExtension.builder().put("a", 1).put("a", 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemExtension.builder().put("a", 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemExtension.builder().put("a", new Object())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemExtension.builder().put("a", "x".repeat(201))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemExtension.builder().put("a", Map.of("Bad", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemExtension.builder().put("a", Map.of(1, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProblemExtension.builder().put("a", List.of(List.of(List.of(List.of(1))))))
                .isInstanceOf(IllegalArgumentException.class);
        var builder = ProblemExtension.builder();
        for (int i = 0; i < 16; i++) builder.put("m" + i, i);
        assertThatThrownBy(() -> builder.put("overflow", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    // The profile is never active: tests mount it by hand, and a scanned test controller would add routes to every context.
    @RestController
    @Profile("problem-extension-fixture")
    static final class Failing {
        @GetMapping("/usage-limit")
        void usage() {
            throw new UsageLimitReachedException(new UsageLimitReachedException.Block(Bucket.PODCASTS, Window.MONTH,
                    Unit.COUNT, 0L, 0, 1, false, null, false, Plan.FREE));
        }

        @GetMapping("/spec")
        void spec() {
            throw new SpecNotSupportedException("REVISE_ITEM");
        }

        @GetMapping("/limit")
        void limit() {
            throw new ResourceLimitExceededException(ProblemExtension.builder().put("limit", "EXERCISES_PER_SESSION")
                    .put("limits", Map.of("maxExercisesPerSession", 60)).build());
        }

        @GetMapping("/plain-limit")
        void plainLimit() {
            throw new ResourceLimitExceededException();
        }

        @GetMapping("/capability")
        void capability() {
            throw new CapabilityUnavailableException(ProblemExtension.builder().put("capability", "textToSpeech")
                    .put("reason", "PROVIDER_NOT_CONFIGURED").build());
        }

        @GetMapping("/plain-capability")
        void plainCapability() {
            throw new CapabilityUnavailableException();
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Failing()).setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void usageLimitReachedIsA409WithTheContractsMembersAndNothingElse() throws Exception {
        mvc.perform(get("/usage-limit")).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.type").value("urn:mnema:problem:usage-limit-reached"))
                .andExpect(jsonPath("$.title").value("Usage limit reached"))
                .andExpect(jsonPath("$.detail").value("The remaining AI budget does not cover this operation."))
                .andExpect(jsonPath("$.status").value(409)).andExpect(jsonPath("$.instance").value("/usage-limit"))
                .andExpect(jsonPath("$.code").value("USAGE_LIMIT_REACHED")).andExpect(jsonPath("$.bucket").value("PODCASTS"))
                .andExpect(jsonPath("$.window").value("MONTH")).andExpect(jsonPath("$.unit").value("COUNT"))
                .andExpect(jsonPath("$.limit").value(0)).andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.required").value(1)).andExpect(jsonPath("$.offered").value(false))
                .andExpect(jsonPath("$.renewsAt").value((Object) null)).andExpect(jsonPath("$.fitsAfterRenewal").value(false))
                .andExpect(jsonPath("$.plan").value("FREE"));
    }

    @Test
    void specNotSupportedAndResourceLimitAndCapabilityCarryTheirMembers() throws Exception {
        mvc.perform(get("/spec")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:mnema:problem:spec-not-supported"))
                .andExpect(jsonPath("$.title").value("Spec not supported"))
                .andExpect(jsonPath("$.code").value("SPEC_NOT_SUPPORTED")).andExpect(jsonPath("$.kind").value("REVISE_ITEM"));
        mvc.perform(get("/limit")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESOURCE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.limit").value("EXERCISES_PER_SESSION"))
                .andExpect(jsonPath("$.limits.maxExercisesPerSession").value(60));
        mvc.perform(get("/capability")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPABILITY_UNAVAILABLE"))
                .andExpect(jsonPath("$.capability").value("textToSpeech"))
                .andExpect(jsonPath("$.reason").value("PROVIDER_NOT_CONFIGURED"));
    }

    @Test
    void existingProblemsWithoutMembersKeepTheirStableShape() throws Exception {
        mvc.perform(get("/plain-limit")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RESOURCE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$", not(hasKey("limit")))).andExpect(jsonPath("$", not(hasKey("limits"))));
        mvc.perform(get("/plain-capability")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPABILITY_UNAVAILABLE"))
                .andExpect(jsonPath("$", not(hasKey("capability")))).andExpect(jsonPath("$", not(hasKey("reason"))));
    }
}
