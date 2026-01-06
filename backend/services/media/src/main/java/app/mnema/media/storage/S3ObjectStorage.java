package app.mnema.media.storage;

import app.mnema.media.config.S3Props;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class S3ObjectStorage implements ObjectStorage {
    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStorage(S3Client s3Client, S3Presigner presigner, S3Props props) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucket = props.bucket();
    }

    @Override
    public PresignedUrl presignPut(String key, String contentType, Duration ttl) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .cacheControl(CACHE_CONTROL)
                .build();

        PutObjectPresignRequest presign = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(request)
                .build();

        var presigned = presigner.presignPutObject(presign);
        return new PresignedUrl(presigned.url().toString(), flattenHeaders(presigned));
    }

    @Override
    public MultipartInit initiateMultipart(String key, String contentType) {
        CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .cacheControl(CACHE_CONTROL)
                .build();

        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(request);
        return new MultipartInit(response.uploadId());
    }

    @Override
    public PresignedPart presignUploadPart(String key, String uploadId, int partNumber, Duration ttl) {
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .build();

        UploadPartPresignRequest presign = UploadPartPresignRequest.builder()
                .signatureDuration(ttl)
                .uploadPartRequest(request)
                .build();

        PresignedUploadPartRequest presigned = presigner.presignUploadPart(presign);
        return new PresignedPart(partNumber, presigned.url().toString(), flattenHeaders(presigned));
    }

    @Override
    public void completeMultipart(String key, String uploadId, List<CompletedUploadPart> parts) {
        List<CompletedPart> completedParts = parts.stream()
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.eTag())
                        .build())
                .toList();

        CompletedMultipartUpload upload = CompletedMultipartUpload.builder()
                .parts(completedParts)
                .build();

        s3Client.completeMultipartUpload(b -> b
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(upload));
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        AbortMultipartUploadRequest request = AbortMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .build();
        s3Client.abortMultipartUpload(request);
    }

    @Override
    public ObjectInfo headObject(String key) {
        HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
        return new ObjectInfo(response.contentLength(), response.contentType());
    }

    @Override
    public PresignedUrl presignGet(String key, Duration ttl) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseCacheControl(CACHE_CONTROL)
                .build();

        GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(request)
                .build();

        var presigned = presigner.presignGetObject(presign);
        return new PresignedUrl(presigned.url().toString(), flattenHeaders(presigned));
    }

    @Override
    public void deleteObject(String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        s3Client.deleteObject(request);
    }

    private Map<String, String> flattenHeaders(PresignedRequest presigned) {
        Map<String, List<String>> signedHeaders = presigned.signedHeaders();
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : signedHeaders.entrySet()) {
            headers.put(entry.getKey(), String.join(",", entry.getValue()));
        }
        return headers;
    }
}
