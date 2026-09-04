package com.pigfox.ledger.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Aligns the springdoc-generated description with the committed contract.
 *
 * <p>The title, version, server, and {@code bearerAuth} scheme here mirror
 * {@code src/main/resources/openapi/asset-api.yaml} exactly, and
 * {@code OpenApiContractTest} fails the build if the two drift apart. Without this bean
 * springdoc would invent its own title and omit the security scheme, and the "API-first"
 * claim would quietly stop being true.
 */
@Configuration
public class OpenApiConfig {

    /** Name of the security scheme, referenced by the contract and by controllers. */
    public static final String BEARER_SCHEME = "bearerAuth";
    /** Title, kept identical to the contract's {@code info.title}. */
    public static final String TITLE = "Ledger Node Asset API";
    /** Version, kept identical to the contract's {@code info.version}. */
    public static final String VERSION = "1.0.0";

    /** @return the base OpenAPI description springdoc merges the controllers into */
    @Bean
    public OpenAPI assetApi() {
        return new OpenAPI()
                .info(new Info()
                        .title(TITLE)
                        .version(VERSION)
                        .description("API-first contract for the ledger-node service.")
                        .license(new License()
                                .name("Apache-2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(new Server()
                        .url("http://localhost:8087")
                        .description("Local application port")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("HS256 JWT issued by `POST /api/v1/auth/token`.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
