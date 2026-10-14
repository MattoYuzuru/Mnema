package app.mnema.learning.community;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Community/7 (#454), migration {@code V69__editorial_shelf.sql}: the checks and keys that hold whatever writes the tables, and the audit action. */
@SpringBootTest
class CatalogEditorialSchemaIntegrationTest extends PostgresIntegrationTest {
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private String sqlState(String sql, Object... params) {
        try {
            new TransactionTemplate(transactions).execute(status -> {
                jdbc.sql("SET LOCAL session_replication_role = replica").update();
                var statement = jdbc.sql(sql);
                for (int index = 0; index < params.length; index++) statement = statement.param("p" + index, params[index]);
                statement.update();
                status.setRollbackOnly();
                return null;
            });
        } catch (DataAccessException failure) {
            Throwable cause = failure.getMostSpecificCause();
            return cause instanceof java.sql.SQLException sql2 ? sql2.getSQLState() : "other";
        }
        return "none";
    }

    @Test
    void thePicksAreOrderedFromOneToTwentyFourAndEachDeckAndPositionOnce() {
        String insert = "INSERT INTO app_learning.editorial_pick(deck_id, position, added_at, added_by) VALUES (:p0, :p1, CURRENT_TIMESTAMP, :p2)";
        UUID deck = UUID.randomUUID();
        assertThat(sqlState(insert, deck, 1, UUID.randomUUID())).isEqualTo("none");
        assertThat(sqlState(insert, deck, 24, UUID.randomUUID())).isEqualTo("none");
        assertThat(sqlState(insert, deck, 0, UUID.randomUUID())).as("position 0").isEqualTo("23514");
        assertThat(sqlState(insert, deck, 25, UUID.randomUUID())).as("position 25").isEqualTo("23514");
        assertThat(sqlState("INSERT INTO app_learning.editorial_pick(deck_id, position, added_at, added_by) VALUES (:p0, 1, CURRENT_TIMESTAMP, :p1), (:p0, 2, CURRENT_TIMESTAMP, :p1)", deck, UUID.randomUUID()))
                .as("a deck once").isEqualTo("23505");
        assertThat(sqlState("INSERT INTO app_learning.editorial_pick(deck_id, position, added_at, added_by) VALUES (:p0, 3, CURRENT_TIMESTAMP, :p2), (:p1, 3, CURRENT_TIMESTAMP, :p2)",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).as("a position once").isEqualTo("23505");
        assertThat(sqlState("INSERT INTO app_learning.editorial_pick(deck_id, position, added_at, added_by) VALUES (:p0, 1, 'infinity', :p1)", deck, UUID.randomUUID())).isEqualTo("23514");
    }

    @Test
    void thereIsExactlyOneShelfWhoseVersionNeverGoesNegative() {
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.editorial_shelf").query(Long.class).single()).isEqualTo(1);
        assertThat(sqlState("INSERT INTO app_learning.editorial_shelf(shelf_id, row_version, updated_at) VALUES (:p0, 0, CURRENT_TIMESTAMP)", UUID.randomUUID())).isEqualTo("23514");
        assertThat(sqlState("UPDATE app_learning.editorial_shelf SET row_version = -1")).isEqualTo("23514");
        assertThat(sqlState("UPDATE app_learning.editorial_shelf SET row_version = row_version + 1")).isEqualTo("none");
    }

    @Test
    void theAuditJournalAcceptsTheEditorialActionAndStillOnlyTheListedOnes() {
        String insert = "INSERT INTO app_learning.admin_audit(actor_account_id, action, resource_id, command_id) VALUES (:p0, :p1, :p2, :p3)";
        assertThat(sqlState(insert, UUID.randomUUID(), "COMMUNITY_EDITORIAL_UPDATE", EditorialRepository.SHELF, UUID.randomUUID())).isEqualTo("none");
        assertThat(sqlState(insert, UUID.randomUUID(), "COMMUNITY_ANOMALY_DISMISS", UUID.randomUUID(), UUID.randomUUID())).isEqualTo("none");
        assertThat(sqlState(insert, UUID.randomUUID(), "COMMUNITY_EDITORIAL_DELETE", UUID.randomUUID(), UUID.randomUUID())).isEqualTo("23514");
    }
}
