package app.mnema.learning.catalog.content;

import com.fasterxml.jackson.databind.JsonNode;

/** A validated semantic snapshot, not an authorization or publication receipt. */
public final class NativeDocument {

    private final JsonNode snapshot;
    private final int nodeCount;
    private final boolean unsupportedContent;

    NativeDocument(JsonNode snapshot, int nodeCount, boolean unsupportedContent) {
        this.snapshot = snapshot.deepCopy();
        this.nodeCount = nodeCount;
        this.unsupportedContent = unsupportedContent;
    }

    /** Callers cannot mutate the validated snapshot through the returned tree. */
    public JsonNode toJson() {
        return snapshot.deepCopy();
    }

    public int nodeCount() {
        return nodeCount;
    }

    /** Unsupported payload remains data; this flag never grants rendering capabilities. */
    public boolean hasUnsupportedContent() {
        return unsupportedContent;
    }
}
