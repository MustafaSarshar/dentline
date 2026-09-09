package no.dentline.notification.service

import no.dentline.notification.config.NotificationProperties
import no.dentline.notification.domain.Channel
import no.dentline.notification.domain.NotificationType
import no.dentline.notification.events.AppointmentEventMessage
import no.dentline.notification.events.PatientPayload
import no.dentline.notification.events.RecallEventMessage
import no.dentline.notification.events.WaitlistEventMessage
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** What a notification would say. Copy is in the clinic's voice, matching the UI. */
data class Draft(
    val type: NotificationType,
    val channels: List<Channel>,
    val recipient: PatientPayload,
    val subject: String,
    val body: String,
    val appointmentId: UUID? = null,
    val waitlistEntryId: UUID? = null,
)

@Component
class NotificationComposer(properties: NotificationProperties) {
    private val zone = properties.zoneId
    private val dayAndTime = DateTimeFormatter.ofPattern("EEE d MMM 'at' HH:mm", Locale.ENGLISH)
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayOnly = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** Returns null for events that produce no patient-facing message (COMPLETED, NO_SHOW). */
    fun forAppointment(event: AppointmentEventMessage): Draft? {
        val a = event.appointment
        val first = a.patient.name.substringBefore(' ')
        val whenAndWho = "${a.treatmentName} with ${a.practitionerName} on ${dayAndTime.format(a.startTime.at(zone))}"
        fun draft(type: NotificationType, channels: List<Channel>, subject: String, body: String) =
            Draft(type, channels, a.patient, subject, body, appointmentId = a.id)

        return when (event.type) {
            "BOOKED" -> if (a.status == "CONFIRMED") {
                draft(NotificationType.BOOKING_CONFIRMED, EMAIL, "Your appointment is confirmed", "Hi $first, you are booked in: $whenAndWho. Reference ${a.reference}. See you soon.")
            } else {
                draft(NotificationType.BOOKING_RECEIVED, EMAIL, "We have received your booking request", "Hi $first, we have received your request for $whenAndWho. Reference ${a.reference}. We will confirm it shortly.")
            }
            "CONFIRMED" -> draft(NotificationType.BOOKING_CONFIRMED, EMAIL, "Your appointment is confirmed", "Hi $first, your appointment is confirmed: $whenAndWho. Reference ${a.reference}.")
            "RESCHEDULED" -> draft(NotificationType.BOOKING_RESCHEDULED, EMAIL, "Your appointment has moved", "Hi $first, your appointment is now $whenAndWho. Reference ${a.reference}.")
            "CANCELLED" -> draft(NotificationType.BOOKING_CANCELLED, EMAIL, "Your appointment is cancelled", "Hi $first, your $whenAndWho is cancelled and the slot has been released to the waitlist. You can book a new time whenever suits you.")
            "REMINDER_DUE" -> draft(NotificationType.REMINDER, SMS_AND_EMAIL, "Reminder: your appointment tomorrow", "Hi $first, a reminder of your $whenAndWho. Reply to this message if you need to reschedule.")
            else -> null
        }
    }

    fun forWaitlist(event: WaitlistEventMessage): Draft? {
        val e = event.entry
        val first = e.patient.name.substringBefore(' ')
        return when (event.type) {
            "OFFER_SENT" -> {
                val offer = event.offer ?: return null
                Draft(
                    NotificationType.WAITLIST_OFFER, SMS_AND_EMAIL, e.patient,
                    "A slot just opened",
                    "Hi $first, ${e.treatmentName} with ${offer.practitionerName} on ${dayAndTime.format(offer.startTime.at(zone))} is held for you until ${timeOnly.format(offer.expiresAt.at(zone))}. Accept it before then or it passes to the next person.",
                    waitlistEntryId = e.id,
                )
            }
            "OFFER_EXPIRED" -> Draft(
                NotificationType.WAITLIST_OFFER_EXPIRED, EMAIL, e.patient,
                "Your slot offer has expired",
                "Hi $first, the slot we offered you has passed to the next person in line. You keep your place in the ${e.treatmentName} queue.",
                waitlistEntryId = e.id,
            )
            else -> null
        }
    }

    fun forRecall(event: RecallEventMessage): Draft? {
        if (event.type != "RECALL_DUE") return null
        val first = event.patient.name.substringBefore(' ')
        return Draft(
            NotificationType.RECALL, EMAIL, event.patient,
            "Time for your six-month check-up",
            "Hi $first, your last check-up with ${event.practitionerName} was on ${dayOnly.format(event.lastVisitDate)} and your next one is due by ${dayOnly.format(event.dueDate)}. Book a time that suits you.",
        )
    }

    private fun Instant.at(zone: ZoneId) = atZone(zone)

    private companion object {
        val EMAIL = listOf(Channel.EMAIL)
        val SMS_AND_EMAIL = listOf(Channel.SMS, Channel.EMAIL)
    }
}
