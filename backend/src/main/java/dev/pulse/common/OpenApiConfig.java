package dev.pulse.common;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/** Swagger UI at /swagger-ui. The "Authorize" button takes an access token. */
@Configuration
@OpenAPIDefinition(info = @Info(title = "Pulse API", version = "v1",
		description = "Uptime monitoring: monitors, incidents, alerts and status pages."))
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
