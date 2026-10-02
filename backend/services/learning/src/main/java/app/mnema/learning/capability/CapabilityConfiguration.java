package app.mnema.learning.capability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CapabilityFlags.class)
class CapabilityConfiguration { }
