package app.mnema.learning.admin.support;

import app.mnema.learning.admin.AdminConsoleAccess;
import app.mnema.learning.platform.api.AccessForbiddenException;
import app.mnema.learning.platform.api.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminSupportControllerTest {
    private static final UUID OWNER = UUID.randomUUID();

    @Test void rejectsOtherActorBeforeParsingOrTouchingBridge() {
        var client = mock(AdminSupportClient.class);
        var controller = new AdminSupportController(new AdminConsoleAccess(OWNER.toString()), client);
        assertThatThrownBy(() -> controller.tickets(identity(UUID.randomUUID()), new MockHttpServletRequest()))
                .isInstanceOf(AccessForbiddenException.class);
        assertThatThrownBy(() -> controller.command(identity(UUID.randomUUID()), "1", new ByteArrayInputStream("bad".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(AccessForbiddenException.class);
        verifyNoInteractions(client);
    }

    @Test void mvcKeepsStableProblemsAndNeverCachesPrivateResponses() throws Exception {
        var client = mock(AdminSupportClient.class);
        var controller = new AdminSupportController(new AdminConsoleAccess(OWNER.toString()), client);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(identity(OWNER)));
        try {
            when(client.get("/tickets")).thenThrow(new SupportUnavailableException());
            mvc.perform(get("/admin/support/tickets")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SUPPORT_UNAVAILABLE"))
                    .andExpect(header().string("Cache-Control", "private, no-store"));
            when(client.get("/tickets/1")).thenThrow(new SupportConflictException());
            mvc.perform(get("/admin/support/tickets/1")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SUPPORT_CONFLICT"));
            var result = JsonNodeFactory.instance.objectNode().put("commandId", UUID.randomUUID().toString());
            when(client.command(eq("/tickets/1/commands"), any())).thenReturn(new AdminSupportClient.Result(202, result, true));
            mvc.perform(post("/admin/support/tickets/1/commands").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":1,\"type\":\"reply\",\"text\":\"Answer\"}"))
                    .andExpect(status().isAccepted()).andExpect(header().string("Idempotency-Replayed", "true"))
                    .andExpect(header().string("Cache-Control", "private, no-store"));
            verify(client).command(eq("/tickets/1/commands"), org.mockito.ArgumentMatchers.argThat(body ->
                    OWNER.toString().equals(body.path("actorAccountId").stringValue(null))));
            mvc.perform(post("/admin/support/tickets/1/commands").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":1,\"type\":\"reply\",\"text\":\"Answer\",\"actorAccountId\":\"spoofed\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        } finally { SecurityContextHolder.clearContext(); }
    }

    private static Jwt identity(UUID subject) {
        return Jwt.withTokenValue("fixture-token").header("alg", "RS256").subject(subject.toString()).build();
    }
}
