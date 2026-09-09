package no.dentline.booking.support

import no.dentline.booking.event.AppointmentEvent
import no.dentline.booking.event.AppointmentEventType
import no.dentline.booking.event.RecallEvent
import no.dentline.booking.event.WaitlistEvent
import no.dentline.booking.event.WaitlistEventType
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/** Test-only listener that captures the domain events the services publish (before the Kafka relay). */
@Component
class RecordedEvents {
    private val recorded = CopyOnWriteArrayList<AppointmentEvent>()
    private val recordedWaitlist = CopyOnWriteArrayList<WaitlistEvent>()
    private val recordedRecalls = CopyOnWriteArrayList<RecallEvent>()

    @EventListener
    fun on(event: AppointmentEvent) {
        recorded += event
    }

    @EventListener
    fun on(event: WaitlistEvent) {
        recordedWaitlist += event
    }

    @EventListener
    fun on(event: RecallEvent) {
        recordedRecalls += event
    }

    val all: List<AppointmentEvent> get() = recorded.toList()
    val recalls: List<RecallEvent> get() = recordedRecalls.toList()

    fun ofType(type: AppointmentEventType): List<AppointmentEvent> = recorded.filter { it.type == type }

    fun waitlist(type: WaitlistEventType): List<WaitlistEvent> = recordedWaitlist.filter { it.type == type }

    fun clear() {
        recorded.clear()
        recordedWaitlist.clear()
        recordedRecalls.clear()
    }
}
