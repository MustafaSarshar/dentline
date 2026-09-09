package no.dentline.booking

import com.fasterxml.jackson.databind.JsonNode
import no.dentline.booking.event.AppointmentEventType
import no.dentline.booking.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The core guarantee of the booking service: a slot can be booked exactly once, no matter how
 * many requests race for it. Requests really do run in parallel here: each one is sent from its
 * own thread, and a barrier releases all threads at the same instant so they hit the API together.
 *
 * What makes it hold is `PractitionerRepository.lockForUpdate` — `SELECT … FOR UPDATE` on the
 * practitioner row inside the booking transaction. The first transaction to get the lock inserts
 * its appointment; the others block on the lock, then run their overlap check *after* that
 * commit and see the slot taken.
 */
class ConcurrentBookingIT : IntegrationTest() {

    @Test
    fun `two simultaneous requests for the same slot give exactly one 201 and one 409`() {
        val slot = nextMonday().at("08:00")
        val ingrid = bookingRequest(startTime = slot, name = "Ingrid Bakken", email = "ingrid.bakken@gmail.com")
        val omar = bookingRequest(startTime = slot, name = "Omar Hussain", email = "omar.hussain@icloud.com", phone = "+47 476 21 903")

        val responses = fireSimultaneously(ingrid, omar)

        assertThat(responses.map { it.statusCode.value() }).containsExactlyInAnyOrder(201, 409)
        val rejected = responses.single { it.statusCode == HttpStatus.CONFLICT }
        assertThat(rejected.body!!["code"].asText()).isEqualTo("SLOT_TAKEN")

        assertThat(appointmentsStartingAt(slot)).isEqualTo(1)
        assertThat(events.ofType(AppointmentEventType.BOOKED)).hasSize(1)
    }

    @Test
    fun `ten patients racing for one slot still produce exactly one booking`() {
        val slot = nextMonday().at("12:15")
        val requests = (1..10).map { i ->
            bookingRequest(startTime = slot, name = "Patient $i", email = "patient$i@example.com", phone = "+47 900 00 0$i")
        }

        val responses = fireSimultaneously(*requests.toTypedArray())

        assertThat(responses.count { it.statusCode == HttpStatus.CREATED }).isEqualTo(1)
        assertThat(responses.count { it.statusCode == HttpStatus.CONFLICT }).isEqualTo(9)
        assertThat(appointmentsStartingAt(slot)).isEqualTo(1)
    }

    @Test
    fun `simultaneous bookings on different practitioners do not block each other`() {
        val slot = nextMonday().at("10:00")
        val withNordvik = bookingRequest(startTime = slot, practitionerId = NORDVIK)
        val withSaether = bookingRequest(startTime = slot, practitionerId = SAETHER, email = "omar.hussain@icloud.com")

        val responses = fireSimultaneously(withNordvik, withSaether)

        assertThat(responses.map { it.statusCode.value() }).containsExactly(201, 201)
        assertThat(appointmentsStartingAt(slot)).isEqualTo(2)
    }

    /** Sends every request from its own thread, releasing them all through one barrier. */
    private fun fireSimultaneously(vararg requests: Map<String, Any>): List<ResponseEntity<JsonNode>> {
        val startTogether = CyclicBarrier(requests.size)
        val pool = Executors.newFixedThreadPool(requests.size)
        try {
            val inFlight = requests.map { body ->
                pool.submit(
                    Callable {
                        startTogether.await(10, TimeUnit.SECONDS)
                        post("/api/appointments", body)
                    },
                )
            }
            return inFlight.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
