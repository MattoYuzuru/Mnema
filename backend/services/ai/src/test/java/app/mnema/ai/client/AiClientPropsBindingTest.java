package app.mnema.ai.client;

import app.mnema.ai.client.core.CoreClientProps;
import app.mnema.ai.client.media.MediaClientProps;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AiClientPropsBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(ClientPropsConfiguration.class)
            .withPropertyValues(
                    "app.core.base-url=http://core.test",
                    "app.media.base-url=http://media.test"
            );

    @Test
    void startsWhenBothInternalTokensAreConfigured() {
        contextRunner
                .withPropertyValues(
                        "app.core.internal-token=core-token",
                        "app.media.internal-token=media-token"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsStartupWhenCoreInternalTokenIsBlank() {
        contextRunner
                .withPropertyValues(
                        "app.core.internal-token= ",
                        "app.media.internal-token=media-token"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsStartupWhenMediaInternalTokenIsMissing() {
        contextRunner
                .withPropertyValues("app.core.internal-token=core-token")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({CoreClientProps.class, MediaClientProps.class})
    static class ClientPropsConfiguration {
    }
}
