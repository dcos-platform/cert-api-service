package com.dcos.platform.certapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI certApiOpenAPI() {
        final String securitySchemeName = "basicAuth";
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Cert API Service")
                                .description("Domain-neutral certificate lifecycle REST API demo")
                                .version("1.0.0")
                                .contact(
                                        new Contact()
                                                .name("DCOS Platform")
                                                .url(
                                                        "https://github.com/dcos-platform/cert-api-service")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        securitySchemeName,
                                        new SecurityScheme()
                                                .name(securitySchemeName)
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("basic")));
    }
}
