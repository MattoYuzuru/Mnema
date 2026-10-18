package app.mnema.learning.onboarding;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.UUID;

/** Small helpers of the backfill tests. */
final class BackfillTestSupport {
    private BackfillTestSupport() { }

    /** The account id just below {@code id} in the order of PostgreSQL's uuid (unsigned, byte by byte): {@code (prev(id), id]} holds exactly {@code id}. */
    static UUID prev(UUID id) {
        BigInteger value = new BigInteger(1, ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array()).subtract(BigInteger.ONE);
        byte[] bytes = value.toByteArray();
        byte[] padded = new byte[16];
        System.arraycopy(bytes, Math.max(0, bytes.length - 16), padded, Math.max(0, 16 - bytes.length), Math.min(16, bytes.length));
        ByteBuffer buffer = ByteBuffer.wrap(padded);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
