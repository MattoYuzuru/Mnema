package app.mnema.media.storage;

import java.time.Duration;
import java.util.List;

public interface ObjectStorage {
    PresignedUrl presignPut(String key, String contentType, Duration ttl);

    MultipartInit initiateMultipart(String key, String contentType);

    PresignedPart presignUploadPart(String key, String uploadId, int partNumber, Duration ttl);

    void completeMultipart(String key, String uploadId, List<CompletedUploadPart> parts);

    void abortMultipart(String key, String uploadId);

    void putObject(String key, String contentType, long contentLength, java.io.InputStream inputStream);

    ObjectInfo headObject(String key);

    PresignedUrl presignGet(String key, Duration ttl, String fileName);

    void deleteObject(String key);
}
