package com.arthadhruva.riskengine.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * Registers the multicaster under Spring's well-known bean name so it replaces the default one
 * used for {@code @EventListener} dispatch. The default multicaster stops calling further
 * listeners as soon as one throws; a custom {@link org.springframework.util.ErrorHandler} here
 * logs and swallows that exception instead, so (per design decision 1's risk mitigation) one
 * listener's failure never prevents another listener -- or the original request that published
 * the event -- from completing.
 */
@Configuration
public class EventPublishingConfig {

    private static final Logger log = LoggerFactory.getLogger(EventPublishingConfig.class);

    @Bean(name = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME)
    public ApplicationEventMulticaster applicationEventMulticaster() {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setErrorHandler(throwable ->
                log.error("Domain event listener failed; other listeners and the triggering request are unaffected", throwable));
        return multicaster;
    }
}
