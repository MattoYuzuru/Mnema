package app.mnema.importer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({CoreClientProps.class, MediaClientProps.class})
public class RestClientConfig {

    @Bean
    public RestClient coreRestClient(CoreClientProps props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .build();
    }

    @Bean
    public RestClient mediaRestClient(MediaClientProps props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .build();
    }
}
