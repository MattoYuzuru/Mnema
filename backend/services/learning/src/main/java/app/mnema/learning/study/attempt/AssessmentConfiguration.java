package app.mnema.learning.study.attempt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssessmentSettings.class)
class AssessmentConfiguration { }
