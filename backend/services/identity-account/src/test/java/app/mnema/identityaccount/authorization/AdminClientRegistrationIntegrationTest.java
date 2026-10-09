package app.mnema.identityaccount.authorization;

import app.mnema.identityaccount.contract.BrowserOrigins;
import app.mnema.identityaccount.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AdminClientRegistrationIntegrationTest extends PostgresIntegrationTest {
    @Autowired AuthorizationConfiguration configuration;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @Test
    void removingConfiguredAdminOriginRemovesOnlyItsRegistrationAndRetainsMainCallback() {
        var enabled = configuration.clients(jdbc, new BrowserOrigins("https://mnema.app", "https://admin.mnema.app"),
                "https://mnema.app/auth/callback", transactions);
        assertThat(enabled.findByClientId("mnema-admin-web").getRedirectUris())
                .containsExactly("https://admin.mnema.app/auth/callback");
        var disabled = configuration.clients(jdbc, new BrowserOrigins("https://mnema.app", ""),
                "https://mnema.app/auth/callback", transactions);
        assertThat(disabled.findByClientId("mnema-admin-web")).isNull();
        assertThat(disabled.findByClientId("mnema-web").getRedirectUris())
                .containsExactly("https://mnema.app/auth/callback");
        // The database is shared by test classes: leave the registration as the other admin tests expect it.
        configuration.clients(jdbc, new BrowserOrigins("https://mnema.app", "https://admin.mnema.app"),
                "https://mnema.app/auth/callback", transactions);
    }
}
