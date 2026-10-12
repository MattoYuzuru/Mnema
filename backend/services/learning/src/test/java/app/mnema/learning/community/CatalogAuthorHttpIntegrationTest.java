package app.mnema.learning.community;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.community.CatalogWorlds.World;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.DeckPublications;
import app.mnema.learning.library.DeckVisibility;
import app.mnema.learning.library.GrantRole;
import app.mnema.learning.library.PublicationService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/6 (#453), {@code GET /api/public/community/authors/{accountId}/decks}: the public decks of an author (listed or not) newest first, the invitation-only decks of that author a
 * signed-in viewer was granted, one cursor across both parts, and what never appears (link decks, private decks, hidden and deleted decks, invitations without a grant). Decks are made with
 * the production services and the production publication command; the projection jobs are run by hand.
 */
class CatalogAuthorHttpIntegrationTest extends CatalogHttpTest {
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private PublicationService publication;
    @Autowired private DeckModeration moderation;
    @Autowired private CatalogProjector projector;
    @Autowired private DeckPublications publications;
    private CatalogWorlds worlds;
    private final UUID author = UUID.randomUUID();
    private final UUID viewer = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void worlds() {
        worlds = new CatalogWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, publication, moderation);
        jdbc.sql("UPDATE app_learning.learning_job SET state = 'CANCELLED', finished_at = clock_timestamp() WHERE queue LIKE 'catalog.%' AND state = 'QUEUED'").update();
    }

    private World listed(String title) {
        World world = worlds.deck(author, title, "Описание " + title, 10);
        worlds.exercise(world.materials().getFirst(), ExerciseType.CHOICE);
        worlds.publish(world);
        worlds.project(projector, world.deck());
        return world;
    }

    private World belowThreshold(String title) {
        World world = worlds.deck(author, title, "Описание " + title, 3);
        worlds.publish(world);
        worlds.project(projector, world.deck());
        return world;
    }

    private World invitation(String title, UUID grantee) {
        World world = worlds.deck(author, title, "Только для своих " + title, 4);
        worlds.exercise(world.materials().getFirst(), ExerciseType.MATCH);
        worlds.publish(world, DeckVisibility.INVITE, CatalogWorlds.metadata("секрет"));
        if (grantee != null) publications.grant(author, world.deck(), grantee, GrantRole.VIEWER);
        return world;
    }

    private String path(String suffix) {
        return "/public/community/authors/" + author + "/decks" + suffix;
    }

    private String code(World world) {
        return jdbc.sql("SELECT public_code FROM app_learning.deck_publication WHERE deck_id = :d").param("d", world.deck()).query(String.class).single();
    }

    @Test
    void anAuthorListsHisPublicDecksListedOrNotNewestFirstAndNothingElseToAGuest() {
        World first = listed("Первая");
        World small = belowThreshold("Маленькая");
        World last = listed("Последняя");
        World link = worlds.deck(author, "По ссылке", "Описание", 10);
        worlds.publish(link, DeckVisibility.LINK, CatalogWorlds.metadata("x"));
        World hidden = listed("Скрытая");
        worlds.hide(hidden);
        World gone = listed("Удалённая");
        worlds.delete(gone);
        World privateDeck = worlds.deck(author, "Личная", "Описание", 10);
        invitation("Приглашение", viewer);
        // the order is the time of the last publication, newest first; the deck below the threshold is there too
        List<String> expected = List.of(code(last), code(small), code(first));
        for (UUID reader : new UUID[] {null, stranger}) {
            JsonNode page = reader == null ? ok(get(path(""))) : ok(get(path(""), reader));
            assertThat(deckCodes(page)).containsExactlyElementsOf(expected);
            page.path("decks").forEach(entry -> assertThat(entry.path("access").stringValue(null)).isEqualTo("PUBLIC"));
        }
        assertThat(deckCodes(ok(get(path(""))))).doesNotContain(code(link), code(hidden), code(gone));
        assertThat(privateDeck).isNotNull();
        JsonNode page = ok(get(path("")));
        assertThat(page.propertyNames()).containsExactly("learningNowTotal", "decks", "nextCursor");
        assertThat(page.path("learningNowTotal").isNull()).isTrue();
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(page.path("decks").get(0).propertyNames()).containsExactly("access", "deck");
        assertThat(page.path("decks").get(0).path("deck").propertyNames()).containsExactlyElementsOf(CONTRACT.path("card").propertyNames());
        assertThat(page.path("decks").get(0).path("deck").path("ownerId").stringValue(null)).isEqualTo(author.toString());
    }

    /** The codes of the cards of the entries, in order (the entries are {@code {access, deck}}). */
    private static List<String> deckCodes(JsonNode page) {
        List<String> codes = new ArrayList<>();
        page.path("decks").forEach(entry -> codes.add(entry.path("deck").path("code").stringValue(null)));
        return codes;
    }

    @Test
    void aGranteeSeesTheInvitationsOfThatAuthorAfterThePublicDecksWithAnInvitationCard() {
        World a = listed("Публичная");
        World granted = invitation("Для группы", viewer);
        invitation("Чужая группа", null);
        invitation("Для другого", stranger);
        JsonNode page = ok(get(path(""), viewer));
        assertThat(page.path("decks")).hasSize(2);
        JsonNode invite = page.path("decks").get(1);
        assertThat(invite.path("access").stringValue(null)).isEqualTo("GRANTEE");
        JsonNode card = invite.path("deck");
        assertThat(card.propertyNames()).containsExactlyElementsOf(CONTRACT.path("card").propertyNames());
        JsonNode example = CONTRACT.path("author").path("response").path("decks").get(1).path("deck");
        for (String name : List.of("slug", "topic", "targetLanguage", "level", "addedCount", "learningNowCount")) assertThat(card.path(name).isNull()).as(name).isTrue();
        for (String name : List.of("tags", "media", "mechanics")) assertThat(card.path(name)).as(name).isEqualTo(example.path(name));
        assertThat(card.path("code").stringValue(null)).isEqualTo(code(granted));
        assertThat(card.path("title").stringValue(null)).isEqualTo("Для группы");
        assertThat(card.path("contentLanguage").stringValue(null)).isEqualTo("ru");
        assertThat(card.path("itemCount").intValue()).isEqualTo(4);
        assertThat(card.path("exerciseCount").intValue()).isEqualTo(1);
        assertThat(ok(get(path(""))).path("decks")).as("a guest has no grant").hasSize(1);
        assertThat(deckCodes(ok(get(path(""), stranger)))).doesNotContain(card.path("code").stringValue(null));
        // the grant is what shows the invitation: revoked, it is gone; lowered to private, it is gone; deleted, it is gone
        publications.revoke(author, granted.deck(), viewer);
        assertThat(ok(get(path(""), viewer)).path("decks")).hasSize(1);
        publications.grant(author, granted.deck(), viewer, GrantRole.VIEWER);
        assertThat(ok(get(path(""), viewer)).path("decks")).hasSize(2);
        worlds.setLevel(granted, DeckVisibility.PRIVATE);
        assertThat(ok(get(path(""), viewer)).path("decks")).hasSize(1);
        assertThat(a).isNotNull();
    }

    @Test
    void oneCursorWalksBothPartsWithoutRepeatingOrLosingADeck() {
        List<String> expected = new ArrayList<>();
        List<World> publicOnes = new ArrayList<>();
        for (int index = 0; index < 3; index++) publicOnes.add(listed("Публичная " + index));
        for (int index = 2; index >= 0; index--) expected.add(code(publicOnes.get(index)));
        List<World> invitations = new ArrayList<>();
        for (int index = 0; index < 3; index++) invitations.add(invitation("Приглашение " + index, viewer));
        for (int index = 2; index >= 0; index--) expected.add(code(invitations.get(index)));
        for (int size : new int[] {1, 2, 3, 4, 5, 6, 7, 48}) {
            List<String> seen = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                JsonNode page = ok(get(path("?limit=" + size + (cursor == null ? "" : "&cursor=" + cursor)), viewer));
                assertThat(page.path("decks").size()).isLessThanOrEqualTo(size);
                seen.addAll(deckCodes(page));
                cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").stringValue(null);
                assertThat(++pages).isLessThan(20);
            } while (cursor != null);
            assertThat(seen).as("page size %s", size).containsExactlyElementsOf(expected);
        }
        // a cursor belongs to its author and is not an anyone's
        String cursor = ok(get(path("?limit=2"), viewer)).path("nextCursor").stringValue(null);
        problem(get("/public/community/authors/" + UUID.randomUUID() + "/decks?cursor=" + cursor, viewer), 400, "INVALID_REQUEST");
        problem(get(path("?limit=2&cursor=garbage"), viewer), 400, "INVALID_REQUEST");
        // the cursor of the grant part, used by a guest, yields nothing and invents nothing
        String grants = ok(get(path("?limit=3"), viewer)).path("nextCursor").stringValue(null);
        assertThat(ok(get(path("?cursor=" + grants))).path("decks")).isEmpty();
    }

    @Test
    void hidingAndDeletingRemoveTheDeckAtOnceAndAnUnknownAuthorIsEmptyNotAnError() {
        World stays = listed("Остаётся");
        World hides = listed("Скроется");
        assertThat(ok(get(path(""))).path("decks")).hasSize(2);
        worlds.hide(hides);
        assertThat(deckCodes(ok(get(path(""))))).containsExactly(code(stays));
        assertThat(ok(get(path(""))).path("decks")).hasSize(1);
        JsonNode nobody = ok(get("/public/community/authors/" + UUID.randomUUID() + "/decks"));
        assertThat(nobody.path("decks")).isEmpty();
        assertThat(nobody.path("nextCursor").isNull()).isTrue();
        assertThat(nobody.path("learningNowTotal").isNull()).isTrue();
        for (String bad : List.of("not-a-uuid", "0198A6C0-1D3E-7A52-9B0C-2F6D8E4A1C35", "00000000-0000-0000-0000-000000000000", "0198a6c0-1d3e-7a52-9b0c-2f6d8e4a1c3", UUID.randomUUID() + "x")) {
            problem(get("/public/community/authors/" + bad + "/decks"), 400, "INVALID_REQUEST");
        }
        for (String bad : List.of("?limit=0", "?limit=49", "?limit=a", "?x=1", "?limit=1&limit=2", "?cursor=a&cursor=b")) problem(get(path(bad)), 400, "INVALID_REQUEST");
    }

    @Test
    void theLearnersOfTheAuthorAreTheSumOfHisPublicDecksShownLikeACard() {
        World a = listed("А");
        World b = belowThreshold("Б");
        World other = worlds.deck("Чужая", "Описание", 10);
        // 5 + 4 = 9 learners: nothing is shown; 70 + 51 = 121: floored to two digits; another author's decks never count
        setLearning(2000, a, 5, b, 4, other, 100);
        assertThat(ok(get(path(""))).path("learningNowTotal").isNull()).isTrue();
        setLearning(2001, a, 70, b, 51, other, 100);
        assertThat(ok(get(path(""))).path("learningNowTotal").intValue()).isEqualTo(120);
        // the unlisted deck carries its audience too: the generation has a row for every projection row
        setLearning(2002, a, 0, b, 12, other, 0);
        assertThat(ok(get(path(""))).path("learningNowTotal").intValue()).isEqualTo(12);
    }

    private void setLearning(long generation, World a, int learningA, World b, int learningB, World c, int learningC) {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("INSERT INTO app_learning.rank_generation(generation, started_at, completed_at, deck_count) VALUES (:g, now(), now(), 3)").param("g", generation).update();
            int[] counts = {learningA, learningB, learningC};
            UUID[] ids = {a.deck(), b.deck(), c.deck()};
            for (int index = 0; index < 3; index++) {
                jdbc.sql("INSERT INTO app_learning.deck_rank(generation, deck_id, score, rank, learning_rank, pop_pct, added_count, learning_now_count, activated_count, listed) "
                                + "VALUES (:g, :d, 0, :r, :r, 0, 0, :n, 0, :l)").param("g", generation).param("d", ids[index]).param("r", index == 1 ? null : index + 1)
                        .param("n", counts[index]).param("l", index != 1).update();
            }
            jdbc.sql("DELETE FROM app_learning.rank_generation WHERE generation < :g").param("g", generation).update();
        });
    }
}
