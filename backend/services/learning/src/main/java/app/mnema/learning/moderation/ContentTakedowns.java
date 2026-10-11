package app.mnema.learning.moderation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The ONE predicate of the legal takedown by lineage (Share/15, #437; community decks architecture section 11): "this revision of this unit of this
 * lineage is taken down". A takedown row names a revision ({@code content_takedown}: lineage scope, unit kind, revision), and every reader asks the
 * same question about the revision it is ABOUT TO SERVE: the source's published revision for a public reader, the deck's head for its owner, the
 * revision a Study candidate or an AI context would quote. Because a copy shares the lineage rows of its source, the source deck and every copy whose head
 * is still that revision are blocked together; a copy whose learner edited the unit holds a different revision and is unaffected, as is any unit the
 * decision did not name. The lookup is the unique key {@code (reuse_scope_id, unit_kind, revision_id)}: one index probe per revision, nothing at all to
 * read while no complaint has ever been decided.
 *
 * <p>Two forms of the same predicate exist so that no reader needs a second round trip: the SQL fragments ({@link #blockedItem}, {@link #blockedExercise}) are
 * appended to the statements that select revisions, and the methods answer the question for the readers that already hold a revision id. A reader that is
 * not listed in the guide does not enforce the takedown.
 */
@Component
public class ContentTakedowns {
    private static final Pattern EXPRESSION = Pattern.compile("(:[a-zA-Z][a-zA-Z0-9]*)|([a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*)");

    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    public ContentTakedowns(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    /**
     * A boolean SQL expression, true when the material revision is taken down.
     *
     * @param scope    column or named parameter holding the lineage scope, for example {@code "h.reuse_scope_id"}
     * @param member   column or named parameter holding the member key
     * @param revision column or named parameter holding the item revision id
     */
    public static String blockedItem(String scope, String member, String revision) {
        return blocked("ITEM", scope, member, revision);
    }

    /** The exercise twin of {@link #blockedItem}: {@code exercise} is the exercise id, {@code revision} the exercise revision id. */
    public static String blockedExercise(String scope, String exercise, String revision) {
        return blocked("EXERCISE", scope, exercise, revision);
    }

    /**
     * The exercise predicate for statements that hold a deck id instead of a scope (a Study presentation row carries no scope column): the deck names the
     * lineage. The same row of {@code content_takedown}, reached through the deck's scope.
     */
    public static String blockedExerciseOfDeck(String deck, String exercise, String revision) {
        for (String expression : new String[] {deck, exercise, revision}) {
            if (!EXPRESSION.matcher(expression).matches()) throw new IllegalArgumentException("Invalid takedown fragment arguments");
        }
        return ("EXISTS (SELECT 1 FROM app_learning.deck takedown_deck JOIN app_learning.content_takedown takedown "
                + "ON takedown.reuse_scope_id=takedown_deck.reuse_scope_id WHERE takedown_deck.deck_id=%s AND takedown.unit_kind='EXERCISE' "
                + "AND takedown.revision_id=%s AND takedown.unit_key=%s)").formatted(deck, revision, exercise);
    }

    private static String blocked(String kind, String scope, String key, String revision) {
        for (String expression : new String[] {scope, key, revision}) {
            if (!EXPRESSION.matcher(expression).matches()) throw new IllegalArgumentException("Invalid takedown fragment arguments");
        }
        return "EXISTS (SELECT 1 FROM app_learning.content_takedown takedown WHERE takedown.reuse_scope_id=%s AND takedown.unit_kind='%s' "
                .formatted(scope, kind) + "AND takedown.revision_id=%s AND takedown.unit_key=%s)".formatted(revision, key);
    }

    public boolean item(UUID scope, UUID member, UUID revision) {
        return exists("ITEM", scope, member, revision);
    }

    public boolean exercise(UUID scope, UUID exercise, UUID revision) {
        return exists("EXERCISE", scope, exercise, revision);
    }

    /** The revisions among {@code revisions} that are taken down, in one statement (a page of a list). */
    public Set<UUID> items(UUID scope, Collection<UUID> revisions) {
        return among("ITEM", scope, revisions);
    }

    public Set<UUID> exercises(UUID scope, Collection<UUID> revisions) {
        return among("EXERCISE", scope, revisions);
    }

    /** Counts an enforcement for the metric {@code mnema_content_unavailable_total{reader}} (fixed labels, never an id). */
    public void count(String reader) {
        meters.counter("mnema_content_unavailable_total", "reader", reader).increment();
    }

    private boolean exists(String kind, UUID scope, UUID key, UUID revision) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.content_takedown takedown WHERE takedown.reuse_scope_id=:scope "
                        + "AND takedown.unit_kind=:kind AND takedown.revision_id=:revision AND takedown.unit_key=:key)")
                .param("scope", scope).param("kind", kind).param("revision", revision).param("key", key).query(Boolean.class).single();
    }

    private Set<UUID> among(String kind, UUID scope, Collection<UUID> revisions) {
        if (revisions.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("SELECT takedown.revision_id FROM app_learning.content_takedown takedown WHERE takedown.reuse_scope_id=:scope "
                        + "AND takedown.unit_kind=:kind AND takedown.revision_id IN (:revisions)")
                .param("scope", scope).param("kind", kind).param("revisions", revisions).query(UUID.class).list());
    }
}
