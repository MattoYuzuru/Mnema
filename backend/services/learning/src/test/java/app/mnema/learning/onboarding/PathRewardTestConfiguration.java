package app.mnema.learning.onboarding;

import app.mnema.learning.platform.security.AccountStandings;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Optional;

/** An Identity that calls every account a learner with a verified e-mail: the redemption of a code needs that standing, and the path reward tests are not about it. */
@TestConfiguration(proxyBeanMethods = false)
class PathRewardTestConfiguration {
    @Bean
    @Primary
    AccountStandings pathRewardStandings() {
        return token -> Optional.of(new AccountStandings.Standing(true, false));
    }
}
