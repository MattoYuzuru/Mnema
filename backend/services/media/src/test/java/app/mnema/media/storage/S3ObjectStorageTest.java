package app.mnema.media.storage;

import app.mnema.media.config.S3Props;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner presigner;

    private S3ObjectStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3ObjectStorage(
                s3Client,
                presigner,
                new S3Props("mnema-bucket", "eu-central-1", "https://s3.example", "https://cdn.example", true, "key", "secret")
        );
    }

    @Test
    void presignPutBuildsRequestAndFlattensHeaders() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(java.net.URI.create("https://upload.example/object").toURL());
        when(presigned.signedHeaders()).thenReturn(Map.of("x-amz-meta-test", List.of("a", "b")));
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        PresignedUrl result = storage.presignPut("media/key", "image/png", Duration.ofMinutes(5));

        assertThat(result.url()).isEqualTo("https://upload.example/object");
        assertThat(result.headers()).containsEntry("x-amz-meta-test", "a,b");

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(captor.capture());
        PutObjectRequest request = captor.getValue().putObjectRequest();
        assertThat(request.bucket()).isEqualTo("mnema-bucket");
        assertThat(request.key()).isEqualTo("media/key");
        assertThat(request.contentType()).isEqualTo("image/png");
        assertThat(request.cacheControl()).isEqualTo("public, max-age=31536000, immutable");
        assertThat(captor.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void initiateAndCompleteMultipartMapRequests() {
        when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-123").build());

        MultipartInit init = storage.initiateMultipart("media/part", "application/pdf");

        assertThat(init.uploadId()).isEqualTo("upload-123");

        ArgumentCaptor<CreateMultipartUploadRequest> createCaptor = ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(s3Client).createMultipartUpload(createCaptor.capture());
        assertThat(createCaptor.getValue().bucket()).isEqualTo("mnema-bucket");
        assertThat(createCaptor.getValue().key()).isEqualTo("media/part");
        assertThat(createCaptor.getValue().contentType()).isEqualTo("application/pdf");

        storage.completeMultipart("media/part", "upload-123", List.of(
                new CompletedUploadPart(1, "\"etag-1\""),
                new CompletedUploadPart(2, "\"etag-2\"")
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<CompleteMultipartUploadRequest.Builder>> completeCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(s3Client).completeMultipartUpload(completeCaptor.capture());
        CompleteMultipartUploadRequest.Builder builder = CompleteMultipartUploadRequest.builder();
        completeCaptor.getValue().accept(builder);
        CompleteMultipartUploadRequest completeRequest = builder.build();
        assertThat(completeRequest.bucket()).isEqualTo("mnema-bucket");
        assertThat(completeRequest.key()).isEqualTo("media/part");
        assertThat(completeRequest.uploadId()).isEqualTo("upload-123");
        assertThat(completeRequest.multipartUpload().parts())
                .extracting(CompletedPart::partNumber, CompletedPart::eTag)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "\"etag-1\""),
                        org.assertj.core.groups.Tuple.tuple(2, "\"etag-2\"")
                );
    }

    @Test
    void presignUploadPartUsesPartSpecificRequest() throws Exception {
        PresignedUploadPartRequest presigned = mock(PresignedUploadPartRequest.class);
        when(presigned.url()).thenReturn(java.net.URI.create("https://upload.example/part").toURL());
        when(presigned.signedHeaders()).thenReturn(Map.of("x-test", List.of("value")));
        when(presigner.presignUploadPart(any(UploadPartPresignRequest.class))).thenReturn(presigned);

        PresignedPart result = storage.presignUploadPart("media/key", "upload-1", 7, Duration.ofMinutes(15));

        assertThat(result.partNumber()).isEqualTo(7);
        assertThat(result.url()).isEqualTo("https://upload.example/part");
        assertThat(result.headers()).containsEntry("x-test", "value");

        ArgumentCaptor<UploadPartPresignRequest> captor = ArgumentCaptor.forClass(UploadPartPresignRequest.class);
        verify(presigner).presignUploadPart(captor.capture());
        UploadPartRequest request = captor.getValue().uploadPartRequest();
        assertThat(request.bucket()).isEqualTo("mnema-bucket");
        assertThat(request.key()).isEqualTo("media/key");
        assertThat(request.uploadId()).isEqualTo("upload-1");
        assertThat(request.partNumber()).isEqualTo(7);
        assertThat(captor.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void putHeadAbortAndDeleteDelegateToS3() throws Exception {
        byte[] bytes = "payload".getBytes();
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(7L).contentType("audio/mpeg").build());

        storage.putObject("media/audio", "audio/mpeg", bytes.length, new ByteArrayInputStream(bytes));
        ObjectInfo info = storage.headObject("media/audio");
        storage.abortMultipart("media/audio", "upload-9");
        storage.deleteObject("media/audio");

        assertThat(info.contentLength()).isEqualTo(7L);
        assertThat(info.contentType()).isEqualTo("audio/mpeg");

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(putCaptor.capture(), bodyCaptor.capture());
        assertThat(putCaptor.getValue().bucket()).isEqualTo("mnema-bucket");
        assertThat(putCaptor.getValue().contentLength()).isEqualTo((long) bytes.length);
        assertThat(putCaptor.getValue().cacheControl()).isEqualTo("public, max-age=31536000, immutable");

        ArgumentCaptor<HeadObjectRequest> headCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(headCaptor.capture());
        assertThat(headCaptor.getValue().key()).isEqualTo("media/audio");

        ArgumentCaptor<AbortMultipartUploadRequest> abortCaptor = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(s3Client).abortMultipartUpload(abortCaptor.capture());
        assertThat(abortCaptor.getValue().uploadId()).isEqualTo("upload-9");

        ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo("mnema-bucket");
        assertThat(deleteCaptor.getValue().key()).isEqualTo("media/audio");
    }

    @Test
    void presignGetSanitizesFileNameAndOmitsDispositionWhenBlank() throws Exception {
        PresignedGetObjectRequest named = mock(PresignedGetObjectRequest.class);
        when(named.url()).thenReturn(java.net.URI.create("https://download.example/object").toURL());
        when(named.signedHeaders()).thenReturn(Map.of("host", List.of("download.example")));
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(named);

        PresignedUrl result = storage.presignGet("media/file", Duration.ofMinutes(3), " bad\\name\"\n.pdf ");
        PresignedUrl noName = storage.presignGet("media/file", Duration.ofMinutes(1), "   ");

        assertThat(result.url()).isEqualTo("https://download.example/object");
        assertThat(result.headers()).containsEntry("host", "download.example");
        assertThat(noName.url()).isEqualTo("https://download.example/object");

        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner, org.mockito.Mockito.times(2)).presignGetObject(captor.capture());
        GetObjectRequest withName = captor.getAllValues().get(0).getObjectRequest();
        GetObjectRequest withoutName = captor.getAllValues().get(1).getObjectRequest();

        assertThat(withName.bucket()).isEqualTo("mnema-bucket");
        assertThat(withName.responseCacheControl()).isEqualTo("public, max-age=31536000, immutable");
        assertThat(withName.responseContentDisposition())
                .isEqualTo("attachment; filename=\"bad_name_.pdf\"; filename*=UTF-8''bad_name_.pdf");
        assertThat(withoutName.responseContentDisposition()).isNull();
    }
}
