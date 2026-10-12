package app.mnema.learning.community;

import app.mnema.learning.library.DeckSlug;
import app.mnema.learning.library.TopicDirectory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * The deck card of the catalogue routes (contract {@code contracts/community/catalog.json}, {@code card}), in ONE place for facets-free lists, shelves and the author list. Never a
 * deck id, a revision id, a score, a rank or an exact audience below {@link AudienceCounts#MIN_SHOWN}: the counts pass through {@link AudienceCounts#shown}.
 *
 * <ul>
 *   <li>{@code slug} is {@link DeckSlug} of the title (computed, never stored); {@code description} is cut to {@value #DESCRIPTION_MAX} code points, ellipsis included.</li>
 *   <li>{@code topic} is {@code {id, name (Russian), parentId}} from the topic directory; {@code media} and {@code mechanics} are the words of {@link CatalogMedia} and
 *       {@link CatalogMechanic} in the order of their declarations.</li>
 *   <li>{@code addedCount} and {@code learningNowCount} are the numbers of the rank generation the list was cut from (a deck the generation does not know has none), displayed.</li>
 *   <li>{@code updatedAt} is {@code content_updated_at} at whole seconds.</li>
 * </ul>
 * The card of an invitation-only deck seen by a grantee ({@link #granted}) has no slug (its link is {@code /d/{code}}), topic, levels, tags, media, mechanics or counts.
 */
@Component
class CatalogCards {
    static final int DESCRIPTION_MAX = 300;
    private static final String ELLIPSIS = "…";

    private final TopicDirectory topics;

    CatalogCards(TopicDirectory topics) { this.topics = topics; }

    ObjectNode card(PublicDeckRow row, Map<UUID, CatalogRanks.Counts> counts) {
        CatalogRanks.Counts known = counts.getOrDefault(row.deckId(), CatalogRanks.Counts.NONE);
        ObjectNode card = JsonNodeFactory.instance.objectNode();
        card.put("code", row.publicCode()).put("slug", DeckSlug.of(row.title())).put("title", row.title()).put("description", describe(row.description()));
        if (row.topicId() == null) card.putNull("topic");
        else card.set("topic", topic(row.topicId()));
        putText(card, "contentLanguage", row.contentLanguage());
        putText(card, "targetLanguage", row.targetLanguage());
        putText(card, "level", row.level());
        ArrayNode tags = card.putArray("tags");
        row.tags().forEach(tags::add);
        card.put("itemCount", row.itemCount()).put("exerciseCount", row.exerciseCount());
        ArrayNode media = card.putArray("media");
        row.media().forEach(item -> media.add(item.wire()));
        ArrayNode mechanics = card.putArray("mechanics");
        row.mechanics().forEach(item -> mechanics.add(item.wire()));
        putCount(card, "addedCount", known.added());
        putCount(card, "learningNowCount", known.learningNow());
        card.put("updatedAt", seconds(row.contentUpdatedAt())).put("ownerId", row.ownerId().toString());
        return card;
    }

    ObjectNode granted(AuthorQueries.Granted deck) {
        ObjectNode card = JsonNodeFactory.instance.objectNode();
        card.put("code", deck.code()).putNull("slug").put("title", deck.title()).put("description", describe(deck.description())).putNull("topic");
        putText(card, "contentLanguage", deck.contentLanguage());
        card.putNull("targetLanguage").putNull("level");
        card.putArray("tags");
        card.put("itemCount", deck.itemCount()).put("exerciseCount", deck.exerciseCount());
        card.putArray("media");
        card.putArray("mechanics");
        card.putNull("addedCount").putNull("learningNowCount");
        card.put("updatedAt", seconds(deck.publishedAt())).put("ownerId", deck.ownerId().toString());
        return card;
    }

    /** {@code {id, name, parentId}} of a topic, or the bare id when the directory does not know it (a data edit that removed a topic a row still names). */
    ObjectNode topic(String topicId) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        var entry = topics.find(topicId);
        node.put("id", topicId).put("name", entry.map(TopicDirectory.Entry::nameRu).orElse(topicId));
        putText(node, "parentId", entry.map(TopicDirectory.Entry::parentId).orElse(null));
        return node;
    }

    static String describe(String description) {
        if (description.codePointCount(0, description.length()) <= DESCRIPTION_MAX) return description;
        int end = description.offsetByCodePoints(0, DESCRIPTION_MAX - 1);
        return description.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    static String seconds(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static void putText(ObjectNode node, String name, String value) {
        if (value == null) node.putNull(name);
        else node.put(name, value);
    }

    private static void putCount(ObjectNode node, String name, int raw) {
        Integer shown = AudienceCounts.shown(raw);
        if (shown == null) node.putNull(name);
        else node.put(name, shown.intValue());
    }
}
