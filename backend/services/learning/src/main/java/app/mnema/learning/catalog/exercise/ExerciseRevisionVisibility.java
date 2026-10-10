package app.mnema.learning.catalog.exercise;

import java.util.regex.Pattern;

/**
 * The single rule for reading an exercise revision by id (Share/5, #427), the exercise twin of
 * {@code ItemRevisionVisibility}.
 *
 * <p>Exercise revisions belong to the storage lineage ({@code reuse_scope_id}); a deck reaching one by id must not read every
 * revision of the lineage, only its own. Revision {@code R} of exercise {@code E} is visible to deck {@code D} iff {@code R} is
 * {@code D}'s current head for {@code E} ({@code deck_head_exercise}) or {@code D}'s change journal has a row that published
 * {@code R} ({@code deck_exercise_change.revision_id}) or replaced it ({@code previous_revision_id}, which also covers the
 * revision a removal took off the roster): a revision that a change of {@code D} replaced was {@code D}'s own head until then.
 * So a copy sees the revision it inherited (also after it edited it) and everything it writes itself, never the source's later
 * revisions or the source's history before the copy, and the source never sees a copy's revision. Ownership of {@code D} by the
 * actor is a separate condition every caller keeps.
 *
 * <p>One SQL fragment, not a database function, so each caller's plan stays three correlated index probes
 * ({@code deck_head_exercise} primary key, {@code deck_exercise_change_revision},
 * {@code deck_exercise_change_previous_revision}) joined by {@code UNION ALL} inside one {@code EXISTS}, which stops at the
 * first row (the reason ORed {@code EXISTS} are avoided is the same as for items).
 */
public final class ExerciseRevisionVisibility {
    private static final Pattern PARAMETER = Pattern.compile(":[a-zA-Z][a-zA-Z0-9]*");
    private static final Pattern ALIAS = Pattern.compile("[a-z][a-z0-9_]*");

    private ExerciseRevisionVisibility() { }

    /**
     * A boolean SQL expression, true when the {@code exercise_revision} row {@code revisionAlias} is visible to the deck
     * bound to the named parameter.
     *
     * @param deckParameter named parameter (with the colon) bound to the reading deck id, for example {@code ":deck"}
     * @param revisionAlias alias of the {@code exercise_revision} row in the enclosing statement
     */
    public static String visibleTo(String deckParameter, String revisionAlias) {
        if (!PARAMETER.matcher(deckParameter).matches() || !ALIAS.matcher(revisionAlias).matches()) {
            throw new IllegalArgumentException("Invalid visibility fragment arguments");
        }
        return """
                EXISTS (SELECT 1 FROM app_learning.deck_head_exercise visible_head
                         WHERE visible_head.deck_id=%1$s AND visible_head.exercise_id=%2$s.exercise_id
                           AND visible_head.revision_id=%2$s.revision_id AND visible_head.reuse_scope_id=%2$s.reuse_scope_id
                        UNION ALL
                        SELECT 1 FROM app_learning.deck_exercise_change visible_change
                         WHERE visible_change.deck_id=%1$s AND visible_change.exercise_id=%2$s.exercise_id
                           AND visible_change.revision_id=%2$s.revision_id AND visible_change.reuse_scope_id=%2$s.reuse_scope_id
                        UNION ALL
                        SELECT 1 FROM app_learning.deck_exercise_change replaced_change
                         WHERE replaced_change.deck_id=%1$s AND replaced_change.exercise_id=%2$s.exercise_id
                           AND replaced_change.previous_revision_id=%2$s.revision_id
                           AND replaced_change.reuse_scope_id=%2$s.reuse_scope_id)"""
                .formatted(deckParameter, revisionAlias);
    }
}
