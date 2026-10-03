package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP boundary of the assessment commands: shapes, status codes, stable problem codes and bodies that fail before the service. */
class AssessmentControllerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID actor = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID();
    private final AssessmentService service = mock(AssessmentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(actor.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new AssessmentController(service)).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private String url(String suffix) { return "/decks/" + deck + "/study-sessions/" + session + "/attempts/" + attempt + suffix; }

    private ObjectNode state(String status) {
        return JSON.createObjectNode().put("attemptId", attempt.toString()).put("status", status);
    }

    @Test
    void theReadIsPrivateJson() throws Exception {
        when(service.read(actor, deck, session, attempt)).thenReturn(state("ASSESSING"));
        mvc.perform(get(url(""))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSESSING"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        when(service.read(actor, deck, session, attempt)).thenThrow(new ResourceNotFoundException());
        mvc.perform(get(url(""))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void selfCheckTakesNoBodyOrAnEmptyObjectOnly() throws Exception {
        when(service.selfCheck(actor, deck, session, attempt)).thenReturn(state("SELF_CHECK"));
        mvc.perform(post(url("/self-check"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SELF_CHECK"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(post(url("/self-check")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        mvc.perform(post(url("/self-check")).contentType(MediaType.APPLICATION_JSON).content("   ")).andExpect(status().isOk());
        mvc.perform(post(url("/self-check")).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post(url("/self-check")).contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isBadRequest());
        mvc.perform(post(url("/self-check")).contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest());
    }

    @Test
    void aSelfRatingIsOneOfTheFourRatingsAndAnExactRetryIsMarkedReplayed() throws Exception {
        ObjectNode outcome = JSON.createObjectNode().put("attemptId", attempt.toString()).put("status", "ASSESSED");
        when(service.selfRate(actor, deck, session, attempt, AttemptCommand.SelfRating.PARTIAL))
                .thenReturn(new AttemptService.SubmitResult(outcome, false, false));
        mvc.perform(post(url("/self-rating")).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":\"PARTIAL\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSESSED"))
                .andExpect(header().doesNotExist("Idempotency-Replayed")).andExpect(header().string("Cache-Control", "private, no-store"));
        when(service.selfRate(actor, deck, session, attempt, AttemptCommand.SelfRating.FULL))
                .thenReturn(new AttemptService.SubmitResult(outcome, true, false));
        mvc.perform(post(url("/self-rating")).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":\"FULL\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"));
        when(service.selfRate(actor, deck, session, attempt, AttemptCommand.SelfRating.HINTED)).thenThrow(new AssessmentStateConflictException());
        mvc.perform(post(url("/self-rating")).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":\"HINTED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSESSMENT_STATE_CONFLICT"));
        when(service.selfRate(actor, deck, session, attempt, AttemptCommand.SelfRating.NOT_RECALLED)).thenThrow(new IdempotencyConflictException());
        mvc.perform(post(url("/self-rating")).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":\"NOT_RECALLED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        for (String body : new String[] {"{}", "{\"rating\":\"GREAT\"}", "{\"rating\":3}", "{\"rating\":\"FULL\",\"extra\":1}", "[]", "{"}) {
            mvc.perform(post(url("/self-rating")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void aDisputeCarriesACommandIdAndNoUnknownFields() throws Exception {
        UUID command = UUID.fromString("018f1d98-5c10-7abc-8abc-012345678951");
        ObjectNode outcome = JSON.createObjectNode().put("attemptId", attempt.toString()).put("status", "NOT_ASSESSED").put("disputed", true);
        when(service.dispute(actor, deck, session, attempt, new AssessmentService.DisputeCommand(command, false)))
                .thenReturn(new AttemptService.SubmitResult(outcome, false, false));
        mvc.perform(post(url("/dispute")).contentType(MediaType.APPLICATION_JSON).content("{\"commandId\":\"" + command + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disputed").value(true));
        when(service.dispute(actor, deck, session, attempt, new AssessmentService.DisputeCommand(command, true)))
                .thenReturn(new AttemptService.SubmitResult(outcome, true, false));
        mvc.perform(post(url("/dispute")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"" + command + "\",\"shareExample\":true}"))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"));
        UUID other = UUID.fromString("018f1d98-5c10-7abc-8abc-012345678952");
        when(service.dispute(actor, deck, session, attempt, new AssessmentService.DisputeCommand(other, false)))
                .thenThrow(new DisputeNotAllowedException());
        mvc.perform(post(url("/dispute")).contentType(MediaType.APPLICATION_JSON).content("{\"commandId\":\"" + other + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DISPUTE_NOT_ALLOWED"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        for (String body : new String[] {"{}", "{\"commandId\":\"not-a-uuid\"}", "{\"commandId\":\"" + command + "\",\"shareExample\":\"yes\"}",
                "{\"commandId\":\"" + command + "\",\"answer\":\"text\"}", "[]", "{"}) {
            mvc.perform(post(url("/dispute")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verify(service).dispute(actor, deck, session, attempt, new AssessmentService.DisputeCommand(command, true));
    }

    @Test
    void malformedRouteIdsFailBeforeTheService() throws Exception {
        mvc.perform(get("/decks/bad/study-sessions/" + session + "/attempts/" + attempt)).andExpect(status().isBadRequest());
        mvc.perform(get("/decks/" + deck + "/study-sessions/" + session + "/attempts/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(post("/decks/" + deck + "/study-sessions/bad/attempts/" + attempt + "/self-check")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
