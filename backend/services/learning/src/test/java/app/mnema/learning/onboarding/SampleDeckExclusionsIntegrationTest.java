package app.mnema.learning.onboarding;

import app.mnema.learning.community.CatalogEvents;
import app.mnema.learning.community.CatalogOutbox;
import app.mnema.learning.community.DeckEngagement;
import app.mnema.learning.community.InterestService;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.library.DeckVisibility;
import app.mnema.learning.platform.security.IdentityClaims.AccountSignals;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sample deck, and the template it is copied from, write no engagement, no catalogue event and no interest signal (OB-201), and the template never reaches the catalogue.
 * The decisive scenario is the worst case: a deck that the catalogue already listed becomes the template (the marker marks any ordinary deck) and is lowered to LINK, so the
 * catalogue still holds its old row, with its topic. A copy of it, its study, its deletion and the writers called directly must still leave no trace, while the same actions on an
 * ordinary public deck do (the control in each test).
 */
class SampleDeckExclusionsIntegrationTest extends SampleDeckIntegrationTest {
    private static final AccountSignals TRUSTED = new AccountSignals(true, false);

    @Autowired private DeckEngagement engagement;
    @Autowired private CatalogEvents events;
    @Autowired private CatalogOutbox outbox;
    @Autowired private InterestService interests;

    /** Waits for the catalogue projection of a public deck (the real job) to list it. */
    private void awaitListed(UUID deck) {
        Instant limit = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(limit)) {
            if (count("SELECT count(*) FROM app_learning.public_deck WHERE deck_id = :p0 AND listed", deck) == 1) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError("the catalogue did not list " + deck);
    }

    /** The learner answers the first scheduled session of the copy to its end through the real attempt service. */
    private UUID study(UUID learner, UUID copy, Source source) {
        StudyFixtures.Material inherited = source.materials().getFirst();
        StudyFixtures.Material asCopy = new StudyFixtures.Material(learner, copy, inherited.member(), inherited.itemRevision(), inherited.node(), inherited.distractor(),
                inherited.divider(), inherited.root());
        List<Issued> issued = worlds.study.issue(asCopy, "SCHEDULED", null);
        assertThat(issued).isNotEmpty();
        for (Issued presentation : issued) attempts.submit(learner, copy, presentation.session(), StudyFixtures.attempt(presentation, StudyFixtures.textResponse("перевод")));
        UUID session = issued.getFirst().session();
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_session WHERE session_id = :s").param("s", session).query(String.class).single()).isEqualTo("COMPLETE");
        return session;
    }

    /** The level goes down without touching the catalogue, as a deck that was listed before it became the template would be (the marker refuses a PUBLIC deck). */
    private void lower(UUID deck) {
        jdbc.sql("UPDATE app_learning.deck_publication SET visibility = 'LINK', row_version = row_version + 1 WHERE deck_id = :d").param("d", deck).update();
    }

    private long engagementOf(UUID source) {
        return count("SELECT count(*) FROM app_learning.deck_engagement WHERE deck_id = :p0", source);
    }

    private long eventsOf(UUID account, UUID source) {
        return count("SELECT count(*) FROM app_learning.catalog_event WHERE account_id = :p0 AND deck_id = :p1", account, source);
    }

    private int topics(UUID account) {
        return interests.profile(account).path("inferredTopics").size();
    }

    @Test
    void copyingStudyingAndDeletingASampleDeckLeavesNoEngagementEventOrInterest() {
        Source leaked = worlds.source("PUBLIC", 12, 2);
        awaitListed(leaked.deck());
        Source ordinary = worlds.source("PUBLIC", 12, 2);
        awaitListed(ordinary.deck());
        assertThat(count("SELECT count(*) FROM app_learning.public_deck WHERE deck_id = :p0 AND topic_id IS NOT NULL", leaked.deck())).isOne();

        // the listed deck is lowered to LINK and becomes the template; the catalogue still holds its old row
        lower(leaked.deck());
        SampleDeckTestConfiguration.template(context, leaked.deck());
        assertThat(purpose(leaked.deck())).isEqualTo("SAMPLE_TEMPLATE");

        UUID account = UUID.randomUUID();
        HttpIdentityFixture.signals(account, true, false);
        UUID copy = sample(account);
        worlds.awaitReady(account, copy);
        UUID session = study(account, copy, leaked);
        assertThat(session).isNotNull();
        delete(account, copy);

        assertThat(engagementOf(leaked.deck())).as("no FORK or STUDY row for the template").isZero();
        assertThat(eventsOf(account, leaked.deck())).as("no FORK, STUDY_START or DROP event").isZero();
        assertThat(count("SELECT count(*) FROM app_learning.catalog_event WHERE account_id = :p0", account)).isZero();
        assertThat(topics(account)).as("the topic of the template is no interest of the account").isZero();

        // the control: the same account copies, studies and deletes an ordinary public deck, and all of it is recorded
        UUID plain = worlds.forked(account, ordinary.code());
        worlds.awaitReady(account, plain);
        study(account, plain, ordinary);
        assertThat(count("SELECT count(*) FROM app_learning.deck_engagement WHERE deck_id = :p0 AND account_id = :p1 AND kind = 'FORK'", ordinary.deck(), account)).isOne();
        assertThat(count("SELECT count(*) FROM app_learning.deck_engagement WHERE deck_id = :p0 AND account_id = :p1 AND kind = 'STUDY'", ordinary.deck(), account)).isOne();
        assertThat(eventsOf(account, ordinary.deck())).isPositive();
        assertThat(topics(account)).isPositive();
        delete(account, plain);
        assertThat(count("SELECT count(*) FROM app_learning.catalog_event WHERE account_id = :p0 AND deck_id = :p1 AND kind = 'DROP'", account, ordinary.deck())).isOne();
        assertThat(engagementOf(leaked.deck())).isZero();
    }

    @Test
    void theWritersRefuseASourceThatIsNotAnOrdinaryDeck() {
        Source leaked = worlds.source("PUBLIC", 12, 2);
        awaitListed(leaked.deck());
        Source ordinary = worlds.source("PUBLIC", 12, 2);
        awaitListed(ordinary.deck());
        lower(leaked.deck());
        SampleDeckTestConfiguration.template(context, leaked.deck());
        UUID account = UUID.randomUUID();
        UUID revision = worlds.publishedRevision(leaked.deck());
        long outbox = count("SELECT count(*) FROM app_learning.deck_publication_event WHERE deck_id = :p0", leaked.deck());
        long projection = count("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.project' AND dedupe_key LIKE :p0", leaked.deck() + ":%");

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(engagement.forked(leaked.deck(), account, TRUSTED)).as("engagement of the template").isFalse();
            assertThat(engagement.forked(ordinary.deck(), account, TRUSTED)).as("engagement of an ordinary deck").isTrue();
            events.forked(account, leaked.deck());
            events.forked(account, ordinary.deck());
            this.outbox.publicationChanged(leaked.deck(), revision, DeckVisibility.LINK);
        });

        assertThat(engagementOf(leaked.deck())).isZero();
        assertThat(eventsOf(account, leaked.deck())).isZero();
        assertThat(eventsOf(account, ordinary.deck())).as("the control event").isOne();
        assertThat(count("SELECT count(*) FROM app_learning.deck_publication_event WHERE deck_id = :p0", leaked.deck())).as("no outbox row").isEqualTo(outbox);
        assertThat(count("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.project' AND dedupe_key LIKE :p0", leaked.deck() + ":%"))
                .as("no projection job").isEqualTo(projection);
        // an ordinary deck still gets its row and its job
        UUID ordinaryRevision = worlds.publishedRevision(ordinary.deck());
        new TransactionTemplate(transactions).executeWithoutResult(status -> this.outbox.publicationChanged(ordinary.deck(), ordinaryRevision, DeckVisibility.PUBLIC));
        assertThat(count("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.project' AND dedupe_key LIKE :p0", ordinary.deck() + ":%")).isPositive();
    }
}
