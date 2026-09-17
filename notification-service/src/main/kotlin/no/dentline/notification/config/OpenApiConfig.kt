package no.dentline.notification.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Serves the live contract at `/swagger-ui.html`. */
@Configuration
class OpenApiConfig {
    @Bean
    fun notificationOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Dentline notification API")
            .version("0.1.0")
            .description(
                """
                The messages that would have been sent to patients, built from the events
                booking-service publishes to Kafka. Delivery is simulated; each row is the record
                of one message. The clinic dashboard reads them to show an appointment's history.
                """.trimIndent(),
            )
            .license(License().name("MIT").url("https://github.com/MustafaSarshar/dentline/blob/main/LICENSE")),
    )
}
