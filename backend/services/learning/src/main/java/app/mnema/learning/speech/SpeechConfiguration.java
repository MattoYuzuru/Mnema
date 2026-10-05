package app.mnema.learning.speech;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code learning.speech.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpeechInputSettings.class)
class SpeechConfiguration { }
