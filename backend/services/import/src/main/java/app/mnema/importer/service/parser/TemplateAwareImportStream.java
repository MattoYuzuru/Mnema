package app.mnema.importer.service.parser;

import app.mnema.importer.client.core.CoreFieldTemplate;

import java.util.List;

public interface TemplateAwareImportStream extends ImportStream {

    List<CoreFieldTemplate> templateFields();
}
