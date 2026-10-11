package app.mnema.learning.catalog.item;

import java.util.regex.Pattern;

/**
 * The single rule for reading an item revision by id (Share/4, #426).
 *
 * <p>Item revisions belong to the storage lineage ({@code reuse_scope_id}); a deck reaching one by id must not be able to
 * read every revision of the lineage, only its own. Revision {@code R} of material {@code M} is visible to deck {@code D} iff
 * {@code R} is {@code D}'s current head for {@code M} or {@code D}'s change journal has a row that published {@code R}
 * ({@code deck_item_change.revision_id}) or replaced ({@code deck_item_change.previous_revision_id}): a revision that a
 * change of {@code D} replaced was {@code D}'s own head until then, so seeing it leaks nothing. So a copy sees the
 * revision it inherited (also after it edited it) and everything it writes itself, never the source's later revisions,
 * never the source's history before the copy, and the source never sees a copy's revision. Study alone ({@link #visibleToStudy}, Share/5,
 * #427) also reads a revision pinned by a binding of one of {@code D}'s head exercises: an exercise inherited from the source
 * quotes the material revision it was written against, which predates the copy's own journal, and the copy must still issue that
 * text. Ownership of {@code D} by the actor is a separate condition every caller keeps as before.
 *
 * <p>It is one SQL fragment instead of a database function so that the planner sees plain index probes
 * ({@code deck_head_item} primary key, {@code deck_item_change_revision}) inside each caller's query, and so that a
 * later rule change ships with the code that needs it in one place. Every statement that reads a revision by id appends it
 * with the revision row's alias.
 *
 * <p>The branches (head, published, replaced, and for Study pinned by a head exercise) are one {@code EXISTS} over {@code UNION ALL}, not ORed
 * {@code EXISTS}: PostgreSQL may turn the second of two ORed {@code EXISTS} into a hashed subplan that reads the deck's
 * whole journal (seen in a joined statement), while a set operation always stays a correlated probe per branch, which stops
 * at the first row. Each branch has its own index: {@code deck_head_item} primary key, {@code deck_item_change_revision},
 * {@code deck_item_change_previous_revision}; the Study branch adds {@code exercise_binding_item_revision} followed by the
 * {@code deck_head_exercise} primary key.
 */
public final class ItemRevisionVisibility {
    private static final Pattern PARAMETER = Pattern.compile(":[a-zA-Z][a-zA-Z0-9]*");
    private static final Pattern ALIAS = Pattern.compile("[a-z][a-z0-9_]*");

    private ItemRevisionVisibility() { }

    /**
     * A boolean SQL expression, true when the {@code item_revision} row {@code revisionAlias} is visible to the
     * deck bound to the named parameter: the deck's head, or published or replaced by the deck's own journal. This is the rule
     * of every by-id read of a material ({@code ItemService.read}, draft bases, generation admission): a revision that is only
     * pinned by an exercise has no journal row of the deck, hence no recorded position, and is NOT readable through it.
     *
     * @param deckParameter named parameter (with the colon) bound to the reading deck id, for example {@code ":deck"}
     * @param revisionAlias alias of the {@code item_revision} row in the enclosing statement
     */
    public static String visibleTo(String deckParameter, String revisionAlias) {
        check(deckParameter, revisionAlias);
        return """
                EXISTS (%s)""".formatted(branches(deckParameter, revisionAlias));
    }

    /**
     * {@link #visibleTo} plus the revisions pinned by a binding of one of the deck's HEAD exercises. It serves Study's reading of
     * the text an issued exercise quotes and nothing else: the pin comes from the deck's own head exercise (an exercise the
     * source authored after the fork is not in the copy's heads, so its pins stay invisible to the copy), and it grants the text
     * of the pinned revision only, never a by-id read of the material.
     */
    public static String visibleToStudy(String deckParameter, String revisionAlias) {
        check(deckParameter, revisionAlias);
        return """
                EXISTS (%s
                        UNION ALL
                        SELECT 1 FROM app_learning.exercise_content_binding pinned_binding
                          JOIN app_learning.deck_head_exercise pinned_head ON pinned_head.deck_id=%s
                           AND pinned_head.reuse_scope_id=pinned_binding.reuse_scope_id
                           AND pinned_head.exercise_id=pinned_binding.exercise_id
                           AND pinned_head.revision_id=pinned_binding.exercise_revision_id
                         WHERE pinned_binding.reuse_scope_id=%s.reuse_scope_id AND pinned_binding.member_key=%s.member_key
                           AND pinned_binding.item_revision_id=%s.revision_id)"""
                .formatted(branches(deckParameter, revisionAlias), deckParameter, revisionAlias, revisionAlias, revisionAlias);
    }

    private static void check(String deckParameter, String revisionAlias) {
        if (!PARAMETER.matcher(deckParameter).matches() || !ALIAS.matcher(revisionAlias).matches()) {
            throw new IllegalArgumentException("Invalid visibility fragment arguments");
        }
    }

    private static String branches(String deckParameter, String revisionAlias) {
        return """
                SELECT 1 FROM app_learning.deck_head_item visible_head
                         WHERE visible_head.deck_id=%1$s AND visible_head.member_key=%2$s.member_key
                           AND visible_head.revision_id=%2$s.revision_id AND visible_head.reuse_scope_id=%2$s.reuse_scope_id
                        UNION ALL
                        SELECT 1 FROM app_learning.deck_item_change visible_change
                         WHERE visible_change.deck_id=%1$s AND visible_change.member_key=%2$s.member_key
                           AND visible_change.revision_id=%2$s.revision_id AND visible_change.reuse_scope_id=%2$s.reuse_scope_id
                        UNION ALL
                        SELECT 1 FROM app_learning.deck_item_change replaced_change
                         WHERE replaced_change.deck_id=%1$s AND replaced_change.member_key=%2$s.member_key
                           AND replaced_change.previous_revision_id=%2$s.revision_id
                           AND replaced_change.reuse_scope_id=%2$s.reuse_scope_id"""
                .formatted(deckParameter, revisionAlias);
    }
}
