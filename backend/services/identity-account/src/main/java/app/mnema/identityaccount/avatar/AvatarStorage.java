package app.mnema.identityaccount.avatar;

import app.mnema.identityaccount.contract.AccountFailure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AvatarStorage implements AutoCloseable, OwnedAvatarEraser {
    private final S3Client s3;
    private final String bucket;
    private final boolean configured;

    public AvatarStorage(@Value("${identity.avatar.endpoint}") URI endpoint,
                         @Value("${identity.avatar.region}") String region,
                         @Value("${identity.avatar.bucket}") String bucket,
                         @Value("${identity.avatar.access-key}") String access,
                         @Value("${identity.avatar.secret-key}") String secret,
                         @Value("${identity.avatar.allow-loopback-http:false}") boolean loopback,
                         @Value("${identity.avatar.allow-staging-minio-http:false}") boolean stagingMinio) {
        boolean loopbackHttp = loopback && "http".equals(endpoint.getScheme()) &&
                Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost());
        boolean stagingMinioHttp = stagingMinio && endpoint.equals(URI.create("http://minio:9000"));
        if (!"https".equals(endpoint.getScheme()) && !loopbackHttp && !stagingMinioHttp)
            throw new IllegalArgumentException("Avatar endpoint requires HTTPS");
        this.bucket = bucket;
        configured = !access.isBlank() && !secret.isBlank();
        s3 = S3Client.builder().endpointOverride(endpoint).region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(configured ? access : "unconfigured",
                                configured ? secret : "unconfigured")))
                .overrideConfiguration(
                        c -> c.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5)))
                .build();
    }

    public boolean configured() {
        return configured;
    }

    public String put(String key, UUID account, UUID asset, AvatarImage image) {
        requireConfigured();
        try {
            return s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(image.contentType())
                            .metadata(Map.of("account-id", account.toString(), "asset-id", asset.toString())).build(),
                    RequestBody.fromBytes(image.bytes())).versionId();
        } catch (RuntimeException e) {
            throw new AccountFailure(503, "avatar_storage_unavailable");
        }
    }

    public byte[] get(String key) {
        requireConfigured();
        try (var response = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            if (response.response().contentLength() > AvatarImage.MAX_BYTES)
                throw new AccountFailure(503, "avatar_storage_invalid");
            byte[] bytes = response.readNBytes(AvatarImage.MAX_BYTES + 1);
            if (bytes.length > AvatarImage.MAX_BYTES) throw new AccountFailure(503, "avatar_storage_invalid");
            return bytes;
        } catch (NoSuchKeyException e) {
            throw new AccountFailure(404, "avatar_not_found");
        } catch (Exception e) {
            if (e instanceof AccountFailure failure) throw failure;
            throw new AccountFailure(503, "avatar_storage_unavailable");
        }
    }

    @Override
    public void deleteOwned(OwnedAvatarEraser.Manifest manifest) {
        requireConfigured();
        String exactKey = "account-avatar/" + manifest.accountId() + "/" + manifest.assetId();
        if (!exactKey.equals(manifest.storageKey())) throw new AccountFailure(409, "avatar_ownership_mismatch");
        try {
            List<StoredVersion> versions = exactVersions(manifest.storageKey());
            if (versions.isEmpty()) versions = unversionedObject(manifest);
            var verified = new ArrayList<StoredVersion>();
            for (StoredVersion version : versions) {
                if (version.deleteMarker() || requireOwned(manifest, version.versionId())) verified.add(version);
            }
            for (StoredVersion version : verified) deleteVersion(manifest.storageKey(), version.versionId());
            if (!exactVersions(manifest.storageKey()).isEmpty() || currentObjectExists(manifest.storageKey()))
                throw new AccountFailure(503, "avatar_storage_unavailable");
        } catch (S3Exception failure) {
            throw new AccountFailure(503, "avatar_storage_unavailable");
        } catch (AccountFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AccountFailure(503, "avatar_storage_unavailable");
        }
    }

    private List<StoredVersion> exactVersions(String key) {
        var response = s3.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).prefix(key)
                .maxKeys(1000).build());
        if (Boolean.TRUE.equals(response.isTruncated()))
            throw new AccountFailure(409, "avatar_version_set_too_large");
        var result = new ArrayList<StoredVersion>();
        response.versions().stream().filter(version -> key.equals(version.key()))
                .forEach(version -> result.add(new StoredVersion(version.versionId(), false)));
        response.deleteMarkers().stream().filter(marker -> key.equals(marker.key()))
                .forEach(marker -> result.add(new StoredVersion(marker.versionId(), true)));
        return result;
    }

    private List<StoredVersion> unversionedObject(OwnedAvatarEraser.Manifest manifest) {
        return requireOwned(manifest, null) ? List.of(new StoredVersion(null, false)) : List.of();
    }

    private boolean requireOwned(OwnedAvatarEraser.Manifest manifest, String versionId) {
        try {
            var request = HeadObjectRequest.builder().bucket(bucket).key(manifest.storageKey());
            if (versionId != null && !versionId.isBlank()) request.versionId(versionId);
            var object = s3.headObject(request.build());
            if (!manifest.accountId().toString().equals(object.metadata().get("account-id")) ||
                    !manifest.assetId().toString().equals(object.metadata().get("asset-id")))
                throw new AccountFailure(409, "avatar_ownership_mismatch");
            return true;
        } catch (NoSuchKeyException missing) {
            return false;
        } catch (S3Exception missing) {
            if (missing.statusCode() == 404) return false;
            throw missing;
        }
    }

    private void deleteVersion(String key, String versionId) {
        try {
            var request = DeleteObjectRequest.builder().bucket(bucket).key(key);
            if (versionId != null && !versionId.isBlank()) request.versionId(versionId);
            s3.deleteObject(request.build());
        } catch (NoSuchKeyException missing) {
            // A concurrent/retried exact delete is success.
        } catch (S3Exception missing) {
            if (missing.statusCode() != 404) throw missing;
        }
    }

    private boolean currentObjectExists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException missing) {
            return false;
        } catch (S3Exception missing) {
            if (missing.statusCode() == 404) return false;
            throw missing;
        }
    }

    private record StoredVersion(String versionId, boolean deleteMarker) {
    }

    private void requireConfigured() {
        if (!configured) throw new AccountFailure(503, "avatar_storage_unavailable");
    }

    @Override
    public void close() {
        s3.close();
    }
}
