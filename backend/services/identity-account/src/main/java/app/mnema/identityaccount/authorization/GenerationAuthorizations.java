package app.mnema.identityaccount.authorization;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.BrowserSessions;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.Principal;
import java.util.UUID;

/**
 * Both lookup and save check generation, including code exchange and refresh, across replicas.
 */
public final class GenerationAuthorizations implements OAuth2AuthorizationService {
    private final OAuth2AuthorizationService delegate;
    private final AccountStore accounts;
    private final TransactionTemplate transactions;

    public GenerationAuthorizations(OAuth2AuthorizationService delegate, AccountStore accounts,
                                    TransactionTemplate transactions) {
        this.delegate = delegate;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    public static long generation(OAuth2Authorization authorization) {
        String generation = authorization.getAttribute("generation");
        if (generation != null) return Long.parseLong(generation);
        Authentication principal = authorization.getAttribute(Principal.class.getName());
        return BrowserSessions.access(principal).generation();
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        transactions.executeWithoutResult(s -> {
            long generation = generation(authorization);
            try {
                accounts.require(new AccountAccess(UUID.fromString(authorization.getPrincipalName()), generation),
                        true);
            } catch (AccountFailure e) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            }
            delegate.save(
                    OAuth2Authorization.from(authorization).attribute("generation", Long.toString(generation)).build());
        });
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return current(delegate.findById(id));
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
        return current(delegate.findByToken(token, type));
    }

    private OAuth2Authorization current(OAuth2Authorization authorization) {
        if (authorization == null) return null;
        try {
            accounts.require(
                    new AccountAccess(UUID.fromString(authorization.getPrincipalName()), generation(authorization)),
                    false);
            return authorization;
        } catch (AccountFailure e) {
            return null;
        }
    }
}
