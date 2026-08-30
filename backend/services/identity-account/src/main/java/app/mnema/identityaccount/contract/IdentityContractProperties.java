package app.mnema.identityaccount.contract;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("identity")
public record IdentityContractProperties(URI issuer) {
}
