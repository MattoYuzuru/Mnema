package app.mnema.learning.library;

import java.util.Objects;
import java.util.UUID;

/**
 * Who asks: a signed-in account, or a guest. {@code network} is the client network for abuse limits (IPv4 address or the /64 of an IPv6 address) and
 * {@code coarse} the /48 of an IPv6 address; both are absent when the request has no usable peer (or, for {@code coarse}, comes from IPv4), and neither
 * ever takes part in an access decision.
 */
public record Viewer(UUID accountId, String network, String coarse) {
    public static Viewer guest(String network) { return new Viewer(null, network, null); }

    public static Viewer guest(String network, String coarse) { return new Viewer(null, network, coarse); }

    public static Viewer account(UUID accountId, String network) { return new Viewer(Objects.requireNonNull(accountId), network, null); }

    public boolean isGuest() { return accountId == null; }
}
