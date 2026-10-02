package app.mnema.learning.generation.mbm;

import java.util.List;

/** Inline tree of one block, before text nodes are merged and identifiers are allocated. */
sealed interface Inline {

    /** Text with the unique marks of its enclosing spans, outermost first. */
    record Text(String text, List<String> marks) implements Inline {
    }

    record Ruby(String base, String reading) implements Inline {
    }

    record Link(String href, List<Inline> children) implements Inline {
    }
}
