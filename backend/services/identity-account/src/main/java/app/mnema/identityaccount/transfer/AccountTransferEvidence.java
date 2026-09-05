package app.mnema.identityaccount.transfer;

record AccountTransferEvidence(
        int schemaVersion,
        String kind,
        String status,
        int accountCount,
        int credentialCount,
        int externalIdentityCount,
        int avatarCount,
        long avatarBytes,
        String projectionSha256,
        String avatarSetSha256) {
}
