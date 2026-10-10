package app.mnema.identityaccount.authorization;

import app.mnema.identityaccount.contract.BrowserOrigins;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The shared test database may have lost the reserved client while another context started without an admin origin. */
public final class AdminClientFixture {
    private AdminClientFixture() { }

    public static void ensureRegistered(AuthorizationConfiguration configuration, JdbcTemplate jdbc, TransactionTemplate transactions) {
        configuration.clients(jdbc, new BrowserOrigins("https://mnema.app", "https://admin.mnema.app"), "https://mnema.app/auth/callback", transactions);
    }
}
