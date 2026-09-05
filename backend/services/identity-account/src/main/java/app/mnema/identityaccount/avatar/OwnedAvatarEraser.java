package app.mnema.identityaccount.avatar;

import java.util.UUID;

public interface OwnedAvatarEraser {
    record Manifest(UUID accountId, UUID assetId, String storageKey, String storageVersion, byte[] contentSha256) {
    }

    void deleteOwned(Manifest manifest);
}
