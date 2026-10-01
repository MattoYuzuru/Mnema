package app.mnema.learning.media;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Instant;

/** Signs exact immutable blobs after the catalog has authorized the logical asset. */
@Component
final class MediaPlaybackStore implements AutoCloseable {
    private final MediaUploadSettings upload;
    private final MediaPlaybackSettings playback;
    private final S3Presigner signer;

    MediaPlaybackStore(MediaUploadSettings upload, MediaPlaybackSettings playback) {
        this.upload = upload;
        this.playback = playback;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                upload.configured() ? upload.accessKey : "unconfigured",
                upload.configured() ? upload.secretKey : "unconfigured"));
        signer = S3Presigner.builder().endpointOverride(upload.endpoint).region(Region.of(upload.region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(credentials).build();
    }

    SignedRead read(String key, boolean download) {
        if (!upload.configured()) throw new MediaStorageUnavailableException();
        var request = GetObjectRequest.builder().bucket(upload.bucket).key(key);
        if (download) request.responseContentDisposition("attachment");
        try {
            var signed = signer.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(playback.urlTtl()).getObjectRequest(request.build()).build());
            return new SignedRead(signed.url().toExternalForm(), signed.expiration());
        } catch (software.amazon.awssdk.core.exception.SdkException failure) {
            throw new MediaStorageUnavailableException();
        }
    }

    @Override public void close() { signer.close(); }

    record SignedRead(String url, Instant expiresAt) { }
}
