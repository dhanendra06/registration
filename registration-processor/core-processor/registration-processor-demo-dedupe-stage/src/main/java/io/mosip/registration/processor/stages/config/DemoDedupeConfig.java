package io.mosip.registration.processor.stages.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.rest.client.service.impl.RegistrationProcessorRestClientServiceImpl;
import io.mosip.registration.processor.stages.demodedupe.DemoDedupe;
import io.mosip.registration.processor.stages.demodedupe.DemodedupeProcessor;

@Configuration
public class DemoDedupeConfig {

    /**
     * Creates a singleton bean for DemoDedupe
     * Using eager initialization for faster first-time access.
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    public DemoDedupe demoDedupe() {
        return new DemoDedupe();
    }

    /**
     * Creates a singleton bean for DemodedupeProcessor
     * Marked as Lazy only if it's not always required at startup.
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    @Lazy(false)
    public DemodedupeProcessor demodedupeProcessor() {
        return new DemodedupeProcessor();
    }

    /**
     * Singleton RestClientService bean with thread-safe reuse
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
    public RegistrationProcessorRestClientService<Object> registrationProcessorRestClientService() {
        return new RegistrationProcessorRestClientServiceImpl();
    }
}