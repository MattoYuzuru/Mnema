package app.mnema.auth.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth.local")
data class LocalAuthProps(
    val accessTokenTtl: Duration = Duration.ofHours(8),
    val maxFailedAttempts: Int = 5,
    val lockDuration: Duration = Duration.ofMinutes(15),
    val registerLimit: Int = 10,
    val registerWindow: Duration = Duration.ofHours(1),
    val loginLimit: Int = 30,
    val loginWindow: Duration = Duration.ofMinutes(10)
)
