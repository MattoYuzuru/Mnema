package app.mnema.learning.update.apply;

import app.mnema.learning.platform.idempotency.CommandIdentity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The receipt of an apply command. Command receipts are keyed by the command id alone and the combined publication under the command stores its own (scope
 * {@code deck.combined}) under the id the client sent, so the receipt that holds the ANSWER of the apply command (its counts and its base) lives under an id derived from the client's,
 * a version 4 UUID computed with SHA-256: one client id is one apply receipt, written in the same transaction as the publication.
 */
final class ApplyReceipt {
    static final String SCOPE = "deck.update";
    static final String TYPE = "apply";

    private ApplyReceipt() { }

    static CommandIdentity identity(UUID owner, UUID command) {
        return new CommandIdentity(derive(command), owner, SCOPE, TYPE);
    }

    static UUID derive(UUID command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("mnema.deck.update.apply:" + command).getBytes(StandardCharsets.UTF_8));
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            long high = (bytes.getLong() & ~0xF000L) | 0x4000L;
            long low = (bytes.getLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
            return new UUID(high, low);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", failure);
        }
    }
}
