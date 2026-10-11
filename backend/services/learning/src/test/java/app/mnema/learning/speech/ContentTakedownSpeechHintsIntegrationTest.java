package app.mnema.learning.speech;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.support.TakedownFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Share/15 (#437): the terms of a taken-down material are not offered as speech hints (and its cached title is not read). */
@SpringBootTest
class ContentTakedownSpeechHintsIntegrationTest extends PostgresIntegrationTest {
    @Autowired private SpeechInputRepository repository;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private StudySessionService studies;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void aTakenDownMaterialIsNotAMaterialOfTheDecksTerms() {
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, studies, media, jdbc);
        Material a = fixtures.material();
        Material b = fixtures.addMaterial(a.actor(), a.deck(), "beta", "other");
        assertThat(repository.deckHeads(a.actor(), a.deck(), 10)).extracting(SpeechInputRepository.Head::member).containsExactlyInAnyOrder(a.member(), b.member());
        new TakedownFixture(jdbc).item(a.deck(), a.member(), a.itemRevision());
        assertThat(repository.deckHeads(a.actor(), a.deck(), 10)).extracting(SpeechInputRepository.Head::member).containsExactly(b.member());
        // a copy that holds the same revision is the same
        var copy = new SharedScopeFixture(decks, items, storage, jdbc, transactions).copy(a.deck(), java.util.UUID.randomUUID());
        assertThat(repository.deckHeads(copy.owner(), copy.deckId(), 10)).extracting(SpeechInputRepository.Head::member).containsExactly(b.member());
    }
}
