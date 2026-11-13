package com.qualityos.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SecondFactorConfig.SecondFactorProperties.class)
public class SecondFactorConfig {

    /** @param key 32 bytes, base64 encoded, that seal authenticator secrets in the database */
    @ConfigurationProperties("qualityos.security.second-factor")
    public record SecondFactorProperties(String key) {
    }

    @Bean
    SecretBox secretBox(SecondFactorProperties properties, JwtProperties jwt) {
        return SecretBox.from(properties.key(), jwt.allowEphemeralKeys());
    }
}
