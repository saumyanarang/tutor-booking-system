package com.saumyanarang.tutorbooking.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI metadata. The bearerAuth scheme adds the "Authorize" button: sign up or log in, paste the
 * returned token there, and every protected endpoint can be called from the browser.
 */
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Tutor Booking API",
        version = "1.0",
        description = "Tutors publish time slots, students book a specific one - without ever double-booking."))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {
}
