package no.dentline.booking.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Serves the live contract at `/swagger-ui.html`, alongside the written one in `docs/02-api-contract.md`. */
@Configuration
class OpenApiConfig {
    @Bean
    fun bookingOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Dentline booking API")
            .version("0.1.0")
            .description(
                """
                Appointments, computed availability and the waitlist for Dentline Majorstuen.

                Times are ISO-8601 with the clinic's offset. Errors are RFC 7807 problem+json:
                400 carries a field list, 409 carries a machine-readable `code`
                (SLOT_TAKEN, OUTSIDE_WORKING_HOURS, IN_THE_PAST, ILLEGAL_TRANSITION,
                OFFER_NOT_ACTIVE, OFFER_ALREADY_PENDING, NO_MATCHING_SLOT).

                There is no authentication: it is out of scope for this project.
                """.trimIndent(),
            )
            .license(License().name("MIT").url("https://github.com/MustafaSarshar/dentline/blob/main/LICENSE")),
    )
}
