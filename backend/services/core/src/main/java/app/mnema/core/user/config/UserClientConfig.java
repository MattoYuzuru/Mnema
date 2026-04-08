package app.mnema.core.user.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(UserClientProps.class)
public class UserClientConfig {

    @Bean
    public RestClient userRestClient(UserClientProps props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .build();
    }
}
