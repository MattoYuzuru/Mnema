package app.mnema.learning.onboarding;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The settings of an integration test of the sample deck. The editorial deck gets its id when the test creates it, which is after the context is up, so the template id is not
 * a property here: {@link #template} names it and marks it exactly as a restart with the id configured would ({@link SampleTemplateMarker}). The switch is on and an account
 * gets 3 free AI checks.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SampleDeckTestConfiguration {
    private static final AtomicReference<UUID> TEMPLATE = new AtomicReference<>();

    @Bean
    @Primary
    SampleDeckSettings testSampleDeckSettings() {
        return new SampleDeckSettings(true, "", 3) {
            @Override
            Optional<UUID> templateId() {
                return Optional.ofNullable(TEMPLATE.get());
            }

            @Override
            boolean configured() {
                return TEMPLATE.get() != null;
            }
        };
    }

    /** Names {@code deck} the template WITHOUT marking it: a deck created after the application started. */
    public static void templateWithoutMarking(UUID deck) {
        TEMPLATE.set(deck);
    }

    /** Names {@code deck} the template and marks it, as the application does when it is ready; {@code null} configures none. */
    public static void template(ApplicationContext context, UUID deck) {
        TEMPLATE.set(deck);
        if (deck != null) context.getBean(SampleTemplateMarker.class).mark();
    }
}
