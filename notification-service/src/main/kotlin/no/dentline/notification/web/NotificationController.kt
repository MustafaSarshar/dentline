package no.dentline.notification.web

import no.dentline.notification.config.NotificationProperties
import no.dentline.notification.domain.Channel
import no.dentline.notification.domain.Notification
import no.dentline.notification.domain.NotificationType
import no.dentline.notification.service.NotificationService
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.OffsetDateTime
import java.util.UUID

data class RecipientResponse(val name: String, val phone: String, val email: String)

data class NotificationResponse(
    val id: UUID,
    val type: NotificationType,
    val channels: List<Channel>,
    val recipient: RecipientResponse,
    val subject: String,
    val body: String,
    val appointmentId: UUID?,
    val waitlistEntryId: UUID?,
    val createdAt: OffsetDateTime,
)

/** The clinic view reads sent notifications here, e.g. to show them in the appointment drawer's history. */
@RestController
@RequestMapping("/api/notifications")
class NotificationController(private val service: NotificationService, private val properties: NotificationProperties) {

    @GetMapping
    fun list(
        @RequestParam(required = false) appointmentId: UUID?,
        @RequestParam(required = false) waitlistEntryId: UUID?,
        @RequestParam(required = false) email: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<NotificationResponse> = service.find(appointmentId, waitlistEntryId, email, limit).map(::toResponse)

    private fun toResponse(n: Notification) = NotificationResponse(
        id = n.id,
        type = n.type,
        channels = n.channels,
        recipient = RecipientResponse(n.recipientName, n.recipientPhone, n.recipientEmail),
        subject = n.subject,
        body = n.body,
        appointmentId = n.appointmentId,
        waitlistEntryId = n.waitlistEntryId,
        createdAt = n.createdAt.atZone(properties.zoneId).toOffsetDateTime(),
    )
}

@Configuration
class WebConfig(private val properties: NotificationProperties) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*properties.cors.allowedOrigins.toTypedArray())
            .allowedMethods("GET", "OPTIONS")
            .maxAge(3600)
    }
}
