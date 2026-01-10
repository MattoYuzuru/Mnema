package app.mnema.importer.service.parser;

public record ImportRecordProgress(
        double stabilityDays,
        double difficulty01,
        int reviewCount,
        boolean suspended
) {
}
