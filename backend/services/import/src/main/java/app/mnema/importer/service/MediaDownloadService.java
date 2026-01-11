package app.mnema.importer.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;

@Service
public class MediaDownloadService {

    public InputStream openStream(String url) throws IOException {
        return URI.create(url).toURL().openStream();
    }

    public String detectContentType(String fileName) throws IOException {
        return URLConnection.guessContentTypeFromName(fileName);
    }
}
