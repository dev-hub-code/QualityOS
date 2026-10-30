package com.qualityos.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI qualityOsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("QualityOS API")
                .version("v1")
                .description("Manufacturing quality management and product traceability platform."));
    }
}
