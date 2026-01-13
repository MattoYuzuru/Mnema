package app.mnema.core.media.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MediaClientProps.class)
public class MediaClientConfig {

    @Bean
    public RestClient mediaRestClient(MediaClientProps props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .build();
    }
}
