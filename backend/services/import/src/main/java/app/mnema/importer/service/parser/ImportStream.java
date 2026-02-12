package app.mnema.importer.service.parser;

import java.io.Closeable;
import java.util.List;

public interface ImportStream extends Closeable {
    List<String> fields();

    boolean hasNext();

    ImportRecord next();

    default Integer totalItems() {
        return null;
    }

    default ImportLayout layout() {
        return null;
    }

    default boolean isAnki() {
        return false;
    }

    default ImportAnkiTemplate ankiTemplate() {
        return null;
    }
}
