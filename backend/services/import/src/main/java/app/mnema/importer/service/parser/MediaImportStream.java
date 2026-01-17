package app.mnema.importer.service.parser;

import java.io.IOException;

public interface MediaImportStream extends ImportStream {

    ImportMedia openMedia(String mediaName) throws IOException;
}
