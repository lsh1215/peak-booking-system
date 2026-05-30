package com.ecommerce.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI(
            @Value("${springdoc.info.title:E-Commerce API}") String title,
            @Value("${springdoc.info.version:v1}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title(title)
                        .version(version));
    }
}
