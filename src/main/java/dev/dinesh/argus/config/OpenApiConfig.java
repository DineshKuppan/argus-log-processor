package dev.dinesh.argus.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sets up Swagger UI and OpenAPI metadata for the Argus Log Processing Service. */
@Configuration
public class OpenApiConfig {
  @Bean
  public OpenAPI argusOpenAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Argus Log Processing Service")
                .description("REST APIs for parsing, filtering, aggregating, and exporting logs")
                .version("0.1.0"));
  }
}
