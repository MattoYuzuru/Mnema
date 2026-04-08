package app.mnema.auth.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(UserClientProps::class)
class UserClientConfig {
    @Bean
    fun userRestClient(props: UserClientProps): RestClient =
        RestClient.builder()
            .baseUrl(props.baseUrl)
            .build()
}
