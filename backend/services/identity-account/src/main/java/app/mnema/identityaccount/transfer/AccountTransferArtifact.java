package app.mnema.identityaccount.transfer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

record AccountTransferArtifact(AccountTransferBundle bundle, Map<UUID, byte[]> avatarBlobs) {
    AccountTransferArtifact {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(avatarBlobs, "avatarBlobs");
        Map<UUID, byte[]> copied = new LinkedHashMap<>();
        avatarBlobs.forEach((id, bytes) -> copied.put(id, bytes.clone()));
        avatarBlobs = Map.copyOf(copied);
        long expected = bundle.accounts().stream().filter(account -> account.avatar() != null).count();
        AccountTransferBundle.require(expected == avatarBlobs.size(), "avatar_blob_count_mismatch");
        for (AccountTransferBundle.Account account : bundle.accounts()) {
            if (account.avatar() == null) continue;
            byte[] bytes = avatarBlobs.get(account.avatar().assetId());
            AccountTransferBundle.require(bytes != null, "missing_avatar_blob");
            AvatarBinary.validate(account.avatar(), bytes);
        }
    }

    byte[] avatar(UUID assetId) {
        byte[] bytes = avatarBlobs.get(assetId);
        return bytes == null ? null : bytes.clone();
    }
}
