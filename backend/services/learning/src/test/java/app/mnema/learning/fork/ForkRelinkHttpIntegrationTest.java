package app.mnema.learning.fork;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.library.GrantRole;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.UUID;

import static app.mnema.learning.fork.ForkWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/6 part A (#445), the re-link: a copy whose source rotated its code reads «источник недоступен»; when its owner opens the NEW link and presses «Добавить к себе» again, the
 * copy command answers the existing copy as always and the copy stores the code that works, so the source is available and the updates resume. Nothing else may re-link it: not the
 * code of another deck, not another account, not a read. No executor runs in this context, so the test makes a copy ready itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"learning.community.forks.max-preparing=100", "learning.community.forks.max-per-window=1000"})
class ForkRelinkHttpIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;
    @Autowired private app.mnema.learning.library.DeckPublications publications;
    @Autowired private app.mnema.learning.library.DeckModeration moderation;
    private ForkWorlds worlds;
    private final UUID owner = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    @BeforeEach
    void worlds() {
        worlds = new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private UUID readyCopy(UUID account, Source source) {
        UUID copy = worlds.forked(account, source.code());
        worlds.markReady(copy);
        return copy;
    }

    private String storedCode(UUID copy) {
        return jdbc.sql("SELECT upstream_code FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(String.class).single();
    }

    private String rowMark(UUID copy) {
        return jdbc.sql("SELECT xmin::text FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(String.class).single();
    }

    @Test
    void aRotatedCodeIsReplacedWhenTheCopierOpensTheNewLinkAndTheUpdatesResume() {
        Source source = worlds.source("PUBLIC", 3, 1);
        UUID copy = readyCopy(owner, source);
        String old = source.code();
        assertThat(worlds.forkOf(owner, copy).path("sourceAvailable").booleanValue(false)).isTrue();

        // the author lowers the level: the code is rotated, the copy reads «источник недоступен» and an update published meanwhile is not offered
        String rotated = worlds.level(source.author(), source.deck(), "LINK");
        assertThat(rotated).isNotEqualTo(old);
        worlds.republish(source, "LINK");
        JsonNode closed = worlds.forkOf(owner, copy);
        assertThat(closed.path("sourceAvailable").booleanValue(true)).isFalse();
        assertThat(closed.path("updateAvailable").booleanValue(true)).isFalse();
        assertThat(storedCode(copy)).isEqualTo(old);
        // nothing re-linked it by itself: reading is not a write
        String mark = rowMark(copy);
        worlds.get("/decks/" + copy, owner);
        worlds.get("/decks?limit=100", owner);
        assertThat(rowMark(copy)).isEqualTo(mark);

        // the copier opens the new link and presses «Добавить к себе»: the existing copy is answered, and it is re-linked
        HttpResponse<String> again = worlds.fork(owner, rotated);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(json(again).path("existing").booleanValue(false)).isTrue();
        assertThat(json(again).path("deckId").stringValue(null)).isEqualTo(copy.toString());
        assertThat(storedCode(copy)).isEqualTo(rotated);
        JsonNode open = worlds.forkOf(owner, copy);
        assertThat(open.path("sourceAvailable").booleanValue(false)).isTrue();
        assertThat(open.path("upstreamCode").stringValue(null)).isEqualTo(rotated);
        assertThat(open.path("upstreamTitle").stringValue(null)).startsWith("Источник");
        // the update published while the link was dark is offered, and so is the next one
        assertThat(open.path("updateAvailable").booleanValue(false)).isTrue();
        worlds.moveBase(copy, worlds.publishedRevision(source.deck()));
        assertThat(worlds.forkOf(owner, copy).path("updateAvailable").booleanValue(true)).isFalse();
        worlds.republish(source, "LINK");
        assertThat(worlds.forkOf(owner, copy).path("updateAvailable").booleanValue(false)).isTrue();
        // myCopy of the public summary is as before
        assertThat(json(worlds.get("/public/decks/" + rotated, owner)).path("myCopy").path("deckId").stringValue(null)).isEqualTo(copy.toString());

        // the old code no longer opens anything and creates nothing
        assertThat(worlds.fork(owner, old).statusCode()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :o AND upstream_deck_id = :s AND released_at IS NULL")
                .param("o", owner).param("s", source.deck()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theCommandStaysIdempotentAndAnUnchangedCodeIsNoWrite() {
        Source source = worlds.source("PUBLIC", 2, 0);
        UUID copy = readyCopy(owner, source);
        String rotated = worlds.level(source.author(), source.deck(), "LINK");

        UUID command = UUID.randomUUID();
        HttpResponse<String> first = worlds.fork(owner, command, rotated);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(storedCode(copy)).isEqualTo(rotated);
        String mark = rowMark(copy);

        // an exact retry is replayed with the original answer; another command for the same code answers the existing copy and writes nothing
        HttpResponse<String> replay = worlds.fork(owner, command, rotated);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("idempotency-replayed")).contains("true");
        assertThat(replay.body()).isEqualTo(first.body());
        HttpResponse<String> another = worlds.fork(owner, rotated);
        assertThat(another.statusCode()).isEqualTo(200);
        assertThat(json(another).path("existing").booleanValue(false)).isTrue();
        assertThat(rowMark(copy)).as("the stored code was already the one that works").isEqualTo(mark);
        // the same command id with another code is the house conflict, not a re-link
        Source other = worlds.source("LINK", 1, 0);
        HttpResponse<String> conflict = worlds.fork(owner, command, other.code());
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json(conflict).path("code").stringValue(null)).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(storedCode(copy)).isEqualTo(rotated);
        // the code the copy was made by (still the current one) changes nothing as well
        Source steady = worlds.source("LINK", 1, 0);
        UUID steadyCopy = readyCopy(owner, steady);
        String steadyMark = rowMark(steadyCopy);
        assertThat(worlds.fork(owner, steady.code()).statusCode()).isEqualTo(200);
        assertThat(rowMark(steadyCopy)).isEqualTo(steadyMark);
    }

    @Test
    void aCodeOfAnotherDeckNeverRelinksAndAnotherAccountIsUnaffected() {
        Source source = worlds.source("PUBLIC", 2, 0);
        UUID copy = readyCopy(owner, source);
        String old = source.code();
        String rotated = worlds.level(source.author(), source.deck(), "LINK");
        Source other = worlds.source("LINK", 2, 0);
        UUID otherCopy = readyCopy(owner, other);
        String mark = rowMark(copy);

        // the code of the other deck answers the other deck's copy, the first copy is untouched
        HttpResponse<String> answer = worlds.fork(owner, other.code());
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(json(answer).path("deckId").stringValue(null)).isEqualTo(otherCopy.toString());
        assertThat(storedCode(copy)).isEqualTo(old);
        assertThat(rowMark(copy)).isEqualTo(mark);
        assertThat(storedCode(otherCopy)).isEqualTo(other.code());

        // a deck the account has no copy of is copied as always, and the dark copy stays dark
        Source fresh = worlds.source("LINK", 1, 0);
        assertThat(worlds.fork(owner, fresh.code()).statusCode()).isEqualTo(201);
        assertThat(storedCode(copy)).isEqualTo(old);

        // a source made private: its CURRENT code (the one the level change returned) is the opaque 404 and the stored code stays
        Source closed = worlds.source("LINK", 1, 0);
        UUID closedCopy = readyCopy(owner, closed);
        String closedStored = storedCode(closedCopy);
        // the publication read hides the code of a private deck, so the current code is read from the row itself
        worlds.level(closed.author(), closed.deck(), "PRIVATE");
        String closedCurrent = jdbc.sql("SELECT public_code FROM app_learning.deck_publication WHERE deck_id = :d").param("d", closed.deck()).query(String.class).single();
        assertThat(closedCurrent).isNotEqualTo(closedStored);
        assertThat(worlds.fork(owner, closedCurrent).statusCode()).isEqualTo(404);
        assertThat(worlds.fork(owner, closedStored).statusCode()).isEqualTo(404);
        assertThat(storedCode(closedCopy)).isEqualTo(closedStored);
        // an unknown code and a guest: nothing is re-linked
        assertThat(worlds.fork(owner, "zzzzzzzzzz").statusCode()).isEqualTo(404);
        assertThat(worlds.fork(null, rotated).statusCode()).isEqualTo(401);
        assertThat(storedCode(copy)).isEqualTo(old);

        // another account with the new link makes ITS OWN copy; the dark copy of the first account is still dark
        UUID stranger = UUID.randomUUID();
        HttpResponse<String> theirs = worlds.fork(stranger, rotated);
        assertThat(theirs.statusCode()).isEqualTo(201);
        assertThat(json(theirs).path("deckId").stringValue(null)).isNotEqualTo(copy.toString());
        assertThat(storedCode(copy)).isEqualTo(old);
        assertThat(worlds.forkOf(owner, copy).path("sourceAvailable").booleanValue(true)).isFalse();
        // and the source's owner cannot copy his own deck, with any code
        assertThat(worlds.fork(source.author(), rotated).statusCode()).isEqualTo(409);
        assertThat(storedCode(copy)).isEqualTo(old);
    }

    @Test
    void aDetachedCopyKeepsItsCodeAndAPreparingCopyIsRelinked() {
        Source detachedSource = worlds.source("PUBLIC", 2, 0);
        UUID detached = readyCopy(owner, detachedSource);
        String old = detachedSource.code();
        assertThat(worlds.send("POST", "/decks/" + detached + "/fork:detach", owner, null, null).statusCode()).isEqualTo(200);
        String rotated = worlds.level(detachedSource.author(), detachedSource.deck(), "LINK");
        HttpResponse<String> answer = worlds.fork(owner, rotated);
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(json(answer).path("deckId").stringValue(null)).isEqualTo(detached.toString());
        assertThat(storedCode(detached)).as("a detached copy has no use for a link").isEqualTo(old);

        Source preparingSource = worlds.source("PUBLIC", 2, 0);
        UUID preparing = worlds.forked(UUID.randomUUID(), preparingSource.code());
        UUID preparingOwner = jdbc.sql("SELECT owner_id FROM app_learning.deck WHERE deck_id = :d").param("d", preparing).query(UUID.class).single();
        String preparingRotated = worlds.level(preparingSource.author(), preparingSource.deck(), "LINK");
        HttpResponse<String> relinked = worlds.fork(preparingOwner, preparingRotated);
        assertThat(relinked.statusCode()).isEqualTo(200);
        assertThat(json(relinked).path("preparing").booleanValue(false)).isTrue();
        assertThat(storedCode(preparing)).isEqualTo(preparingRotated);
    }

    @Test
    void anInvitationSourceReLinksOnlyForAGrantee() {
        Source source = worlds.source("LINK", 2, 0);
        UUID copy = readyCopy(owner, source);
        String old = source.code();
        // the author lowers the level to INVITE: the code rotates and only grantees may open it
        String rotated = worlds.level(source.author(), source.deck(), "INVITE");
        assertThat(rotated).isNotEqualTo(old);
        String mark = rowMark(copy);

        HttpResponse<String> refused = worlds.fork(owner, rotated);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(json(refused).path("code").stringValue(null)).isEqualTo("DECK_INVITE_ONLY");
        assertThat(storedCode(copy)).isEqualTo(old);
        assertThat(rowMark(copy)).as("nothing was written").isEqualTo(mark);
        assertThat(worlds.forkOf(owner, copy).path("sourceAvailable").booleanValue(true)).isFalse();

        publications.grant(source.author(), source.deck(), owner, GrantRole.VIEWER);
        HttpResponse<String> granted = worlds.fork(owner, rotated);
        assertThat(granted.statusCode()).as(granted.body()).isEqualTo(200);
        assertThat(json(granted).path("existing").booleanValue(false)).isTrue();
        assertThat(json(granted).path("deckId").stringValue(null)).isEqualTo(copy.toString());
        assertThat(storedCode(copy)).isEqualTo(rotated);
        JsonNode fork = worlds.forkOf(owner, copy);
        assertThat(fork.path("sourceAvailable").booleanValue(false)).isTrue();
        assertThat(fork.path("upstreamCode").stringValue(null)).isEqualTo(rotated);
    }

    @Test
    void aSourceHiddenByModerationIsNotReLinkedEvenByItsCurrentCode() {
        Source source = worlds.source("PUBLIC", 2, 0);
        UUID copy = readyCopy(owner, source);
        String old = source.code();
        String rotated = worlds.level(source.author(), source.deck(), "LINK");
        Boolean hidden = new TransactionTemplate(transactions).execute(status -> moderation.hide(source.deck(), java.time.Instant.now()));
        assertThat(hidden).isTrue();
        String mark = rowMark(copy);

        assertThat(worlds.fork(owner, rotated).statusCode()).isEqualTo(404);
        assertThat(worlds.fork(owner, old).statusCode()).isEqualTo(404);
        assertThat(storedCode(copy)).isEqualTo(old);
        assertThat(rowMark(copy)).as("nothing was written").isEqualTo(mark);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :o AND upstream_deck_id = :s AND released_at IS NULL")
                .param("o", owner).param("s", source.deck()).query(Long.class).single()).isEqualTo(1);

        // lifted again, the same code re-links
        Boolean lifted = new TransactionTemplate(transactions).execute(status -> moderation.unhide(source.deck()));
        assertThat(lifted).isTrue();
        assertThat(worlds.fork(owner, rotated).statusCode()).isEqualTo(200);
        assertThat(storedCode(copy)).isEqualTo(rotated);
    }
}
