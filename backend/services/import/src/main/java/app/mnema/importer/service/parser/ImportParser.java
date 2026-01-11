package app.mnema.importer.service.parser;

import java.io.IOException;
import java.io.InputStream;

public interface ImportParser {
    ImportPreview preview(InputStream inputStream, int sampleSize) throws IOException;

    ImportStream openStream(InputStream inputStream) throws IOException;
}
