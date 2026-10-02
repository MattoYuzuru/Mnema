package app.mnema.learning.notification;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP boundary: parameter validation before the service, headers, status codes and problem details. */
class NotificationControllerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID owner = UUID.randomUUID();
    private final NotificationService service = mock(NotificationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void aListIsPrivateCarriesTheValidatorAndForwardsTheBoundedQuery() throws Exception {
        var item = new NotificationService.View(UUID.randomUUID(), "7", "USAGE_EXHAUSTED", "WARNING",
                JSON.readTree("{\"bucket\":\"CREDITS\"}"), "PLANS", "2026-10-02T09:00:00Z", "2026-11-01T09:00:00Z");
        when(service.list(eq(owner), eq(20), isNull(), isNull(), isNull())).thenReturn(new NotificationService.Listing(
                "\"n-7\"", new NotificationService.Page(List.of(item), 1, "0", 0, null)));
        mvc.perform(get("/notifications")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store")).andExpect(header().string("ETag", "\"n-7\""))
                .andExpect(jsonPath("$.items[0].seq").value("7")).andExpect(jsonPath("$.items[0].params.bucket").value("CREDITS"))
                .andExpect(jsonPath("$.unreadCount").value(1)).andExpect(jsonPath("$.readUpto").value("0"))
                .andExpect(jsonPath("$.activeWork").value(0)).andExpect(jsonPath("$.nextCursor").value((Object) null));

        when(service.list(eq(owner), eq(5), eq(12L), isNull(), eq("\"old\""))).thenReturn(new NotificationService.Listing(
                "\"n-7\"", new NotificationService.Page(List.of(), 0, "7", 0, null)));
        mvc.perform(get("/notifications").queryParam("limit", "5").queryParam("after", "12")
                .header("If-None-Match", "\"old\"")).andExpect(status().isOk());
    }

    @Test
    void aMatchingValidatorIsNotModifiedWithoutABodyButWithTheValidatorAndNoStore() throws Exception {
        when(service.list(eq(owner), eq(20), isNull(), isNull(), eq("\"n-7\"")))
                .thenReturn(new NotificationService.Listing("\"n-7\"", null));
        mvc.perform(get("/notifications").header("If-None-Match", "\"n-7\"")).andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"n-7\"")).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=abc", "limit=-1", "limit=1&limit=2", "after=-1", "after=01",
            "after=x", "after=9999999999999999999", "cursor=!!", "cursor=QQ", "after=1&cursor=RDE", "cursor="})
    void malformedQueriesAreAnOpaque400BeforeTheService(String query) throws Exception {
        mvc.perform(get("/notifications?" + query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        verifyNoInteractions(service);
    }

    @Test
    void theReadCursorBodyIsExactlyOneDecimalString() throws Exception {
        when(service.advanceReadCursor(owner, 42)).thenReturn(new NotificationService.ReadCursor("42", 0));
        mvc.perform(put("/notifications/read-cursor").contentType(MediaType.APPLICATION_JSON).content("{\"readUpto\":\"42\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.readUpto").value("42")).andExpect(jsonPath("$.unreadCount").value(0));
        for (String body : List.of("{\"readUpto\":42}", "{\"readUpto\":\"-1\"}", "{\"readUpto\":\"007\"}", "{}", "[]", "",
                "{\"readUpto\":\"1\",\"extra\":true}", "{\"readUpto\":\"1\",\"readUpto\":\"2\"}", "{\"readUpto\":null}",
                "{\"readUpto\":\"99999999999999999999\"}")) {
            mvc.perform(put("/notifications/read-cursor").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verify(service).advanceReadCursor(owner, 42);
    }

    @Test
    void aReadValueBeyondTheLatestSeqIsTheServicesInvalidRequest() throws Exception {
        when(service.advanceReadCursor(owner, 9)).thenThrow(new InvalidRequestException());
        mvc.perform(put("/notifications/read-cursor").contentType(MediaType.APPLICATION_JSON).content("{\"readUpto\":\"9\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void dismissingIsNoContentAndAForeignOrAbsentIdIsTheOpaqueNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/notifications/" + id)).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "private, no-store")).andExpect(content().string(""));
        verify(service).dismiss(owner, id);

        UUID foreign = UUID.randomUUID();
        doThrow(new ResourceNotFoundException()).when(service).dismiss(owner, foreign);
        mvc.perform(delete("/notifications/" + foreign)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(delete("/notifications/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(delete("/notifications/00000000-0000-0000-0000-000000000000")).andExpect(status().isBadRequest());
        verify(service, org.mockito.Mockito.times(2)).dismiss(eq(owner), any());
    }
}
