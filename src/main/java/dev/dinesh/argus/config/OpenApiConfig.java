package dev.dinesh.argus.config;

import dev.dinesh.argus.tenant.ApiKeyTenantResolver;
import dev.dinesh.argus.tenant.CurrentTenant;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sets up Swagger UI and OpenAPI metadata for the Argus Log Processing Service. */
@Configuration
public class OpenApiConfig {

  private static final String API_KEY_SCHEME = "apiKey";

  static {
    // The tenant comes from the API key, not from a parameter the caller sends.
    SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentTenant.class);
  }

  @Bean
  public OpenAPI argusOpenAPI() {
    return new OpenAPI()
        .components(
            new Components()
                .addSecuritySchemes(
                    API_KEY_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyTenantResolver.API_KEY_HEADER)
                        .description("Identifies the tenant; every /api request needs one")))
        .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME))
        .info(
            new Info()
                .title("Argus Log Processing Service")
                .description("REST APIs for parsing, filtering, aggregating, and exporting logs")
                .version("0.1.0"));
  }
}
