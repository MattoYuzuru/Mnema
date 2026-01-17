package app.mnema.importer.service.parser;

import java.io.InputStream;

public record ImportMedia(InputStream stream, long size) {
}
