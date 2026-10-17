package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SampleDeckFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DeckPurposeGuard} at the service level (OB-201), on a real PostgreSQL: the low-level level change and grant of {@link DeckPublications} (the HTTP routes are covered by the
 * sharing inventory of the onboarding tests) refuse a sample deck at every level and the template at every level but LINK; an ordinary deck is untouched.
 */
@SpringBootTest
class DeckPurposeGuardIntegrationTest extends PostgresIntegrationTest {
    @Autowired private DeckPublications publications;
    @Autowired private DeckPurposeGuard guard;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private LibraryFixtures fixtures;
    private final UUID owner = UUID.randomUUID();
    private final UUID grantee = UUID.randomUUID();

    @BeforeEach
    void fixtures() { fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications); }

    private void template(UUID deck) {
        assertThat(jdbc.sql("UPDATE app_learning.deck SET purpose = 'SAMPLE_TEMPLATE' WHERE deck_id = :d AND purpose = 'USER'").param("d", deck).update()).isOne();
    }

    @Test
    void anOrdinaryDeckIsShareableAtEveryLevelAndGrantable() {
        Deck deck = fixtures.deck(owner, "Обычная");
        assertThat(guard.purposeOf(deck.id())).isEqualTo(DeckPurpose.USER);
        guard.requireShareable(deck.id());
        for (DeckVisibility level : DeckVisibility.values()) {
            guard.requirePublication(deck.id(), level);
        }
        publications.grant(owner, deck.id(), grantee, GrantRole.VIEWER);
        assertThat(publications.setVisibility(owner, deck.id(), DeckVisibility.INVITE, DeckPublications.UNPUBLISHED)).isPresent();
        assertThat(guard.purposeOf(UUID.randomUUID())).as("no such deck: the caller's own lookup answers").isEqualTo(DeckPurpose.USER);
    }

    @Test
    void theTemplateIsPublishableAtLinkAndNowhereElse() {
        Deck deck = fixtures.deck(owner, "Шаблон");
        template(deck.id());
        assertThat(guard.purposeOf(deck.id())).isEqualTo(DeckPurpose.SAMPLE_TEMPLATE);
        assertThatThrownBy(() -> guard.requireShareable(deck.id())).isInstanceOf(SampleDeckNotShareableException.class);
        guard.requirePublication(deck.id(), DeckVisibility.LINK);
        for (DeckVisibility level : new DeckVisibility[] {DeckVisibility.PRIVATE, DeckVisibility.INVITE, DeckVisibility.PUBLIC}) {
            assertThatThrownBy(() -> guard.requirePublication(deck.id(), level)).as(level.name()).isInstanceOf(SampleDeckNotShareableException.class);
            assertThatThrownBy(() -> publications.setVisibility(owner, deck.id(), level, DeckPublications.UNPUBLISHED)).as(level.name())
                    .isInstanceOf(SampleDeckNotShareableException.class);
        }
        assertThat(publications.find(owner, deck.id())).isEmpty();
        assertThat(publications.setVisibility(owner, deck.id(), DeckVisibility.LINK, DeckPublications.UNPUBLISHED)).isPresent();
        assertThatThrownBy(() -> publications.grant(owner, deck.id(), grantee, GrantRole.VIEWER)).isInstanceOf(SampleDeckNotShareableException.class);
        // not the owner: the opaque 404 comes first, the purpose is not told
        assertThatThrownBy(() -> publications.grant(UUID.randomUUID(), deck.id(), grantee, GrantRole.VIEWER))
                .isInstanceOf(app.mnema.learning.platform.api.ResourceNotFoundException.class);
    }

    @Test
    void aSampleCopyIsNeverPublishableOrGrantable() {
        Deck deck = fixtures.deck(owner, "Учебная");
        SampleDeckFixtures.markSample(jdbc, transactions, deck.id());
        assertThat(guard.purposeOf(deck.id())).isEqualTo(DeckPurpose.SAMPLE);
        for (DeckVisibility level : DeckVisibility.values()) {
            assertThatThrownBy(() -> guard.requirePublication(deck.id(), level)).as(level.name()).isInstanceOf(SampleDeckNotShareableException.class);
            assertThatThrownBy(() -> publications.setVisibility(owner, deck.id(), level, DeckPublications.UNPUBLISHED)).as(level.name())
                    .isInstanceOf(SampleDeckNotShareableException.class);
        }
        assertThatThrownBy(() -> publications.grant(owner, deck.id(), grantee, GrantRole.VIEWER)).isInstanceOf(SampleDeckNotShareableException.class);
        assertThat(publications.find(owner, deck.id())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :d").param("d", deck.id()).query(Long.class).single()).isZero();
    }
}
