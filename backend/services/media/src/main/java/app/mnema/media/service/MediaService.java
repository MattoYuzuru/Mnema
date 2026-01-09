package app.mnema.media.service;

import app.mnema.media.controller.dto.CompletedPartRequest;
import app.mnema.media.controller.dto.CompleteUploadRequest;
import app.mnema.media.controller.dto.CompleteUploadResponse;
import app.mnema.media.controller.dto.CreateUploadRequest;
import app.mnema.media.controller.dto.CreateUploadResponse;
import app.mnema.media.controller.dto.DirectUploadRequest;
import app.mnema.media.controller.dto.ResolvedMedia;
import app.mnema.media.controller.dto.UploadPartResponse;
import app.mnema.media.domain.entity.MediaAssetEntity;
import app.mnema.media.domain.entity.MediaUploadEntity;
import app.mnema.media.domain.type.MediaStatus;
import app.mnema.media.domain.type.UploadStatus;
import app.mnema.media.repository.MediaAssetRepository;
import app.mnema.media.repository.MediaUploadRepository;
import app.mnema.media.security.CurrentUserProvider;
import app.mnema.media.security.JwtScopeHelper;
import app.mnema.media.service.policy.MediaPolicy;
import app.mnema.media.storage.CompletedUploadPart;
import app.mnema.media.storage.ObjectInfo;
import app.mnema.media.storage.ObjectStorage;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaService {
    private static final Set<String> INTERNAL_SCOPES = Set.of("media.internal", "media.read_all");

    private final MediaAssetRepository assetRepository;
    private final MediaUploadRepository uploadRepository;
    private final ObjectStorage storage;
    private final MediaPolicy policy;
    private final CurrentUserProvider currentUserProvider;
    private final JwtScopeHelper scopeHelper;

    public MediaService(MediaAssetRepository assetRepository,
                        MediaUploadRepository uploadRepository,
                        ObjectStorage storage,
                        MediaPolicy policy,
                        CurrentUserProvider currentUserProvider,
                        JwtScopeHelper scopeHelper) {
        this.assetRepository = assetRepository;
        this.uploadRepository = uploadRepository;
        this.storage = storage;
        this.policy = policy;
        this.currentUserProvider = currentUserProvider;
        this.scopeHelper = scopeHelper;
    }

    @Transactional
    public CreateUploadResponse createUpload(Jwt jwt, CreateUploadRequest req) {
        UUID userId = currentUserProvider.requireUserId(jwt);

        String contentType = policy.normalizeContentType(req.contentType());
        policy.validateUpload(req.kind(), contentType, req.sizeBytes());

        UUID mediaId = UUID.randomUUID();
        String storageKey = buildStorageKey(req.kind().name(), mediaId);
        Instant now = Instant.now();

        MediaAssetEntity asset = new MediaAssetEntity(
                mediaId,
                userId,
                req.kind(),
                MediaStatus.pending,
                storageKey,
                contentType,
                null,
                null,
                null,
                null,
                req.fileName(),
                now,
                null,
                null
        );

        assetRepository.save(asset);

        boolean multipart = req.sizeBytes() >= policy.multipartThresholdBytes();
        UUID uploadId = UUID.randomUUID();
        Instant expiresAt = now.plus(policy.presignTtl());

        if (!multipart) {
            var presigned = storage.presignPut(storageKey, contentType, policy.presignTtl());

            MediaUploadEntity upload = new MediaUploadEntity(
                    uploadId,
                    mediaId,
                    UploadStatus.initiated,
                    req.sizeBytes(),
                    contentType,
                    false,
                    1,
                    null,
                    null,
                    now,
                    expiresAt,
                    null,
                    null
            );
            uploadRepository.save(upload);

            return new CreateUploadResponse(
                    mediaId,
                    uploadId,
                    false,
                    presigned.url(),
                    presigned.headers(),
                    List.of(),
                    null,
                    null,
                    expiresAt
            );
        }

        long partSize = policy.multipartPartSizeBytes();
        int partsCount = (int) Math.ceil((double) req.sizeBytes() / partSize);

        var init = storage.initiateMultipart(storageKey, contentType);

        List<UploadPartResponse> parts = buildMultipartParts(storageKey, init.uploadId(), partsCount);

        MediaUploadEntity upload = new MediaUploadEntity(
                uploadId,
                mediaId,
                UploadStatus.initiated,
                req.sizeBytes(),
                contentType,
                true,
                partsCount,
                partSize,
                init.uploadId(),
                now,
                expiresAt,
                null,
                null
        );
        uploadRepository.save(upload);

        return new CreateUploadResponse(
                mediaId,
                uploadId,
                true,
                null,
                null,
                parts,
                partsCount,
                partSize,
                expiresAt
        );
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public CompleteUploadResponse completeUpload(Jwt jwt, UUID uploadId, CompleteUploadRequest req) {
        MediaUploadEntity upload = uploadRepository.findByUploadId(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload not found"));

        MediaAssetEntity asset = assetRepository.findById(upload.getMediaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));

        ensureOwnerOrInternal(jwt, asset);

        if (upload.getStatus() != UploadStatus.initiated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload already finalized");
        }

        if (upload.isMultipart()) {
            if (req == null || req.parts() == null || req.parts().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing multipart parts");
            }
            if (upload.getPartsCount() != null && req.parts().size() != upload.getPartsCount()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unexpected parts count");
            }
            ensureUniqueParts(req.parts(), upload.getPartsCount());
            List<CompletedUploadPart> parts = req.parts().stream()
                    .map(part -> new CompletedUploadPart(part.partNumber(), normalizeETag(part)))
                    .toList();
            storage.completeMultipart(asset.getStorageKey(), upload.getS3UploadId(), parts);
        }

        ObjectInfo info = storage.headObject(asset.getStorageKey());
        validateCompleted(asset, upload, info);

        asset.setSizeBytes(info.contentLength());
        asset.setMimeType(policy.normalizeContentType(info.contentType()));
        asset.setStatus(MediaStatus.ready);
        asset.setUpdatedAt(Instant.now());
        assetRepository.save(asset);

        upload.setStatus(UploadStatus.completed);
        upload.setCompletedAt(Instant.now());
        uploadRepository.save(upload);

        return new CompleteUploadResponse(asset.getMediaId(), asset.getStatus());
    }

    @Transactional
    public CompleteUploadResponse directUpload(Jwt jwt, DirectUploadRequest req, MultipartFile file) {
        if (!scopeHelper.hasAnyScope(jwt, INTERNAL_SCOPES)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal scope required");
        }
        if (req == null || req.kind() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing media kind");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing file");
        }

        UUID ownerUserId = resolveOwnerUserId(jwt, req.ownerUserId());
        String contentType = policy.normalizeContentType(req.contentType());
        if (contentType == null || contentType.isBlank()) {
            contentType = policy.normalizeContentType(file.getContentType());
        }
        if (contentType == null || contentType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentType is required");
        }

        long sizeBytes = file.getSize();
        policy.validateUpload(req.kind(), contentType, sizeBytes);

        UUID mediaId = UUID.randomUUID();
        String storageKey = buildStorageKey(req.kind().name(), mediaId);
        Instant now = Instant.now();
        String fileName = resolveFileName(req.fileName(), file);

        MediaAssetEntity asset = new MediaAssetEntity(
                mediaId,
                ownerUserId,
                req.kind(),
                MediaStatus.pending,
                storageKey,
                contentType,
                null,
                null,
                null,
                null,
                fileName,
                now,
                null,
                null
        );

        assetRepository.save(asset);

        try (var inputStream = file.getInputStream()) {
            storage.putObject(storageKey, contentType, sizeBytes, inputStream);
        } catch (IOException | RuntimeException ex) {
            asset.setStatus(MediaStatus.rejected);
            asset.setUpdatedAt(Instant.now());
            assetRepository.save(asset);
            safeDelete(storageKey);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload failed", ex);
        }

        asset.setSizeBytes(sizeBytes);
        asset.setMimeType(contentType);
        asset.setStatus(MediaStatus.ready);
        asset.setUpdatedAt(Instant.now());
        assetRepository.save(asset);

        return new CompleteUploadResponse(asset.getMediaId(), asset.getStatus());
    }

    @Transactional
    public void abortUpload(Jwt jwt, UUID uploadId) {
        MediaUploadEntity upload = uploadRepository.findByUploadId(uploadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload not found"));

        MediaAssetEntity asset = assetRepository.findById(upload.getMediaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));

        ensureOwnerOrInternal(jwt, asset);

        if (upload.isMultipart() && upload.getS3UploadId() != null) {
            storage.abortMultipart(asset.getStorageKey(), upload.getS3UploadId());
        }

        upload.setStatus(UploadStatus.aborted);
        upload.setCompletedAt(Instant.now());
        uploadRepository.save(upload);

        safeDelete(asset.getStorageKey());
        asset.setStatus(MediaStatus.deleted);
        asset.setDeletedAt(Instant.now());
        assetRepository.save(asset);
    }

    @Transactional(readOnly = true)
    public List<ResolvedMedia> resolve(Jwt jwt, List<UUID> mediaIds) {
        Instant expiresAt = Instant.now().plus(policy.presignTtl());
        List<MediaAssetEntity> assets = assetRepository.findByMediaIdIn(mediaIds);
        var byId = assets.stream()
                .collect(java.util.stream.Collectors.toMap(MediaAssetEntity::getMediaId, a -> a));
        var userIdOpt = currentUserProvider.getUserId(jwt);
        boolean hasInternalScope = scopeHelper.hasAnyScope(jwt, INTERNAL_SCOPES);

        return mediaIds.stream()
                .map(id -> {
                    MediaAssetEntity asset = byId.get(id);
                    if (asset == null) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found: " + id);
                    }
                    if (!hasInternalScope && !isOwner(userIdOpt, asset) && !isPublicResolvable(asset)) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
                    }
                    if (asset.getStatus() != MediaStatus.ready) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Media not ready: " + asset.getMediaId());
                    }
                    var presigned = storage.presignGet(asset.getStorageKey(), policy.presignTtl());
                    return new ResolvedMedia(
                            asset.getMediaId(),
                            asset.getKind(),
                            presigned.url(),
                            asset.getMimeType(),
                            asset.getSizeBytes(),
                            asset.getDurationSeconds(),
                            asset.getWidth(),
                            asset.getHeight(),
                            expiresAt
                    );
                })
                .toList();
    }

    @Transactional
    public void deleteMedia(Jwt jwt, UUID mediaId) {
        MediaAssetEntity asset = assetRepository.findById(mediaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));

        ensureOwnerOrInternal(jwt, asset);

        storage.deleteObject(asset.getStorageKey());

        asset.setStatus(MediaStatus.deleted);
        asset.setDeletedAt(Instant.now());
        assetRepository.save(asset);
    }

    private void validateCompleted(MediaAssetEntity asset, MediaUploadEntity upload, ObjectInfo info) {
        if (info.contentLength() <= 0) {
            markRejected(asset, upload, "Empty content");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty content");
        }

        String actualContentType = policy.normalizeContentType(info.contentType());
        if (actualContentType == null) {
            markRejected(asset, upload, "Missing content type");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing content type");
        }
        if (!upload.getExpectedMimeType().equalsIgnoreCase(actualContentType)) {
            markRejected(asset, upload, "Unexpected content type");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unexpected content type");
        }

        if (!upload.getExpectedSizeBytes().equals(info.contentLength())) {
            markRejected(asset, upload, "Unexpected content size");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unexpected content size");
        }

        long maxBytes = policy.maxBytesFor(asset.getKind(), actualContentType);
        if (info.contentLength() > maxBytes) {
            markRejected(asset, upload, "Content too large");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content too large");
        }
    }

    private void markRejected(MediaAssetEntity asset, MediaUploadEntity upload, String message) {
        upload.setStatus(UploadStatus.failed);
        upload.setErrorMessage(message);
        upload.setCompletedAt(Instant.now());
        uploadRepository.save(upload);

        asset.setStatus(MediaStatus.rejected);
        asset.setUpdatedAt(Instant.now());
        assetRepository.save(asset);

        safeDelete(asset.getStorageKey());
    }

    private void ensureOwnerOrInternal(Jwt jwt, MediaAssetEntity asset) {
        if (scopeHelper.hasAnyScope(jwt, INTERNAL_SCOPES)) {
            return;
        }
        UUID userId = currentUserProvider.getUserId(jwt)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_id claim missing"));
        if (!asset.getOwnerUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    private boolean isOwner(java.util.Optional<UUID> userIdOpt, MediaAssetEntity asset) {
        return userIdOpt.isPresent() && asset.getOwnerUserId().equals(userIdOpt.get());
    }

    private boolean isPublicResolvable(MediaAssetEntity asset) {
        return switch (asset.getKind()) {
            case card_image, card_audio, card_video, deck_icon -> true;
            default -> false;
        };
    }

    private List<UploadPartResponse> buildMultipartParts(String storageKey, String uploadId, int partsCount) {
        return java.util.stream.IntStream.rangeClosed(1, partsCount)
                .mapToObj(partNumber -> {
                    var presigned = storage.presignUploadPart(storageKey, uploadId, partNumber, policy.presignTtl());
                    return new UploadPartResponse(partNumber, presigned.url(), presigned.headers());
                })
                .toList();
    }

    private String buildStorageKey(String kind, UUID mediaId) {
        return "media/" + kind + "/" + mediaId;
    }

    private String normalizeETag(CompletedPartRequest part) {
        String eTag = part.eTag();
        if (eTag == null) {
            return null;
        }
        return eTag.replace("\"", "");
    }

    private void ensureUniqueParts(List<CompletedPartRequest> parts, Integer partsCount) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (CompletedPartRequest part : parts) {
            int partNumber = part.partNumber();
            if (partsCount != null && partNumber > partsCount) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid part number: " + partNumber);
            }
            if (!seen.add(partNumber)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate part number: " + partNumber);
            }
        }
    }

    private UUID resolveOwnerUserId(Jwt jwt, UUID requestedOwnerId) {
        if (requestedOwnerId != null) {
            return requestedOwnerId;
        }
        return currentUserProvider.getUserId(jwt)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "ownerUserId is required"));
    }

    private String resolveFileName(String requestedName, MultipartFile file) {
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName;
        }
        String original = file.getOriginalFilename();
        return (original == null || original.isBlank()) ? null : original;
    }

    private void safeDelete(String key) {
        try {
            storage.deleteObject(key);
        } catch (RuntimeException ignored) {
        }
    }
}
