package app.mnema.importer.service.parser;

import java.util.List;

public record ImportLayout(
        List<String> front,
        List<String> back
) {
}
