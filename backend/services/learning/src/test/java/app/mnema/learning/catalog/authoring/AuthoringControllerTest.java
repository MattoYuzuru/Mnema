package app.mnema.learning.catalog.authoring;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthoringControllerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID actor = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final UUID draft = UUID.randomUUID();
    private final UUID note = UUID.randomUUID();
    private final DraftService drafts = mock(DraftService.class);
    private final CaptureService captures = mock(CaptureService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "RS256").subject(actor.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new DraftController(drafts), new CaptureController(captures))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void draftCreateUpdateRestoreAndDeleteExposeAcknowledgedValidators() throws Exception {
        ObjectNode draftValue = JSON.createObjectNode().put("draftId", draft.toString()).put("rowVersion", "0");
        draftValue.set("document", AuthoringCommandsTest.document("saved"));
        ObjectNode acknowledgement = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        acknowledgement.set("draft", draftValue);
        when(drafts.create(eq(actor), any())).thenReturn(new DraftService.WriteResult(acknowledgement, false));
        mvc.perform(post("/api/editing-drafts").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                        .content(draftCreateBody()))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Location", "/api/editing-drafts/" + draft))
                .andExpect(header().string("Cache-Control", "private, no-store"));

        when(drafts.read(actor, draft)).thenReturn(draftValue);
        mvc.perform(get("/editing-drafts/" + draft)).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""));
        when(drafts.update(eq(actor), eq(draft), eq(0L), any()))
                .thenReturn(new DraftService.WriteResult(acknowledgement, true));
        mvc.perform(put("/editing-drafts/" + draft).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(draftUpdateBody()))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().doesNotExist("ETag"));
        mvc.perform(delete("/editing-drafts/" + draft).header("If-Match", "\"0\""))
                .andExpect(status().isNoContent());
    }

    @Test
    void captureLifecycleAndConversionExposePrivateStableWireContract() throws Exception {
        ObjectNode capture = JSON.createObjectNode().put("noteId", note.toString()).put("rowVersion", "0")
                .put("source", "lesson").put("text", "note");
        ObjectNode created = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        created.set("capture", capture);
        when(captures.create(eq(actor), any())).thenReturn(new CaptureService.WriteResult(created, false));
        mvc.perform(post("/capture-notes").contentType(MediaType.APPLICATION_JSON).content(captureCreateBody()))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Location", "/capture-notes/" + note));

        ObjectNode updated = capture.deepCopy().put("rowVersion", "1").put("archived", false);
        when(captures.update(eq(actor), eq(note), eq(0L), any())).thenReturn(updated);
        mvc.perform(put("/capture-notes/" + note).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"book\",\"text\":\"text\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        ObjectNode archived = updated.deepCopy().put("rowVersion", "2").put("archived", true);
        when(captures.archive(eq(actor), eq(note), eq(1L), any())).thenReturn(archived);
        mvc.perform(post("/capture-notes/" + note + "/archive").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));

        ObjectNode converted = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("noteId", note.toString()).put("noteVersion", "3").put("sourcePreserved", true);
        converted.set("publication", JSON.createObjectNode());
        when(captures.convert(eq(actor), eq(note), eq(2L), any()))
                .thenReturn(new CaptureService.WriteResult(converted, false));
        mvc.perform(post("/capture-notes/" + note + "/conversions").header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content(conversionBody()))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.sourcePreserved").value(true));
    }

    @Test
    void missingPreconditionsStrictParametersAndForeignIdsUseStableProblems() throws Exception {
        mvc.perform(put("/editing-drafts/" + draft).contentType(MediaType.APPLICATION_JSON)
                        .content(draftUpdateBody()))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(get("/editing-drafts").param("limit", "1", "2"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/capture-notes/not-a-uuid")).andExpect(status().isBadRequest());
        when(captures.read(actor, note)).thenThrow(new ResourceNotFoundException());
        mvc.perform(get("/capture-notes/" + note)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        when(drafts.create(eq(actor), any())).thenThrow(new ResourceLimitExceededException());
        mvc.perform(post("/editing-drafts").contentType(MediaType.APPLICATION_JSON).content(draftCreateBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESOURCE_LIMIT_EXCEEDED"));
    }

    private String draftCreateBody() {
        ObjectNode value = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("deckId", deck.toString());
        value.set("document", AuthoringCommandsTest.document("draft"));
        return value.toString();
    }

    private String draftUpdateBody() {
        ObjectNode value = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        value.set("document", AuthoringCommandsTest.document("draft"));
        return value.toString();
    }

    private String captureCreateBody() {
        return JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("deckId", deck.toString())
                .put("source", "lesson").put("text", "note").toString();
    }

    private String conversionBody() {
        ObjectNode value = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckVersion", "0").put("expectedDeckRevisionId", UUID.randomUUID().toString());
        value.set("document", AuthoringCommandsTest.document("converted"));
        return value.toString();
    }
}
