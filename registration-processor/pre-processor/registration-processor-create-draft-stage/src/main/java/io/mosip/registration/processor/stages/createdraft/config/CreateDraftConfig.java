package io.mosip.registration.processor.stages.createdraft.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.mosip.registration.processor.stages.createdraft.stage.CreateDraftStage;

/**
 * Spring configuration for the Create Draft stage.
 */
@Configuration
public class CreateDraftConfig {

    @Bean
    public CreateDraftStage getCreateDraftStage() {
        return new CreateDraftStage();
    }
}
