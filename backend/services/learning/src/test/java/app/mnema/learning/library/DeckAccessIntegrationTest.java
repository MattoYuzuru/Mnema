package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CD-3 on a real PostgreSQL: who resolves to what, by code and by id, and how the owner's level and grant operations behave. */
@SpringBootTest
class DeckAccessIntegrationTest extends PostgresIntegrationTest {
    @Autowired private DeckAccess access;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private LibraryRepository repository;
    private LibraryFixtures fixtures;

    private final UUID owner = UUID.randomUUID();
    private final UUID grantee = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void fixtures() { fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications); }

    private enum Who { OWNER, GRANTEE, OTHER, GUEST }

    private Viewer viewer(Who who) {
        return switch (who) {
            case OWNER -> Viewer.account(owner, "203.0.113.1");
            case GRANTEE -> Viewer.account(grantee, "203.0.113.2");
            case OTHER -> Viewer.account(other, "203.0.113.3");
            case GUEST -> Viewer.guest("203.0.113.4");
        };
    }

    private static final String NF = "NOT_FOUND";
    private static final String INVITE_ONLY = "INVITE_ONLY";

    private String outcome(Resolution resolution) {
        return resolution.granted() ? resolution.level().name() : resolution.denial().name();
    }

    private void expectByCode(String label, String code, String owner, String grantee, String other, String guest) {
        assertThat(List.of(outcome(access.resolve(viewer(Who.OWNER), code)), outcome(access.resolve(viewer(Who.GRANTEE), code)),
                outcome(access.resolve(viewer(Who.OTHER), code)), outcome(access.resolve(viewer(Who.GUEST), code))))
                .as(label + " by code, in order owner/grantee/other/guest").containsExactly(owner, grantee, other, guest);
    }

    private void expectById(String label, UUID deck, String owner, String grantee, String other, String guest) {
        assertThat(List.of(outcome(access.resolve(viewer(Who.OWNER), deck)), outcome(access.resolve(viewer(Who.GRANTEE), deck)),
                outcome(access.resolve(viewer(Who.OTHER), deck)), outcome(access.resolve(viewer(Who.GUEST), deck))))
                .as(label + " by id, in order owner/grantee/other/guest").containsExactly(owner, grantee, other, guest);
    }

    private Deck granted(String title) {
        Deck deck = fixtures.deck(owner, title);
        // A grant alone opens nothing: every deck below has one for the grantee, and only the INVITE level honours it.
        publications.grant(owner, deck.id(), grantee, GrantRole.VIEWER);
        return deck;
    }

    @Test
    void theResolutionMatrixHoldsForEveryLevelStateAndViewer() {
        Deck neverPublished = granted("never");
        Deck privateAgain = granted("private again");
        Deck link = granted("link");
        Deck invite = granted("invite");
        Deck pub = granted("public");
        Deck linkNoRevision = granted("link without revision");
        Deck inviteNoRevision = granted("invite without revision");
        Deck deleted = granted("deleted");
        Deck rotated = granted("rotated");

        fixtures.publishAt(privateAgain, DeckVisibility.PUBLIC);
        String privateCode = fixtures.level(privateAgain, DeckVisibility.PRIVATE);
        String linkCode = fixtures.publishAt(link, DeckVisibility.LINK);
        String inviteCode = fixtures.publishAt(invite, DeckVisibility.INVITE);
        String publicCode = fixtures.publishAt(pub, DeckVisibility.PUBLIC);
        String linkNoRevisionCode = fixtures.level(linkNoRevision, DeckVisibility.LINK);
        String inviteNoRevisionCode = fixtures.level(inviteNoRevision, DeckVisibility.INVITE);
        String deletedCode = fixtures.publishAt(deleted, DeckVisibility.PUBLIC);
        decks.delete(owner, deleted.id(), Long.parseLong(decks.read(owner, deleted.id()).path("rowVersion").stringValue(null)));
        String oldCode = fixtures.publishAt(rotated, DeckVisibility.PUBLIC);
        String newCode = fixtures.level(rotated, DeckVisibility.INVITE);
        assertThat(newCode).isNotEqualTo(oldCode);

        expectByCode("private again", privateCode, "OWNER", NF, NF, NF);
        expectByCode("link", linkCode, "OWNER", "LINK", "LINK", "LINK");
        expectByCode("invite", inviteCode, "OWNER", "GRANTEE", INVITE_ONLY, INVITE_ONLY);
        expectByCode("public", publicCode, "OWNER", "PUBLIC", "PUBLIC", "PUBLIC");
        expectByCode("link without a published revision", linkNoRevisionCode, "OWNER", NF, NF, NF);
        expectByCode("invite without a published revision", inviteNoRevisionCode, "OWNER", NF, NF, NF);
        expectByCode("deleted", deletedCode, NF, NF, NF, NF);
        expectByCode("rotated away", oldCode, NF, NF, NF, NF);
        expectByCode("rotated (invite)", newCode, "OWNER", "GRANTEE", INVITE_ONLY, INVITE_ONLY);
        expectByCode("unknown", PublicCodes.next(new SecureRandom()), NF, NF, NF, NF);
        for (String malformed : new String[] {null, "", "x", "0000000000", "abcdefghij ", "../../../"}) {
            expectByCode("malformed " + malformed, malformed, NF, NF, NF, NF);
        }

        expectById("never published", neverPublished.id(), "OWNER", NF, NF, NF);
        expectById("private again", privateAgain.id(), "OWNER", NF, NF, NF);
        expectById("link", link.id(), "OWNER", NF, NF, NF);
        expectById("invite", invite.id(), "OWNER", "GRANTEE", NF, NF);
        expectById("public", pub.id(), "OWNER", NF, NF, NF);
        expectById("link without a published revision", linkNoRevision.id(), "OWNER", NF, NF, NF);
        expectById("invite without a published revision", inviteNoRevision.id(), "OWNER", NF, NF, NF);
        expectById("deleted", deleted.id(), NF, NF, NF, NF);
        expectById("rotated (invite)", rotated.id(), "OWNER", "GRANTEE", NF, NF);
        expectById("unknown", UUID.randomUUID(), NF, NF, NF, NF);
    }

    @Test
    void aGrantedResolutionCarriesThePublishedRevisionAndADenialCarriesNothing() {
        Deck deck = granted("carry");
        String code = fixtures.publishAt(deck, DeckVisibility.INVITE);
        Resolution granted = access.resolve(viewer(Who.GRANTEE), code);
        assertThat(granted.level()).isEqualTo(AccessLevel.GRANTEE);
        assertThat(granted.deck().deckId()).isEqualTo(deck.id());
        assertThat(granted.deck().ownerId()).isEqualTo(owner);
        assertThat(granted.deck().code()).isEqualTo(code);
        assertThat(granted.deck().published().title()).isEqualTo("carry");
        assertThat(granted.deck().published().memberCount()).isEqualTo(1);
        assertThat(granted.deck().published().exerciseCount()).isEqualTo(1);
        Resolution denied = access.resolve(viewer(Who.OTHER), code);
        assertThat(denied.deck()).isNull();
        assertThat(denied.denial()).isEqualTo(Denial.INVITE_ONLY);
        assertThatThrownBy(() -> new Resolution(AccessLevel.NONE, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Resolution(AccessLevel.LINK, Denial.NOT_FOUND, granted.deck())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokingAGrantClosesAnInviteDeckAgain() {
        Deck deck = fixtures.deck(owner, "revoke");
        String code = fixtures.publishAt(deck, DeckVisibility.INVITE);
        assertThat(outcome(access.resolve(viewer(Who.GRANTEE), code))).isEqualTo(INVITE_ONLY);
        publications.grant(owner, deck.id(), grantee, GrantRole.VIEWER);
        assertThat(outcome(access.resolve(viewer(Who.GRANTEE), code))).isEqualTo("GRANTEE");
        assertThat(publications.revoke(owner, deck.id(), grantee)).isTrue();
        assertThat(publications.revoke(owner, deck.id(), grantee)).isFalse();
        assertThat(outcome(access.resolve(viewer(Who.GRANTEE), code))).isEqualTo(INVITE_ONLY);
        assertThat(outcome(access.resolve(viewer(Who.GRANTEE), deck.id()))).isEqualTo(NF);
    }

    @Test
    void changingTheLevelNeverTouchesTheDeckRow() {
        Deck deck = fixtures.deck(owner, "untouched");
        String before = jdbc.sql("SELECT to_jsonb(d)::text FROM app_learning.deck d WHERE deck_id = :deck").param("deck", deck.id())
                .query(String.class).single();
        for (DeckVisibility level : new DeckVisibility[] {DeckVisibility.PUBLIC, DeckVisibility.INVITE, DeckVisibility.LINK,
                DeckVisibility.PRIVATE, DeckVisibility.PUBLIC}) {
            fixtures.level(deck, level);
            assertThat(jdbc.sql("SELECT to_jsonb(d)::text FROM app_learning.deck d WHERE deck_id = :deck").param("deck", deck.id())
                    .query(String.class).single()).as("deck row after " + level).isEqualTo(before);
        }
        assertThat(decks.read(owner, deck.id()).path("rowVersion").stringValue(null)).isEqualTo(
                jdbc.sql("SELECT row_version::text FROM app_learning.deck WHERE deck_id = :deck").param("deck", deck.id()).query(String.class).single());
    }

    @Test
    void theCodeRotatesExactlyWhenTheLevelIsLowered() {
        for (DeckVisibility from : DeckVisibility.values()) {
            for (DeckVisibility to : DeckVisibility.values()) {
                Deck deck = fixtures.deck(owner, from + ">" + to);
                String first = fixtures.level(deck, from == DeckVisibility.PRIVATE ? DeckVisibility.LINK : from);
                if (from == DeckVisibility.PRIVATE) first = fixtures.level(deck, DeckVisibility.PRIVATE);
                Publication before = publications.find(owner, deck.id()).orElseThrow();
                assertThat(before.publicCode()).isEqualTo(first);
                Publication after = publications.setVisibility(owner, deck.id(), to, before.rowVersion()).orElseThrow();
                String label = from + " -> " + to;
                if (from == to) {
                    assertThat(after).as(label).isEqualTo(before);
                    continue;
                }
                assertThat(after.visibility()).as(label).isEqualTo(to);
                assertThat(after.rowVersion()).as(label).isEqualTo(before.rowVersion() + 1);
                if (from.lowersTo(to)) {
                    assertThat(after.publicCode()).as(label).isNotEqualTo(before.publicCode());
                    assertThat(after.codeRotatedAt()).as(label).isAfter(before.codeRotatedAt());
                } else {
                    assertThat(after.publicCode()).as(label).isEqualTo(before.publicCode());
                    assertThat(after.codeRotatedAt()).as(label).isEqualTo(before.codeRotatedAt());
                }
            }
        }
    }

    @Test
    void anOldCodeStopsWorkingAtOnceAfterALowering() {
        Deck deck = fixtures.deck(owner, "lowered");
        String code = fixtures.publishAt(deck, DeckVisibility.PUBLIC);
        assertThat(outcome(access.resolve(viewer(Who.GUEST), code))).isEqualTo("PUBLIC");
        String rotated = fixtures.level(deck, DeckVisibility.INVITE);
        assertThat(outcome(access.resolve(viewer(Who.GUEST), code))).isEqualTo(NF);
        assertThat(outcome(access.resolve(viewer(Who.GUEST), rotated))).isEqualTo(INVITE_ONLY);
        // raising it again keeps the new code, so a link shared after the lowering survives
        assertThat(fixtures.level(deck, DeckVisibility.LINK)).isEqualTo(rotated);
        assertThat(outcome(access.resolve(viewer(Who.GUEST), rotated))).isEqualTo("LINK");
    }

    @Test
    void theVersionOfThePublicationRowIsACompareAndSet() {
        Deck deck = fixtures.deck(owner, "cas");
        assertThat(publications.find(owner, deck.id())).isEmpty();
        assertThat(publications.setVisibility(owner, deck.id(), DeckVisibility.PRIVATE, DeckPublications.UNPUBLISHED)).isEmpty();
        assertThatThrownBy(() -> publications.setVisibility(owner, deck.id(), DeckVisibility.LINK, 0)).isInstanceOf(VersionConflictException.class);
        Publication created = publications.setVisibility(owner, deck.id(), DeckVisibility.LINK, DeckPublications.UNPUBLISHED).orElseThrow();
        assertThat(created.rowVersion()).isZero();
        assertThat(created.publishedRevisionId()).isNull();
        assertThat(created.publishedAt()).isNull();
        assertThat(PublicCodes.valid(created.publicCode())).isTrue();
        assertThatThrownBy(() -> publications.setVisibility(owner, deck.id(), DeckVisibility.PUBLIC, DeckPublications.UNPUBLISHED))
                .isInstanceOf(VersionConflictException.class);
        Publication raised = publications.setVisibility(owner, deck.id(), DeckVisibility.PUBLIC, 0).orElseThrow();
        assertThat(raised.rowVersion()).isOne();
        assertThatThrownBy(() -> publications.setVisibility(owner, deck.id(), DeckVisibility.LINK, 0)).isInstanceOf(VersionConflictException.class);
        assertThat(publications.find(owner, deck.id())).contains(raised);
        assertThatThrownBy(() -> publications.setVisibility(owner, deck.id(), null, 1)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> publications.setVisibility(owner, deck.id(), DeckVisibility.LINK, -2)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void onlyTheOwnerOfALiveDeckCanChangeItsLevelOrGrants() {
        Deck deck = fixtures.deck(owner, "owned");
        assertThatThrownBy(() -> publications.setVisibility(other, deck.id(), DeckVisibility.PUBLIC, DeckPublications.UNPUBLISHED))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> publications.find(other, deck.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> publications.grant(other, deck.id(), grantee, GrantRole.VIEWER)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> publications.revoke(other, deck.id(), grantee)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> publications.setVisibility(owner, UUID.randomUUID(), DeckVisibility.PUBLIC, DeckPublications.UNPUBLISHED))
                .isInstanceOf(ResourceNotFoundException.class);
        fixtures.level(deck, DeckVisibility.PUBLIC);
        decks.delete(owner, deck.id(), Long.parseLong(decks.read(owner, deck.id()).path("rowVersion").stringValue(null)));
        assertThatThrownBy(() -> publications.setVisibility(owner, deck.id(), DeckVisibility.PRIVATE, 0)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :deck").param("deck", deck.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void grantsAreIdempotentViewerOnlyAndNeverForTheOwner() {
        Deck deck = fixtures.deck(owner, "grants");
        publications.grant(owner, deck.id(), grantee, GrantRole.VIEWER);
        var first = jdbc.sql("SELECT granted_at, granted_by, role FROM app_learning.deck_access_grant WHERE deck_id = :deck")
                .param("deck", deck.id()).query((row, n) -> row.getTimestamp(1) + "/" + row.getObject(2, UUID.class) + "/" + row.getString(3)).single();
        publications.grant(owner, deck.id(), grantee, GrantRole.VIEWER);
        assertThat(jdbc.sql("SELECT granted_at, granted_by, role FROM app_learning.deck_access_grant WHERE deck_id = :deck")
                .param("deck", deck.id()).query((row, n) -> row.getTimestamp(1) + "/" + row.getObject(2, UUID.class) + "/" + row.getString(3)).list())
                .containsExactly(first);
        assertThat(first).endsWith("/" + owner + "/VIEWER");
        assertThatThrownBy(() -> publications.grant(owner, deck.id(), other, GrantRole.EDITOR)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> publications.grant(owner, deck.id(), owner, GrantRole.VIEWER)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> publications.grant(owner, deck.id(), other, null)).isInstanceOf(InvalidRequestException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :deck").param("deck", deck.id())
                .query(Long.class).single()).isOne();
    }

    @Test
    void theFirstPublicationOfOneDeckByTwoRequestsLeavesExactlyOneRow() throws Exception {
        Deck deck = fixtures.deck(owner, "race");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        publications.setVisibility(owner, deck.id(), DeckVisibility.LINK, DeckPublications.UNPUBLISHED);
                        return "ok";
                    } catch (VersionConflictException conflict) {
                        return "conflict";
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) outcomes.add(result.get());
            assertThat(outcomes).containsOnlyOnce("ok").filteredOn("conflict"::equals).hasSize(3);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_publication WHERE deck_id = :deck").param("deck", deck.id())
                .query(Long.class).single()).isOne();
    }

    /** A source of randomness that first replays a taken code and then continues with real draws. */
    private static final class Colliding extends SecureRandom {
        private static final long serialVersionUID = 1L;
        private final String taken;
        private int drawn;

        Colliding(String taken) { this.taken = taken; }

        @Override public int nextInt(int bound) {
            int position = drawn++;
            if (position < PublicCodes.LENGTH) return "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".indexOf(taken.charAt(position));
            return super.nextInt(bound);
        }
    }

    @Test
    void aCodeThatCollidesWithAnotherDecksIsRedrawnInAFreshTransaction() {
        Deck first = fixtures.deck(owner, "first");
        Deck second = fixtures.deck(owner, "second");
        String taken = fixtures.publishAt(first, DeckVisibility.LINK);
        DeckPublications colliding = new DeckPublications(repository, transactions, new Colliding(taken));
        Publication created = colliding.setVisibility(owner, second.id(), DeckVisibility.LINK, DeckPublications.UNPUBLISHED).orElseThrow();
        assertThat(created.publicCode()).isNotEqualTo(taken);
        assertThat(PublicCodes.valid(created.publicCode())).isTrue();
        assertThat(outcome(access.resolve(viewer(Who.GUEST), taken))).isEqualTo("LINK");
        assertThat(access.resolve(viewer(Who.GUEST), taken).deck().deckId()).isEqualTo(first.id());
        assertThat(access.resolve(viewer(Who.GUEST), created.publicCode()).deck()).isNull();
    }

    @Test
    void theSchemaRejectsWhatTheCodeNeverWrites() {
        Deck deck = fixtures.deck(owner, "schema");
        Deck foreign = fixtures.deck(owner, "foreign");
        fixtures.level(deck, DeckVisibility.LINK);
        fixtures.publishHead(deck.id());
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_publication SET public_code = 'short', row_version = row_version + 1 WHERE deck_id = :deck")
                .param("deck", deck.id()).update()).hasMessageContaining("deck_publication_public_code_check");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_publication SET visibility = 'SECRET', row_version = row_version + 1 WHERE deck_id = :deck")
                .param("deck", deck.id()).update()).hasMessageContaining("deck_publication_visibility_check");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_publication SET row_version = row_version + 2 WHERE deck_id = :deck")
                .param("deck", deck.id()).update()).hasMessageContaining("Invalid deck publication transition");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_publication SET created_at = created_at + interval '1 day', row_version = row_version + 1 WHERE deck_id = :deck")
                .param("deck", deck.id()).update()).hasMessageContaining("Invalid deck publication transition");
        // a revision of another deck can never be published
        UUID foreignRevision = jdbc.sql("SELECT head_revision_id FROM app_learning.deck WHERE deck_id = :deck").param("deck", foreign.id())
                .query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_publication SET published_revision_id = :revision, published_at = now(), row_version = row_version + 1 WHERE deck_id = :deck")
                .param("revision", foreignRevision).param("deck", deck.id()).update()).hasMessageContaining("deck_publication_revision_fkey");
        // a revision without a time (and the reverse) is inconsistent
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_publication SET published_at = NULL, row_version = row_version + 1 WHERE deck_id = :deck")
                .param("deck", deck.id()).update()).hasMessageContaining("deck_publication_check");
        // codes are unique across decks
        String code = publications.find(owner, deck.id()).orElseThrow().publicCode();
        fixtures.level(foreign, DeckVisibility.LINK);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_publication SET public_code = :code, row_version = row_version + 1 WHERE deck_id = :deck")
                .param("code", code).param("deck", foreign.id()).update()).hasMessageContaining("deck_publication_code_key");
        // roles are VIEWER or the reserved EDITOR
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.deck_access_grant(deck_id, grantee_id, role, granted_by, granted_at) VALUES (:deck, :grantee, 'OWNER', :by, now())")
                .param("deck", deck.id()).param("grantee", grantee).param("by", owner).update()).hasMessageContaining("deck_access_grant_role_check");
        jdbc.sql("INSERT INTO app_learning.deck_access_grant(deck_id, grantee_id, role, granted_by, granted_at) VALUES (:deck, :grantee, 'EDITOR', :by, now())")
                .param("deck", deck.id()).param("grantee", grantee).param("by", owner).update();
        // an EDITOR row is still a reader (co-authors come later); it is the code, not the schema, that never writes one
        assertThat(repository.hasGrant(deck.id(), grantee)).isTrue();
    }
}
