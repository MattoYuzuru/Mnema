package app.mnema.user.media.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(MediaClientProps::class)
class MediaClientConfig {
    @Bean
    fun mediaRestClient(props: MediaClientProps): RestClient =
        RestClient.builder()
            .baseUrl(props.baseUrl)
            .build()
}
