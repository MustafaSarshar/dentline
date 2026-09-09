package no.dentline.notification

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Publishes raw JSON events (the wire contract, not shared classes) to the real topics and
 * checks the notifications that come out the other end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationServiceIT {

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).withStartupTimeout(java.time.Duration.ofMinutes(3)).also { it.start() }
        private val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1")).withStartupTimeout(java.time.Duration.ofMinutes(3)).also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun infrastructure(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun cleanSlate() = jdbc.execute("truncate table notification")

    @Test
    fun `a BOOKED event becomes a booking-received email, and a redelivery is ignored`() {
        val appointmentId = UUID.randomUUID()
        val event = appointmentEvent(UUID.randomUUID(), "BOOKED", appointmentId, status = "REQUESTED")

        publish("appointment-events", appointmentId, event)
        val first = awaitNotifications("appointmentId=$appointmentId", count = 1).single()

        assertThat(first["type"].asText()).isEqualTo("BOOKING_RECEIVED")
        assertThat(first["channels"].map { it.asText() }).containsExactly("EMAIL")
        assertThat(first["recipient"]["email"].asText()).isEqualTo("ingrid.bakken@gmail.com")
        assertThat(first["subject"].asText()).isEqualTo("We have received your booking request")
        assertThat(first["body"].asText()).contains("Check-up with Dr. Astrid Nordvik on Mon 14 Sep at 08:00", "DL-4821")

        publish("appointment-events", appointmentId, event) // same eventId again
        publish("appointment-events", appointmentId, appointmentEvent(UUID.randomUUID(), "CONFIRMED", appointmentId, status = "CONFIRMED"))
        val all = awaitNotifications("appointmentId=$appointmentId", count = 2)

        assertThat(all.map { it["type"].asText() }).containsExactlyInAnyOrder("BOOKING_RECEIVED", "BOOKING_CONFIRMED")
    }

    @Test
    fun `a waitlist offer is texted and emailed with the hold deadline`() {
        val entryId = UUID.randomUUID()
        publish(
            "waitlist-events", entryId,
            mapOf(
                "eventId" to UUID.randomUUID(), "type" to "OFFER_SENT", "occurredAt" to "2026-09-08T09:20:00Z",
                "entry" to mapOf(
                    "id" to entryId, "status" to "OFFERED", "treatmentName" to "Cleaning", "position" to 1, "queueSize" to 3,
                    "patient" to mapOf("name" to "Vilde Sørensen", "phone" to "+47 924 61 038", "email" to "vilde@example.com"),
                ),
                "offer" to mapOf(
                    "practitionerName" to "Mari Lund", "startTime" to "2026-09-11T11:45:00Z", "endTime" to "2026-09-11T12:30:00Z",
                    "expiresAt" to "2026-09-08T09:35:00Z",
                ),
            ),
        )

        val offer = awaitNotifications("waitlistEntryId=$entryId", count = 1).single()

        assertThat(offer["type"].asText()).isEqualTo("WAITLIST_OFFER")
        assertThat(offer["channels"].map { it.asText() }).containsExactly("SMS", "EMAIL")
        assertThat(offer["body"].asText()).contains("Cleaning with Mari Lund on Fri 11 Sep at 13:45", "held for you until 11:35")
    }

    @Test
    fun `events with no patient-facing message produce nothing`() {
        val appointmentId = UUID.randomUUID()
        publish("appointment-events", appointmentId, appointmentEvent(UUID.randomUUID(), "COMPLETED", appointmentId, status = "COMPLETED"))
        publish("appointment-events", appointmentId, appointmentEvent(UUID.randomUUID(), "CANCELLED", appointmentId, status = "CANCELLED"))

        val only = awaitNotifications("appointmentId=$appointmentId", count = 1).single()

        assertThat(only["type"].asText()).isEqualTo("BOOKING_CANCELLED")
        assertThat(only["body"].asText()).contains("released to the waitlist")
    }

    @Test
    fun `notifications can be looked up by patient email`() {
        val id = UUID.randomUUID()
        publish("appointment-events", id, appointmentEvent(UUID.randomUUID(), "BOOKED", id, status = "CONFIRMED"))

        val byEmail = awaitNotifications("email=Ingrid.Bakken@gmail.com", count = 1).single()

        assertThat(byEmail["type"].asText()).isEqualTo("BOOKING_CONFIRMED")
    }

    private fun appointmentEvent(eventId: UUID, type: String, appointmentId: UUID, status: String): Map<String, Any?> = mapOf(
        "eventId" to eventId, "type" to type, "occurredAt" to "2026-09-08T09:20:00Z",
        "appointment" to mapOf(
            "id" to appointmentId, "reference" to "DL-4821", "status" to status,
            "practitionerId" to UUID.randomUUID(), "practitionerName" to "Dr. Astrid Nordvik",
            "treatmentTypeId" to UUID.randomUUID(), "treatmentName" to "Check-up", "durationMinutes" to 30,
            "startTime" to "2026-09-14T06:00:00Z", "endTime" to "2026-09-14T06:30:00Z",
            "patient" to mapOf("name" to "Ingrid Bakken", "phone" to "+47 913 44 208", "email" to "ingrid.bakken@gmail.com"),
        ),
        "cancelledBy" to if (type == "CANCELLED") "PATIENT" else null,
    )

    private fun publish(topic: String, key: UUID, payload: Map<String, Any?>) {
        val json = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload)
        kafkaTemplate.send(topic, key.toString(), json).get(10, TimeUnit.SECONDS)
    }

    private fun awaitNotifications(query: String, count: Int): List<JsonNode> {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted {
            assertThat(fetch(query)).hasSize(count)
        }
        return fetch(query)
    }

    private fun fetch(query: String): List<JsonNode> = rest.getForEntity("/api/notifications?$query", JsonNode::class.java).body!!.toList()
}
