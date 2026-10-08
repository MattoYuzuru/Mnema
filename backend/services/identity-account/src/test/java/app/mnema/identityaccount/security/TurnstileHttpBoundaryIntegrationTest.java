package app.mnema.identityaccount.security;

import app.mnema.identityaccount.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"APP_ENV=prod", "identity.turnstile.mode=disabled",
        "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=fixture-google-client",
        "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=fixture-google-secret"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class TurnstileHttpBoundaryIntegrationTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void directApiCannotBypassProductionProtectionEvenWithCsrfAndGlobalDisableConfiguration() throws Exception {
        long before = jdbc.sql("SELECT count(*) FROM app_identity.account").query(Long.class).single();
        mvc.perform(get("/api/accounts/abuse-protection")).andExpect(status().isOk())
                .andExpect(content().json("{\"mode\":\"blocked\",\"siteKey\":\"\"}"));
        mvc.perform(post("/api/accounts/register").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"fixture@example.test\",\"loginName\":\"fixture\",\"password\":\"synthetic-password-123\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("abuse_protection_unavailable"));
        mvc.perform(post("/api/accounts/login").with(csrf()).contentType("application/json")
                        .content("{\"login\":\"fixture\",\"password\":\"synthetic-password-123\",\"turnstileToken\":\"forged\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("abuse_protection_unavailable"));
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.account").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void hostedLoginHasCspExternalScriptPrivacyAndAccessibleFeedback() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("https://challenges.cloudflare.com")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("src=\"/login/script.js\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("role=\"status\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"https://mnema.app/terms\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"https://mnema.app/privacy\"")));
        mvc.perform(get("/login/script.js")).andExpect(status().isOk());
        mvc.perform(get("/login/privacy")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("turnstile-privacy-policy")));
    }

    @Test
    void blockedModeClosesPasswordAuthButKeepsConfiguredOAuthAvailable() throws Exception {
        mvc.perform(get("/api/accounts/providers").secure(true)).andExpect(status().isOk())
                .andExpect(content().json("{\"providers\":[\"google\"]}"));
        mvc.perform(get("/oauth2/authorization/google").secure(true)).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://accounts.google.com/")));
        mvc.perform(post("/api/accounts/login").with(csrf()).contentType("application/json")
                        .content("{\"login\":\"fixture\",\"password\":\"synthetic-password-123\",\"turnstileToken\":\"forged\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/accounts/register").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"fixture@example.test\",\"loginName\":\"fixture\",\"password\":\"synthetic-password-123\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
