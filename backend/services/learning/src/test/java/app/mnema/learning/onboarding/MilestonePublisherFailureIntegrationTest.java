package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The notification is part of the action's transaction (OB-301): when the publisher fails in {@code beforeCommit} the commit fails and the action is rolled back with its milestone, so
 * an account never has a fact without its announcement, nor the other way round.
 */
@SpringBootTest(properties = {"learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z"})
class MilestonePublisherFailureIntegrationTest extends PostgresIntegrationTest {
    @MockitoBean private NotificationPublisher publisher;
    @Autowired private DeckService decks;
    @Autowired private JdbcClient jdbc;

    @Test
    void aPublisherThatFailsAtTheCommitRollsTheWholeActionBack() {
        when(publisher.publish(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("the notification center is down"));
        UUID owner = UUID.randomUUID();

        assertThatThrownBy(() -> decks.create(owner, new DeckCommand(UUID.randomUUID(), "Колода", "Описание"))).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck WHERE owner_id = :a").param("a", owner).query(Long.class).single()).as("the deck").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.account_milestone WHERE account_id = :a").param("a", owner).query(Long.class).single()).as("the milestone").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.command_receipt WHERE actor_id = :a").param("a", owner).query(Long.class).single()).as("the receipt").isZero();
    }
}
