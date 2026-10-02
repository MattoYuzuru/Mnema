package app.mnema.learning.usage;

/** Spec fragments shared by the usage tests. */
final class UsageSpecs {
    private UsageSpecs() { }

    static String noteSource(int n) {
        return "{\"role\":\"SOURCE\",\"type\":\"NOTE\",\"noteId\":\"20700000-0000-4000-8000-0000000000%02d\",\"noteRowVersion\":\"3\"}"
                .formatted(n);
    }
}
