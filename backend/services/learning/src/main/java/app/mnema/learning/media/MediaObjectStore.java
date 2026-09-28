package app.mnema.learning.media;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
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
import java.util.ArrayList;
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

    private void requireConfigured() {
        if (!settings.configured()) throw new MediaStorageUnavailableException();
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
