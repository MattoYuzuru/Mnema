package app.mnema.learning.generation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code learning.generation.*}; the rest of the module is component-scanned. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GenerationSettings.class)
class GenerationConfiguration {
}
