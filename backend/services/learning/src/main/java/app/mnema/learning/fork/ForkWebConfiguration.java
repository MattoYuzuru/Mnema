package app.mnema.learning.fork;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the preparing guard on the deck routes. */
@Configuration(proxyBeanMethods = false)
class ForkWebConfiguration implements WebMvcConfigurer {
    private final PreparingGuardInterceptor guard;

    ForkWebConfiguration(PreparingGuardInterceptor guard) { this.guard = guard; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/decks/**");
    }
}
