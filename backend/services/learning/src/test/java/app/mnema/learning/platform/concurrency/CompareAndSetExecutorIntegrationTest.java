package app.mnema.learning.platform.concurrency;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CompareAndSetExecutorIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private CompareAndSetExecutor executor;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void resetFixture() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS app_learning.cas_fixture (
                    entity_id UUID PRIMARY KEY,
                    value TEXT NOT NULL,
                    row_version BIGINT NOT NULL CHECK (row_version >= 0)
                )
                """).update();
        jdbcClient.sql("TRUNCATE app_learning.cas_fixture").update();
    }

    @Test
    void updatesExactlyOneExpectedVersionAndRejectsStaleWriter() {
        UUID entityId = insert("before");

        long nextVersion = executor.updateOne(0, () -> update(entityId, 0, "after"));

        assertThat(nextVersion).isEqualTo(1);
        assertThat(row(entityId)).containsExactly("after", 1L);
        assertThatThrownBy(() -> executor.updateOne(0, () -> update(entityId, 0, "stale")))
                .isInstanceOf(VersionConflictException.class);
        assertThat(row(entityId)).containsExactly("after", 1L);
    }

    @Test
    void rollsBackWhenRepositoryUpdateTouchesMoreThanOneRow() {
        UUID first = insert("first");
        UUID second = insert("second");

        assertThatThrownBy(() -> executor.updateOne(0, () -> jdbcClient.sql("""
                        UPDATE app_learning.cas_fixture
                        SET value = 'invalid', row_version = row_version + 1
                        WHERE row_version = 0
                        """).update()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(row(first)).containsExactly("first", 0L);
        assertThat(row(second)).containsExactly("second", 0L);
    }

    @Test
    void validatesVersionAndUpdateCallbackBeforeWriting() {
        assertThatThrownBy(() -> executor.updateOne(-1, () -> 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.updateOne(Long.MAX_VALUE, () -> 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.updateOne(0, null))
                .isInstanceOf(NullPointerException.class);
    }

    private UUID insert(String value) {
        UUID entityId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO app_learning.cas_fixture (entity_id, value, row_version)
                        VALUES (:entityId, :value, 0)
                        """)
                .param("entityId", entityId)
                .param("value", value)
                .update();
        return entityId;
    }

    private int update(UUID entityId, long expectedVersion, String value) {
        return jdbcClient.sql("""
                        UPDATE app_learning.cas_fixture
                        SET value = :value, row_version = row_version + 1
                        WHERE entity_id = :entityId AND row_version = :expectedVersion
                        """)
                .param("entityId", entityId)
                .param("expectedVersion", expectedVersion)
                .param("value", value)
                .update();
    }

    private Object[] row(UUID entityId) {
        return jdbcClient.sql("""
                        SELECT value, row_version
                        FROM app_learning.cas_fixture
                        WHERE entity_id = :entityId
                        """)
                .param("entityId", entityId)
                .query((resultSet, rowNumber) -> new Object[]{
                        resultSet.getString("value"), resultSet.getLong("row_version")
                })
                .single();
    }
}
