package app.mnema.identityaccount;

import app.mnema.identityaccount.contract.IssuerContract;
import app.mnema.identityaccount.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IdentityAccountApplicationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private BuildProperties buildProperties;

    @Autowired
    private Flyway flyway;

    @Autowired
    private InfoEndpoint infoEndpoint;

    @Autowired
    private IssuerContract issuerContract;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping requestMappings;

    @Autowired
    @SuppressWarnings("rawtypes")
    private org.springframework.session.SessionRepository sessionRepository;

    @Autowired
    private org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations;

    @Autowired
    private org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository clients;

    @Autowired
    private app.mnema.identityaccount.local.LocalAccounts localAccounts;

    @Test
    void bootsFreshIdentityMigrationWithoutLegacyHistoryOrSchemas() {
        assertThat(flyway.info().applied()).filteredOn(m -> m.getVersion() != null)
                .extracting(m -> m.getVersion().getVersion()).containsExactly("1", "2", "3", "4");

        assertThat(jdbcClient.sql("""
                        SELECT schema_name
                        FROM information_schema.schemata
                        WHERE schema_name LIKE 'app_%' OR schema_name = 'auth'
                        ORDER BY schema_name
                        """)
                .query(String.class)
                .list())
                .containsExactly("app_identity");

        var tables = jdbcClient.sql("""
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'app_identity'
                        ORDER BY table_name
                        """)
                .query(String.class)
                .list();
        assertThat(tables).contains("account", "account_avatar", "external_identity", "local_credential",
                "ownership_challenge", "spring_session", "oauth2_authorization", "account_deletion",
                "account_deletion_avatar", "account_erasure_handoff", "account_erasure_receipt", "admin_audit");
        assertThat(columns("external_identity")).containsExactly(
                "identity_id", "account_id", "provider", "provider_subject", "linked_at", "last_login_at"
        );
        assertThat(columns("account_avatar")).containsExactly(
                "account_id", "asset_id", "storage_key", "content_type", "byte_size",
                "content_sha256", "width", "height", "created_at", "storage_version"
        );
        assertThat(jdbcClient.sql("""
                        SELECT DISTINCT data_type
                        FROM information_schema.columns
                        WHERE table_schema = 'app_identity'
                          AND table_name IN ('oauth2_registered_client', 'oauth2_authorization')
                          AND column_name LIKE '%\\_at' ESCAPE '\\'
                        """)
                .query(String.class)
                .list()).containsExactly("timestamp with time zone");
        assertThat(jdbcClient.sql("SELECT current_setting('server_version_num')::int / 10000")
                .query(Integer.class)
                .single()).isEqualTo(18);
    }

    @Test
    void exposesHealthBuildIdentityAndExplicitIssuerContract() throws Exception {
        assertThat(buildProperties.getName()).isEqualTo("identity-account");
        assertThat(buildProperties.get("runtime")).isEqualTo("identity-account");
        assertThat(buildProperties.get("identityBoundary")).isEqualTo("unified");
        assertThat(buildProperties.getTime()).isNull();
        assertThat(issuerContract.issuer()).isEqualTo("https://identity.mnema.test");
        assertThat(infoEndpoint.info()).containsEntry("build", Map.of(
                "name", "identity-account",
                "group", "app.mnema",
                "artifact", "identity-account",
                "version", "0.0.1-SNAPSHOT",
                "runtime", "identity-account",
                "identityBoundary", "unified"
        )).containsEntry("release", Map.of(
                "id", "test-release",
                "mode", "maintenance",
                "topology", "identity-learning",
                "runtime", "identity-account",
                "identity-boundary", "unified"
        ));

        mockMvc.perform(get("/api/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/api/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/api/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.release.runtime").value("identity-account"))
                .andExpect(jsonPath("$.release.mode").value("maintenance"))
                .andExpect(jsonPath("$.release.topology").value("identity-learning"));
    }

    /** Without the JDBC session store, sessions would silently fall back to Tomcat memory. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void browserSessionsAreStoredInThePostgresSessionTable() {
        assertThat(sessionRepository).isInstanceOf(org.springframework.session.jdbc.JdbcIndexedSessionRepository.class);
        var session = sessionRepository.createSession();
        session.setAttribute("characterization", "kept");
        sessionRepository.save(session);
        try {
            assertThat(jdbcClient.sql("SELECT count(*) FROM app_identity.spring_session WHERE session_id = :id")
                    .param("id", session.getId()).query(Long.class).single()).isEqualTo(1L);
            assertThat(jdbcClient.sql("""
                            SELECT count(*) FROM app_identity.spring_session_attributes a
                            JOIN app_identity.spring_session s ON s.primary_id = a.session_primary_id
                            WHERE s.session_id = :id AND a.attribute_name = 'characterization'""")
                    .param("id", session.getId()).query(Long.class).single()).isEqualTo(1L);
            var found = sessionRepository.findById(session.getId());
            assertThat((String) found.getAttribute("characterization")).isEqualTo("kept");
        } finally {
            sessionRepository.deleteById(session.getId());
        }
    }

    @Test
    void oauth2AuthorizationsRoundTripThroughThePostgresTable() {
        String key = java.util.UUID.randomUUID().toString();
        var access = localAccounts.register(key + "@example.test", key, "correct-horse-battery-42", null, key);
        String token = "round-trip-" + key;
        var issued = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var saved = org.springframework.security.oauth2.server.authorization.OAuth2Authorization
                .withRegisteredClient(clients.findByClientId("mnema-web"))
                .principalName(access.accountId().toString())
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute("generation", Long.toString(access.generation()))
                .accessToken(new org.springframework.security.oauth2.core.OAuth2AccessToken(
                        org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER, token,
                        issued, issued.plusSeconds(120), java.util.Set.of("account.read", "account.write")))
                .build();

        authorizations.save(saved);

        assertThat(jdbcClient.sql("SELECT count(*) FROM app_identity.oauth2_authorization WHERE id = :id")
                .param("id", saved.getId()).query(Long.class).single()).isEqualTo(1L);
        for (var found : java.util.List.of(authorizations.findById(saved.getId()),
                authorizations.findByToken(token,
                        org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN))) {
            assertThat(found).isNotNull();
            assertThat(found.getPrincipalName()).isEqualTo(access.accountId().toString());
            assertThat(found.getRegisteredClientId()).isEqualTo(saved.getRegisteredClientId());
            assertThat(found.<String>getAttribute("generation")).isEqualTo(Long.toString(access.generation()));
            var stored = found.getAccessToken();
            assertThat(stored.getToken().getTokenValue()).isEqualTo(token);
            assertThat(stored.getToken().getScopes()).containsExactlyInAnyOrder("account.read", "account.write");
            assertThat(stored.getToken().getExpiresAt()).isEqualTo(issued.plusSeconds(120));
        }
        authorizations.remove(saved);
        assertThat(authorizations.findById(saved.getId())).isNull();
    }

    @Test
    void canonicalAccountRoutesReplaceLegacyAliases() throws Exception {
        assertThat(applicationContext.getBeanNamesForAnnotation(RestController.class)).isNotEmpty();
        assertThat(requestMappings.getHandlerMethods().keySet()).flatExtracting(mapping -> mapping.getPatternValues())
                .allSatisfy(route -> assertThat(route).doesNotContain("/v2", "/api/users", "/api/user/"));
        mockMvc.perform(get("/api/accounts/csrf")).andExpect(status().isOk());
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    private java.util.List<String> columns(String table) {
        return jdbcClient.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'app_identity' AND table_name = :table
                        ORDER BY ordinal_position
                        """)
                .param("table", table)
                .query(String.class)
                .list();
    }
}
