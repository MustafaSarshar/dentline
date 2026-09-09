package no.dentline.notification.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.ZoneId

@ConfigurationProperties(prefix = "dentline")
data class NotificationProperties(
    val timezone: String = "Europe/Oslo",
    val cors: Cors = Cors(),
) {
    val zoneId: ZoneId get() = ZoneId.of(timezone)

    data class Cors(val allowedOrigins: List<String> = listOf("http://localhost:5173"))
}
