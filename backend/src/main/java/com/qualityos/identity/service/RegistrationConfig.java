package com.qualityos.identity.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@EnableConfigurationProperties(RegistrationProperties.class)
class RegistrationConfig {

    /** Registration is public in production, so a misconfigured deployment must not start. */
    @Bean
    @Profile("prod")
    InitializingBean publicRegistrationGuard(RegistrationProperties registration) {
        return () -> {
            if (!registration.blocksUnverified()) {
                throw new IllegalStateException("Production requires verified email addresses before sign-in.");
            }
        };
    }
}
