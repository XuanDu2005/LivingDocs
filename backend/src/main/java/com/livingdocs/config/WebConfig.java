package com.livingdocs.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cross-cutting OpenAPI documentation configuration.
 *
 * <p>CORS is configured centrally inside the SecurityConfig so the two
 * sources of truth do not drift. This class only owns Swagger metadata.
 */
@Configuration
public class WebConfig {

    @Bean
    public OpenAPI livingDocsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LivingDocs API")
                        .description("AI-assisted documentation maintenance platform — REST API")
                        .version("0.2.0")
                        .contact(new Contact().name("LivingDocs Engineering"))
                        .license(new License().name("Proprietary")));
    }
}