package app.mnema.media.service;

import app.mnema.media.controller.dto.CompleteUploadRequest;
import app.mnema.media.controller.dto.CompletedPartRequest;
import app.mnema.media.controller.dto.CreateUploadRequest;
import app.mnema.media.controller.dto.CreateUploadResponse;
import app.mnema.media.controller.dto.DirectUploadRequest;
import app.mnema.media.controller.dto.ResolveUrlTarget;
import app.mnema.media.controller.dto.ResolvedMedia;
import app.mnema.media.domain.entity.MediaAssetEntity;
import app.mnema.media.domain.entity.MediaUploadEntity;
import app.mnema.media.domain.type.MediaKind;
import app.mnema.media.domain.type.MediaStatus;
import app.mnema.media.domain.type.UploadStatus;
import app.mnema.media.repository.MediaAssetRepository;
import app.mnema.media.repository.MediaUploadRepository;
import app.mnema.media.security.CurrentUserProvider;
import app.mnema.media.security.JwtScopeHelper;
import app.mnema.media.service.policy.MediaPolicy;
import app.mnema.media.storage.CompletedUploadPart;
import app.mnema.media.storage.MultipartInit;
import app.mnema.media.storage.ObjectInfo;
import app.mnema.media.storage.ObjectStorage;
import app.mnema.media.storage.PresignedPart;
import app.mnema.media.storage.PresignedUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    MediaAssetRepository assetRepository;

    @Mock
    MediaUploadRepository uploadRepository;

    @Mock
    ObjectStorage storage;

    MediaPolicy policy = new MediaPolicy();

    @Mock
    CurrentUserProvider currentUserProvider;

    @Mock
    JwtScopeHelper scopeHelper;

    @Mock
    MediaResolveCache resolveCache;

    MediaService service;

    @BeforeEach
    void setUp() {
        service = new MediaService(
                assetRepository,
                uploadRepository,
                storage,
                policy,
                currentUserProvider,
                scopeHelper,
                resolveCache
        );
    }

    @Test
    void createUpload_returnsSinglePartPresignedUrlForSmallFiles() {
        UUID userId = UUID.randomUUID();
        Jwt jwt = jwt();
        when(currentUserProvider.requireUserId(jwt)).thenReturn(userId);
        when(assetRepository.save(any(MediaAssetEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(uploadRepository.save(any(MediaUploadEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.presignPut(anyString(), eq("image/png"), any()))
                .thenReturn(new PresignedUrl("https://upload", Map.of("x-test", "1")));

        CreateUploadResponse response = service.createUpload(jwt, new CreateUploadRequest(
                MediaKind.card_image,
                "image/png; charset=utf-8",
                1024,
                "front.png"
        ));

        assertThat(response.multipart()).isFalse();
        assertThat(response.url()).isEqualTo("https://upload");
        assertThat(response.headers()).containsEntry("x-test", "1");
        assertThat(response.parts()).isEmpty();

        ArgumentCaptor<MediaAssetEntity> assetCaptor = ArgumentCaptor.forClass(MediaAssetEntity.class);
        verify(assetRepository).save(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getOwnerUserId()).isEqualTo(userId);
        assertThat(assetCaptor.getValue().getKind()).isEqualTo(MediaKind.card_image);
        assertThat(assetCaptor.getValue().getMimeType()).isEqualTo("image/png");
        assertThat(assetCaptor.getValue().getStorageKey()).startsWith("media/card_image/");

        ArgumentCaptor<MediaUploadEntity> uploadCaptor = ArgumentCaptor.forClass(MediaUploadEntity.class);
        verify(uploadRepository).save(uploadCaptor.capture());
        assertThat(uploadCaptor.getValue().isMultipart()).isFalse();
        assertThat(uploadCaptor.getValue().getExpectedMimeType()).isEqualTo("image/png");
        assertThat(uploadCaptor.getValue().getExpectedSizeBytes()).isEqualTo(1024L);
    }

    @Test
    void createUpload_returnsMultipartPartsForLargeFiles() {
        UUID userId = UUID.randomUUID();
        Jwt jwt = jwt();
        long sizeBytes = policy.multipartThresholdBytes() + 1;

        when(currentUserProvider.requireUserId(jwt)).thenReturn(userId);
        when(assetRepository.save(any(MediaAssetEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(uploadRepository.save(any(MediaUploadEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.initiateMultipart(anyString(), eq("application/pdf"))).thenReturn(new MultipartInit("s3-upload"));
        when(storage.presignUploadPart(anyString(), eq("s3-upload"), any(Integer.class), any()))
                .thenAnswer(invocation -> new PresignedPart(invocation.getArgument(2), "https://part/" + invocation.getArgument(2), Map.of()));

        CreateUploadResponse response = service.createUpload(jwt, new CreateUploadRequest(
                MediaKind.ai_import,
                "application/pdf",
                sizeBytes,
                "doc.pdf"
        ));

        assertThat(response.multipart()).isTrue();
        assertThat(response.url()).isNull();
        assertThat(response.partsCount()).isEqualTo(3);
        assertThat(response.partSizeBytes()).isEqualTo(policy.multipartPartSizeBytes());
        assertThat(response.parts()).hasSize(3);
        assertThat(response.parts().get(0).partNumber()).isEqualTo(1);
        assertThat(response.parts().get(2).url()).isEqualTo("https://part/3");

        ArgumentCaptor<MediaUploadEntity> uploadCaptor = ArgumentCaptor.forClass(MediaUploadEntity.class);
        verify(uploadRepository).save(uploadCaptor.capture());
        assertThat(uploadCaptor.getValue().isMultipart()).isTrue();
        assertThat(uploadCaptor.getValue().getPartsCount()).isEqualTo(3);
        assertThat(uploadCaptor.getValue().getS3UploadId()).isEqualTo("s3-upload");
    }

    @Test
    void createUpload_rejectsInvalidUploadPolicy() {
        Jwt jwt = jwt();
        when(currentUserProvider.requireUserId(jwt)).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.createUpload(jwt, new CreateUploadRequest(
                MediaKind.card_image,
                "application/pdf",
                5,
                "x.pdf"
        ))).isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(assetRepository, never()).save(any());
    }

    @Test
    void completeUpload_completesMultipartAndMarksMediaReady() {
        UUID ownerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        Jwt jwt = jwt();
        MediaUploadEntity upload = upload(uploadId, mediaId, true, 2, 8L, "s3-upload");
        MediaAssetEntity asset = asset(mediaId, ownerId, MediaKind.ai_import, MediaStatus.pending, "media/ai_import/" + mediaId, "application/pdf");

        when(uploadRepository.findByUploadId(uploadId)).thenReturn(Optional.of(upload));
        when(assetRepository.findById(mediaId)).thenReturn(Optional.of(asset));
        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(false);
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));
        when(storage.headObject(asset.getStorageKey())).thenReturn(new ObjectInfo(17L, "application/pdf"));
        when(assetRepository.save(any(MediaAssetEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(uploadRepository.save(any(MediaUploadEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.completeUpload(jwt, uploadId, new CompleteUploadRequest(List.of(
                new CompletedPartRequest(1, "\"etag-1\""),
                new CompletedPartRequest(2, "\"etag-2\"")
        )));

        assertThat(response.mediaId()).isEqualTo(mediaId);
        assertThat(response.status()).isEqualTo(MediaStatus.ready);
        assertThat(asset.getStatus()).isEqualTo(MediaStatus.ready);
        assertThat(asset.getMimeType()).isEqualTo("application/pdf");
        assertThat(asset.getSizeBytes()).isEqualTo(17L);
        assertThat(upload.getStatus()).isEqualTo(UploadStatus.completed);
        assertThat(upload.getCompletedAt()).isNotNull();

        ArgumentCaptor<List<CompletedUploadPart>> partsCaptor = ArgumentCaptor.forClass(List.class);
        verify(storage).completeMultipart(eq(asset.getStorageKey()), eq("s3-upload"), partsCaptor.capture());
        assertThat(partsCaptor.getValue()).extracting(CompletedUploadPart::eTag).containsExactly("etag-1", "etag-2");
    }

    @Test
    void completeUpload_rejectsUnexpectedContentTypeAndMarksUploadFailed() {
        UUID ownerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        Jwt jwt = jwt();
        MediaUploadEntity upload = upload(uploadId, mediaId, false, 1, null, null);
        MediaAssetEntity asset = asset(mediaId, ownerId, MediaKind.card_image, MediaStatus.pending, "media/card_image/" + mediaId, "image/png");

        when(uploadRepository.findByUploadId(uploadId)).thenReturn(Optional.of(upload));
        when(assetRepository.findById(mediaId)).thenReturn(Optional.of(asset));
        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(false);
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));
        when(storage.headObject(asset.getStorageKey())).thenReturn(new ObjectInfo(2048L, "image/jpeg"));
        when(assetRepository.save(any(MediaAssetEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(uploadRepository.save(any(MediaUploadEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.completeUpload(jwt, uploadId, new CompleteUploadRequest(List.of())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).isEqualTo("Unexpected content type");
                });

        assertThat(upload.getStatus()).isEqualTo(UploadStatus.failed);
        assertThat(upload.getErrorMessage()).isEqualTo("Unexpected content type");
        assertThat(asset.getStatus()).isEqualTo(MediaStatus.rejected);
        verify(storage).deleteObject(asset.getStorageKey());
    }

    @Test
    void completeUpload_rejectsDuplicateMultipartParts() {
        UUID ownerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        Jwt jwt = jwt();
        MediaUploadEntity upload = upload(uploadId, mediaId, true, 2, 8L, "s3-upload");
        MediaAssetEntity asset = asset(mediaId, ownerId, MediaKind.ai_import, MediaStatus.pending, "media/ai_import/" + mediaId, "application/pdf");

        when(uploadRepository.findByUploadId(uploadId)).thenReturn(Optional.of(upload));
        when(assetRepository.findById(mediaId)).thenReturn(Optional.of(asset));
        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(false);
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));

        assertThatThrownBy(() -> service.completeUpload(jwt, uploadId, new CompleteUploadRequest(List.of(
                new CompletedPartRequest(1, "etag-1"),
                new CompletedPartRequest(1, "etag-2")
        )))).isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("Duplicate part number: 1"));

        verify(storage, never()).completeMultipart(anyString(), anyString(), anyList());
    }

    @Test
    void directUpload_storesObjectAndMarksMediaReady() {
        UUID ownerId = UUID.randomUUID();
        Jwt jwt = jwt();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3});

        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(true);
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));
        when(assetRepository.save(any(MediaAssetEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.directUpload(jwt, new DirectUploadRequest(MediaKind.avatar, null, null, null), file);

        assertThat(response.status()).isEqualTo(MediaStatus.ready);
        ArgumentCaptor<MediaAssetEntity> assetCaptor = ArgumentCaptor.forClass(MediaAssetEntity.class);
        verify(assetRepository, atLeastOnce()).save(assetCaptor.capture());
        MediaAssetEntity saved = assetCaptor.getValue();
        assertThat(saved.getOwnerUserId()).isEqualTo(ownerId);
        assertThat(saved.getOriginalFileName()).isEqualTo("avatar.png");
        assertThat(saved.getMimeType()).isEqualTo("image/png");
        assertThat(saved.getSizeBytes()).isEqualTo(3L);
        verify(storage).putObject(eq(saved.getStorageKey()), eq("image/png"), eq(3L), any());
    }

    @Test
    void directUpload_marksAssetRejectedWhenStorageFails() {
        UUID ownerId = UUID.randomUUID();
        Jwt jwt = jwt();
        MockMultipartFile file = new MockMultipartFile("file", "audio.mp3", "audio/mpeg", new byte[]{1, 2, 3, 4});

        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(true);
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));
        when(assetRepository.save(any(MediaAssetEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("s3 down")).when(storage).putObject(anyString(), anyString(), anyLong(), any());

        assertThatThrownBy(() -> service.directUpload(jwt, new DirectUploadRequest(MediaKind.card_audio, "audio/mpeg", "clip.mp3", null), file))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("Upload failed"));

        ArgumentCaptor<MediaAssetEntity> assetCaptor = ArgumentCaptor.forClass(MediaAssetEntity.class);
        verify(assetRepository, atLeastOnce()).save(assetCaptor.capture());
        assertThat(assetCaptor.getAllValues().getLast().getStatus()).isEqualTo(MediaStatus.rejected);
        verify(storage).deleteObject(anyString());
    }

    @Test
    void abortUpload_abortsMultipartMarksEntitiesAndEvictsCache() {
        UUID ownerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        Jwt jwt = jwt();
        MediaUploadEntity upload = upload(uploadId, mediaId, true, 2, 8L, "s3-upload");
        MediaAssetEntity asset = asset(mediaId, ownerId, MediaKind.import_file, MediaStatus.pending, "media/import_file/" + mediaId, "application/zip");

        when(uploadRepository.findByUploadId(uploadId)).thenReturn(Optional.of(upload));
        when(assetRepository.findById(mediaId)).thenReturn(Optional.of(asset));
        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(false);
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));
        when(uploadRepository.save(any(MediaUploadEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(assetRepository.save(any(MediaAssetEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.abortUpload(jwt, uploadId);

        assertThat(upload.getStatus()).isEqualTo(UploadStatus.aborted);
        assertThat(asset.getStatus()).isEqualTo(MediaStatus.deleted);
        assertThat(asset.getDeletedAt()).isNotNull();
        verify(storage).abortMultipart(asset.getStorageKey(), "s3-upload");
        verify(storage).deleteObject(asset.getStorageKey());
        verify(resolveCache).evict(mediaId);
    }

    @Test
    void resolve_allowsOwnerAndPublicMediaButRejectsPrivateForeignMedia() {
        UUID ownerId = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();
        UUID publicMediaId = UUID.randomUUID();
        UUID privateMediaId = UUID.randomUUID();
        Jwt jwt = jwt();

        MediaAssetEntity publicAsset = asset(publicMediaId, foreignId, MediaKind.card_image, MediaStatus.ready, "media/card_image/" + publicMediaId, "image/png");
        MediaAssetEntity privateAsset = asset(privateMediaId, foreignId, MediaKind.avatar, MediaStatus.ready, "media/avatar/" + privateMediaId, "image/png");

        when(assetRepository.findByMediaIdIn(List.of(publicMediaId))).thenReturn(List.of(publicAsset));
        when(assetRepository.findByMediaIdIn(List.of(privateMediaId))).thenReturn(List.of(privateAsset));
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));
        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(false);
        when(resolveCache.resolvePublic(publicAsset)).thenReturn(new ResolvedMedia(publicMediaId, MediaKind.card_image, "https://cdn/public", "image/png", 10L, null, null, null, Instant.now()));

        List<ResolvedMedia> resolved = service.resolve(jwt, List.of(publicMediaId), null);

        assertThat(resolved).singleElement().extracting(ResolvedMedia::url).isEqualTo("https://cdn/public");

        assertThatThrownBy(() -> service.resolve(jwt, List.of(privateMediaId), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void resolve_rejectsMissingAndNotReadyMedia() {
        UUID ownerId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        Jwt jwt = jwt();
        MediaAssetEntity pending = asset(pendingId, ownerId, MediaKind.avatar, MediaStatus.pending, "media/avatar/" + pendingId, "image/png");

        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));
        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(false);
        when(assetRepository.findByMediaIdIn(List.of(missingId))).thenReturn(List.of());
        when(assetRepository.findByMediaIdIn(List.of(pendingId))).thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.resolve(jwt, List.of(missingId), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> service.resolve(jwt, List.of(pendingId), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void resolve_internalTargetRequiresInternalScopeAndUsesInternalCache() {
        UUID ownerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Jwt jwt = jwt();
        MediaAssetEntity asset = asset(mediaId, ownerId, MediaKind.import_file, MediaStatus.ready, "media/import_file/" + mediaId, "application/zip");
        ResolvedMedia internal = new ResolvedMedia(mediaId, MediaKind.import_file, "http://minio:9000/object", "application/zip", 10L, null, null, null, Instant.now());

        when(assetRepository.findByMediaIdIn(List.of(mediaId))).thenReturn(List.of(asset));
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));
        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(true);
        when(resolveCache.resolveInternal(asset)).thenReturn(internal);

        List<ResolvedMedia> resolved = service.resolve(jwt, List.of(mediaId), ResolveUrlTarget.INTERNAL);

        assertThat(resolved).containsExactly(internal);
        verify(resolveCache).resolveInternal(asset);
    }

    @Test
    void resolve_internalTargetRejectsExternalCaller() {
        UUID ownerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Jwt jwt = jwt();

        when(assetRepository.findByMediaIdIn(List.of(mediaId))).thenReturn(List.of());
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));
        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(false);

        assertThatThrownBy(() -> service.resolve(jwt, List.of(mediaId), ResolveUrlTarget.INTERNAL))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void deleteMedia_deletesObjectAndEvictsCache() {
        UUID ownerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Jwt jwt = jwt();
        MediaAssetEntity asset = asset(mediaId, ownerId, MediaKind.deck_icon, MediaStatus.ready, "media/deck_icon/" + mediaId, "image/png");

        when(assetRepository.findById(mediaId)).thenReturn(Optional.of(asset));
        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(false);
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));
        when(assetRepository.save(any(MediaAssetEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.deleteMedia(jwt, mediaId);

        assertThat(asset.getStatus()).isEqualTo(MediaStatus.deleted);
        assertThat(asset.getDeletedAt()).isNotNull();
        verify(storage).deleteObject(asset.getStorageKey());
        verify(resolveCache).evict(mediaId);
    }

    @Test
    void deleteMedia_rejectsForeignUserWithoutInternalScope() {
        UUID ownerId = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        Jwt jwt = jwt();
        MediaAssetEntity asset = asset(mediaId, foreignId, MediaKind.deck_icon, MediaStatus.ready, "media/deck_icon/" + mediaId, "image/png");

        when(assetRepository.findById(mediaId)).thenReturn(Optional.of(asset));
        when(scopeHelper.hasAnyScope(jwt, Set.of("media.internal", "media.read_all"))).thenReturn(false);
        when(currentUserProvider.getUserId(jwt)).thenReturn(Optional.of(ownerId));

        assertThatThrownBy(() -> service.deleteMedia(jwt, mediaId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static Jwt jwt() {
        return new Jwt(
                "token",
                Instant.parse("2026-04-07T00:00:00Z"),
                Instant.parse("2026-04-08T00:00:00Z"),
                Map.of("alg", "none"),
                Map.of("sub", "test-user")
        );
    }

    private static MediaAssetEntity asset(UUID mediaId,
                                          UUID ownerUserId,
                                          MediaKind kind,
                                          MediaStatus status,
                                          String storageKey,
                                          String mimeType) {
        return new MediaAssetEntity(
                mediaId,
                ownerUserId,
                kind,
                status,
                storageKey,
                mimeType,
                null,
                null,
                null,
                null,
                "file.bin",
                Instant.parse("2026-04-07T10:00:00Z"),
                null,
                null
        );
    }

    private static MediaUploadEntity upload(UUID uploadId,
                                            UUID mediaId,
                                            boolean multipart,
                                            Integer partsCount,
                                            Long partSizeBytes,
                                            String s3UploadId) {
        return new MediaUploadEntity(
                uploadId,
                mediaId,
                UploadStatus.initiated,
                multipart ? 17L : 2048L,
                multipart ? "application/pdf" : "image/png",
                multipart,
                partsCount,
                partSizeBytes,
                s3UploadId,
                Instant.parse("2026-04-07T10:00:00Z"),
                Instant.parse("2026-04-07T10:10:00Z"),
                null,
                null
        );
    }
}
