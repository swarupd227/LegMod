package com.envestnet.atlas.cap.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.0 metadata + global security scheme for prj-service.
 *
 * <p>Springdoc generates the path/operation/component sections
 * automatically from the controller annotations and bean shapes; this
 * config supplies only the document-level info that doesn't come from
 * code.</p>
 *
 * <p>The {@code Bearer} security scheme is declared globally so the
 * generated spec marks every endpoint as requiring a JWT — matching
 * the gateway's enforcement. The OAuth dance itself is documented in
 * {@code docs/auth.md}; the spec just notes that bearer tokens go in
 * the {@code Authorization} header.</p>
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:cap-service}")
    private String appName;

    @Value("${app.version:0.1.0}")
    private String appVersion;

    @Bean
    public OpenAPI atlasOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Atlas Migrate · " + appName)
                        .version(appVersion)
                        .description("HTTP contract for the " + appName + " service. " +
                                "Auto-generated; see docs/api-contracts.md for the workflow.")
                        .contact(new Contact()
                                .name("Atlas Migrate engineering")
                                .email("dev@envestnet.local"))
                        .license(new License()
                                .name("Proprietary — Envestnet Atlas Migrate")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .schemaRequirement("bearer-jwt", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Gateway-validated OIDC bearer token. See docs/auth.md."));
    }
}
