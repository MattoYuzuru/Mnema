package app.mnema.identityaccount.contract;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable OpenID Connect identity contract. The issuer is deployment configuration;
 * the subject is the canonical, never-reassigned account UUID.
 */
public final class IssuerContract {

    private final String issuer;

    public IssuerContract(URI issuer) {
        Objects.requireNonNull(issuer, "issuer");
        if (!issuer.isAbsolute()
                || !"https".equals(issuer.getScheme().toLowerCase(Locale.ROOT))
                || issuer.getHost() == null
                || issuer.getHost().isBlank()
                || issuer.getUserInfo() != null
                || issuer.getQuery() != null
                || issuer.getFragment() != null) {
            throw new IllegalArgumentException(
                    "identity issuer must be an absolute HTTPS URL without user info, query, or fragment"
            );
        }
        this.issuer = issuer.toASCIIString();
    }

    public IssuerSubject identify(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        if (accountId.equals(new UUID(0L, 0L))
                || accountId.variant() != 2
                || accountId.version() < 1
                || accountId.version() > 8) {
            throw new IllegalArgumentException("accountId must be a non-nil RFC 9562 UUID");
        }
        return new IssuerSubject(issuer, accountId.toString());
    }

    public String issuer() {
        return issuer;
    }

    public record IssuerSubject(String issuer, String subject) {
    }
}
