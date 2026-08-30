package app.mnema.identityaccount.contract;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityContractProperties.class)
class IdentityContractConfiguration {

    @Bean
    IssuerContract issuerContract(IdentityContractProperties properties) {
        return new IssuerContract(properties.issuer());
    }
}
