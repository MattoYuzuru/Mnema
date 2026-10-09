package app.mnema.learning.media;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Only the backend can create, complete and abort multipart transfers or write frozen keys. */
@Component
final class MediaObjectStore implements AutoCloseable {
    private final MediaUploadSettings settings;
    private final S3Client s3;
    private final S3Presigner presigner;

    MediaObjectStore(MediaUploadSettings settings) {
        this.settings = settings;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                settings.configured() ? settings.accessKey : "unconfigured",
                settings.configured() ? settings.secretKey : "unconfigured"));
        s3 = S3Client.builder().endpointOverride(settings.endpoint).region(Region.of(settings.region))
                .forcePathStyle(true).credentialsProvider(credentials)
                // Yandex Object Storage does not document the default CRC32 trailer of AWS SDK v2.30+.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(c -> c.apiCallTimeout(settings.storageTimeout)
                        .apiCallAttemptTimeout(settings.storageTimeout)).build();
        presigner = S3Presigner.builder().endpointOverride(settings.endpoint).region(Region.of(settings.region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(credentials).build();
    }

    SignedUrl singleUrl(String key, long length) {
        requireConfigured();
        try {
            var request = PutObjectRequest.builder().bucket(settings.bucket).key(key).contentLength(length).build();
            var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(settings.urlTtl).putObjectRequest(request).build());
            return new SignedUrl(signed.url().toExternalForm(), headers(signed.signedHeaders()), signed.expiration());
        } catch (SdkException failure) { throw new MediaStorageUnavailableException(); }
    }

    SignedUrl partUrl(String key, String storageUploadId, int number, long length) {
        requireConfigured();
        try {
            var request = UploadPartRequest.builder().bucket(settings.bucket).key(key)
                    .uploadId(storageUploadId).partNumber(number).contentLength(length).build();
            var signed = presigner.presignUploadPart(UploadPartPresignRequest.builder()
                    .signatureDuration(settings.urlTtl).uploadPartRequest(request).build());
            return new SignedUrl(signed.url().toExternalForm(), headers(signed.signedHeaders()), signed.expiration());
        } catch (SdkException failure) { throw new MediaStorageUnavailableException(); }
    }

    String createMultipart(String key) {
        requireConfigured();
        try {
            return s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(settings.bucket).key(key).build()).uploadId();
        } catch (SdkException failure) { throw new MediaStorageUnavailableException(); }
    }

    List<StoredPart> parts(String key, String storageUploadId) {
        requireConfigured();
        try {
            var result = new ArrayList<StoredPart>();
            Integer marker = null;
            do {
                var request = ListPartsRequest.builder().bucket(settings.bucket).key(key)
                        .uploadId(storageUploadId).partNumberMarker(marker).build();
                var page = s3.listParts(request);
                page.parts().forEach(part -> result.add(new StoredPart(part.partNumber(), part.size(), part.eTag())));
                marker = Boolean.TRUE.equals(page.isTruncated()) ? page.nextPartNumberMarker() : null;
                if (result.size() > 10_000) throw new MediaUploadConflictException();
            } while (marker != null);
            return result;
        } catch (SdkException failure) { throw new MediaStorageUnavailableException(); }
    }

    void complete(String key, String storageUploadId, List<StoredPart> parts) {
        requireConfigured();
        try {
            var completed = parts.stream().map(part -> CompletedPart.builder()
                    .partNumber(part.number()).eTag(part.eTag()).build()).toList();
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(settings.bucket).key(key)
                    .uploadId(storageUploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
        } catch (S3Exception failure) {
            String code = failure.awsErrorDetails() == null ? "" : failure.awsErrorDetails().errorCode();
            if ("InvalidPart".equals(code) || "InvalidPartOrder".equals(code)) {
                throw new MediaUploadConflictException();
            }
            throw new MediaStorageUnavailableException();
        } catch (SdkException failure) { throw new MediaStorageUnavailableException(); }
    }

    StoredObject head(String key) {
        requireConfigured();
        try {
            var response = s3.headObject(HeadObjectRequest.builder().bucket(settings.bucket).key(key).build());
            return new StoredObject(response.contentLength(), response.eTag());
        } catch (S3Exception failure) {
            String code = failure.awsErrorDetails() == null ? "" : failure.awsErrorDetails().errorCode();
            if (failure.statusCode() == 404 && !"NoSuchBucket".equals(code)) return null;
            throw new MediaStorageUnavailableException();
        } catch (SdkException failure) { throw new MediaStorageUnavailableException(); }
    }

    void freeze(String source, String target, String sourceEtag) {
        requireConfigured();
        try {
            s3.copyObject(CopyObjectRequest.builder().sourceBucket(settings.bucket).sourceKey(source)
                    .destinationBucket(settings.bucket).destinationKey(target)
                    .copySourceIfMatch(sourceEtag).build());
        } catch (S3Exception failure) {
            if (failure.statusCode() == 412) throw new MediaUploadConflictException();
            throw new MediaStorageUnavailableException();
        } catch (SdkException failure) { throw new MediaStorageUnavailableException(); }
    }

    void abort(String key, String storageUploadId) {
        requireConfigured();
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(settings.bucket)
                    .key(key).uploadId(storageUploadId).build());
        } catch (S3Exception failure) {
            if (failure.statusCode() != 404) throw new MediaStorageUnavailableException();
        } catch (SdkException failure) { throw new MediaStorageUnavailableException(); }
    }

    void delete(String key) {
        requireConfigured();
        try { s3.deleteObject(DeleteObjectRequest.builder().bucket(settings.bucket).key(key).build()); }
        catch (SdkException failure) { throw new MediaStorageUnavailableException(); }
    }

    /** Private backend read of frozen bytes, with an independent full-byte digest. */
    String downloadVerified(String key, Path target, long expectedLength) throws IOException {
        requireConfigured();
        try (var input = s3.getObject(GetObjectRequest.builder().bucket(settings.bucket).key(key).build());
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE)) {
            if (input.response().contentLength() != expectedLength) throw new MediaProcessingRejectedException("source_size_mismatch");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > expectedLength) throw new MediaProcessingRejectedException("source_size_mismatch");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            if (total != expectedLength) throw new MediaProcessingRejectedException("source_size_mismatch");
            return HexFormat.of().formatHex(digest.digest());
        } catch (SdkException failure) {
            throw new MediaStorageUnavailableException();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The server's own single PUT of small bytes (a staged stock image); an existing object at the key is replaced by the same bytes on a retry. */
    void put(String key, byte[] bytes, String mimeType) {
        requireConfigured();
        try {
            s3.putObject(PutObjectRequest.builder().bucket(settings.bucket).key(key).contentLength((long) bytes.length)
                    .contentType(mimeType).build(), RequestBody.fromBytes(bytes));
        } catch (SdkException failure) { throw new MediaStorageUnavailableException(); }
    }

    /**
     * Derived keys are content-addressed; a timed-out PUT is reconciled by HEAD. {@code source} must be a
     * Learning-private file (the verified copy made by {@link MediaWorkerResult}), never a path the media worker
     * can write: {@code RequestBody.fromFile} follows links. The SHA-256 travels as object metadata; a request
     * checksum header is deliberately not sent because Yandex Object Storage does not document it.
     */
    void putVerified(String key, Path source, long length, String sha256, String mimeType) {
        requireConfigured();
        try {
            s3.putObject(PutObjectRequest.builder().bucket(settings.bucket).key(key)
                            .contentLength(length).contentType(mimeType)
                            .metadata(Map.of("sha256", sha256)).ifNoneMatch("*").build(),
                    RequestBody.fromFile(source));
        } catch (SdkException failure) {
            try {
                var existing = s3.headObject(HeadObjectRequest.builder().bucket(settings.bucket).key(key).build());
                if (existing.contentLength() == length && mimeType.equals(existing.contentType())
                        && sha256.equals(userMetadata(existing.metadata()).get("sha256"))) return;
            } catch (SdkException ignored) { /* An absent or unavailable object is retryable. */ }
            throw new MediaStorageUnavailableException();
        }
    }

    private void requireConfigured() {
        if (!settings.configured()) throw new MediaStorageUnavailableException();
    }

    /** S3 providers differ in user-metadata key case (Yandex returns {@code Sha256}); compare case-insensitively. */
    static Map<String, String> userMetadata(Map<String, String> raw) {
        var result = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        result.putAll(raw);
        return result;
    }

    private static Map<String, String> headers(Map<String, List<String>> headers) {
        var result = new java.util.HashMap<String, String>();
        headers.forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host") && !values.isEmpty()) result.put(name, values.getFirst());
        });
        return Map.copyOf(result);
    }

    @Override public void close() { presigner.close(); s3.close(); }

    record SignedUrl(String url, Map<String, String> headers, Instant expiresAt) { }
    record StoredPart(int number, long size, String eTag) { }
    record StoredObject(long size, String eTag) { }
}
