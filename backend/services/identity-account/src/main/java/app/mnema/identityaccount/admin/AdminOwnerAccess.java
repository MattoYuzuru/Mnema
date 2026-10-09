package app.mnema.identityaccount.admin;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class AdminOwnerAccess {
    private final UUID owner;
    private final AccountStore accounts;

    public AdminOwnerAccess(AccountStore accounts, @Value("${identity.admin.owner-id:}") String configuredOwner) {
        this.accounts = accounts;
        if (configuredOwner.isEmpty()) owner = null;
        else {
            owner = UUID.fromString(configuredOwner);
            if (!owner.toString().equals(configuredOwner) || owner.equals(new UUID(0, 0)) || owner.variant() != 2 || owner.version() < 1 || owner.version() > 8)
                throw new IllegalArgumentException("Admin owner must be a canonical entity UUID");
        }
    }

    public void require(AccountAccess actor) {
        accounts.require(actor, false);
        if (owner == null || !owner.equals(actor.accountId())) throw AccountFailure.forbidden();
    }
}
