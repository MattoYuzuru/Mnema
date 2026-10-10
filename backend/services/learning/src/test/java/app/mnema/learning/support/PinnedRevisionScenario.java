package app.mnema.learning.support;

import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures.Material;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.quote;
import static app.mnema.learning.support.StudyFixtures.text;

/**
 * Share/5 (#427): a material revision that a deck reaches ONLY because one of its head exercises binds it. The author writes an
 * exercise quoting revision {@code pinned}, edits the material ({@code head}) and forks; the copy's journal is empty, so
 * {@code pinned} is neither its head nor in its journal. After the fork the source edits again ({@code later}) and authors
 * an exercise on it, which the copy does not have.
 */
public record PinnedRevisionScenario(UUID author, UUID source, UUID member, UUID node, UUID pinned, UUID head, UUID later,
                                     JsonNode inheritedExercise, JsonNode laterExercise, Copy copy, Material copyMaterial) {

    public static PinnedRevisionScenario build(SharedScopeFixture shared, StudyFixtures fixtures) {
        UUID author = UUID.randomUUID();
        UUID deck = shared.deck(author, "Источник");
        SharedScopeFixture.Material created = shared.create(author, deck, shared.document("memory"));
        UUID node = UUID.fromString(shared.document("memory").path("root").path("content").get(0).path("content").get(0)
                .path("id").stringValue(null));
        Material first = new Material(author, deck, created.member(), created.revision(), node, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        JsonNode inherited = fixtures.publish(first, fixtures.freeResponse(first, blocks(quote(first, node), text("Type it")), blocks(),
                "memory"), "Inherited");
        UUID head = shared.save(author, deck, created.member(), created.revision(), shared.document("rewritten before the fork"));
        Copy copy = shared.copy(deck, UUID.randomUUID());
        Material copyMaterial = new Material(copy.owner(), copy.deckId(), created.member(), head, node, first.distractor(),
                first.divider(), first.root());
        UUID later = shared.save(author, deck, created.member(), head, shared.document("rewritten after the fork"));
        Material second = new Material(author, deck, created.member(), later, node, first.distractor(), first.divider(), first.root());
        JsonNode laterExercise = fixtures.publish(second, fixtures.freeResponse(second, blocks(quote(second, node), text("Later")),
                blocks(), "memory"), "After the fork");
        return new PinnedRevisionScenario(author, deck, created.member(), node, created.revision(), head, later, inherited,
                laterExercise, copy, copyMaterial);
    }
}
