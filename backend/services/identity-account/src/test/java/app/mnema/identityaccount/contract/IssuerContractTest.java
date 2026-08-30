package app.mnema.identityaccount.contract;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuerContractTest {

    private final IssuerContract contract = new IssuerContract(URI.create("https://identity.mnema.app/oidc"));

    @Test
    void identifiesAccountByExactIssuerAndCanonicalUuidSubject() {
        UUID accountId = UUID.fromString("018f6b77-c4d8-7a2e-8ca2-0242ac120002");

        assertThat(contract.identify(accountId)).isEqualTo(new IssuerContract.IssuerSubject(
                "https://identity.mnema.app/oidc",
                "018f6b77-c4d8-7a2e-8ca2-0242ac120002"
        ));
        assertThat(contract.identify(accountId)).isEqualTo(contract.identify(accountId));
        assertThat(contract.issuer()).isEqualTo("https://identity.mnema.app/oidc");
    }

    @Test
    void rejectsUnsafeOrRequestDerivedIssuerShapes() {
        assertThatThrownBy(() -> new IssuerContract(URI.create("http://identity.mnema.app")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssuerContract(URI.create("https://user@identity.mnema.app")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssuerContract(URI.create("https://identity.mnema.app?tenant=x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssuerContract(URI.create("https://identity.mnema.app#issuer")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IssuerContract(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNilNonStandardAndUnknownVersionAccountIds() {
        assertThatThrownBy(() -> contract.identify(new UUID(0L, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.identify(new UUID(0x018f6b77c4d87a2eL, 0x0ca20242ac120002L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.identify(new UUID(0x018f6b77c4d80a2eL, 0x8ca20242ac120002L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.identify(null)).isInstanceOf(NullPointerException.class);
    }
}
