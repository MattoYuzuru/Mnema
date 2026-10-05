package app.mnema.learning.promo;

import app.mnema.learning.platform.security.AccountStandings;
import app.mnema.learning.usage.UsageClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A movable clock for promo and popup time, and an Identity that answers from a map: no network, so every standing is a decision of the test. */
@TestConfiguration(proxyBeanMethods = false)
class PromoTestConfiguration {
    static final String START = "2026-10-02T09:00:42Z";

    static final class MutableClock implements UsageClock {
        private volatile Instant now = Instant.parse(START);

        @Override
        public Instant now() {
            return now;
        }

        void set(String instant) {
            now = Instant.parse(instant);
        }
    }

    static final class StubStandings implements AccountStandings {
        private final Map<String, Standing> bySubject = new ConcurrentHashMap<>();
        volatile boolean unavailable;

        void put(java.util.UUID account, boolean verified, boolean admin) {
            bySubject.put(account.toString(), new Standing(verified, admin));
        }

        @Override
        public Optional<Standing> of(Jwt token) {
            return unavailable ? Optional.empty() : Optional.ofNullable(bySubject.get(token.getSubject()));
        }
    }

    @Bean
    @Primary
    MutableClock promoTestClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    StubStandings promoTestStandings() {
        return new StubStandings();
    }
}
