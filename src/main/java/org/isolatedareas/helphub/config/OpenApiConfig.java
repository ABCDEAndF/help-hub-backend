package org.isolatedareas.helphub.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI helpHubOpenApi() {
        String scheme = "bearerAuth";
        return new OpenAPI()
            .info(new Info()
                .title("Isolated Areas Help Hub API")
                .version("1.0.0")
                .description("Resident, operator, routing, impact, and AI-assistant APIs."))
            .addSecurityItem(new SecurityRequirement().addList(scheme))
            .components(new Components().addSecuritySchemes(scheme,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}

