package app.mnema.learning.generation.mbm;

import java.util.List;
import java.util.UUID;

/** Parsed top-level block. {@code keptId} is the node ID a valid handle preserved, or {@code null}. */
sealed interface Block {

    int line();

    UUID keptId();

    record Heading(int line, UUID keptId, int level, List<Inline> content) implements Block {
    }

    record Paragraph(int line, UUID keptId, List<Inline> content) implements Block {
    }

    record ListBlock(int line, UUID keptId, boolean ordered, int order, List<List<Inline>> items) implements Block {
    }

    record Quote(int line, UUID keptId, List<List<Inline>> paragraphs) implements Block {
    }

    record Divider(int line, UUID keptId) implements Block {
    }

    record Table(int line, UUID keptId, String caption, List<String> columns, List<List<String>> rows)
            implements Block {
    }

    record Mermaid(int line, UUID keptId, String source, String title, String description) implements Block {
    }

    /** A fenced code block; {@code lang} is empty or a validated language identifier. */
    record Code(int line, UUID keptId, String lang, String source) implements Block {
    }

    /** An audio, image or video directive; {@code label} is the title (audio, video) or the alt text (image). */
    record Media(int line, UUID keptId, MbmSlot.Kind kind, String slotKey, String label, String lang, String voice,
                 String mode, String text) implements Block {
    }

    /** {@code ::sources}: validated lines, already matched with the research results. */
    record Sources(int line, UUID keptId, List<Entry> entries) implements Block {

        record Entry(int n, String url, String title) {
        }
    }
}
