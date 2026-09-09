package no.dentline.booking.support

import com.fasterxml.jackson.databind.JsonNode
import no.dentline.booking.service.ClinicTime
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Boots the real application against a throw-away Postgres (Testcontainers). Flyway runs the
 * migrations, so reference data (practitioners, treatments, working hours) is always present.
 * The container is started once per JVM and shared by every test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationTest {

    companion object {
        private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).withStartupTimeout(java.time.Duration.ofMinutes(3)).also { it.start() }
        private val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1")).withStartupTimeout(java.time.Duration.ofMinutes(3)).also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun infrastructure(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }

        // Fixed ids from V2__reference_data.sql
        val NORDVIK: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001") // Dentist, Mon–Fri 08:00–11:30, 12:15–15:30
        val SAETHER: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002") // Dentist, Mon–Thu 10:00–13:00, 13:45–18:00
        val LUND: UUID = UUID.fromString("20000000-0000-0000-0000-000000000003")    // Hygienist, Mon/Tue/Thu/Fri 08:00–11:30, 12:15–15:30
        val CHECK_UP: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")   // 30 min
        val CLEANING: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")   // 45 min
        val FILLING: UUID = UUID.fromString("10000000-0000-0000-0000-000000000003")    // 60 min
        val ROOT_CANAL: UUID = UUID.fromString("10000000-0000-0000-0000-000000000004") // 90 min
    }

    @Autowired protected lateinit var rest: TestRestTemplate
    @Autowired protected lateinit var jdbc: JdbcTemplate
    @Autowired protected lateinit var clinicTime: ClinicTime
    @Autowired protected lateinit var events: RecordedEvents

    @BeforeEach
    fun cleanSlate() {
        jdbc.execute("truncate table appointment, waitlist_entry, slot_offer, recall_notice cascade")
        events.clear()
    }

    // --- time helpers (clinic-local) ---

    protected fun nextMonday(): LocalDate = clinicTime.today().with(TemporalAdjusters.next(DayOfWeek.MONDAY))

    protected fun LocalDate.at(time: String): Instant = clinicTime.toInstant(atTime(LocalTime.parse(time)))

    /** Clinic-local wall-clock of an ISO timestamp in a response, e.g. "08:00". */
    protected fun JsonNode.localTime(): String =
        OffsetDateTime.parse(asText()).atZoneSameInstant(clinicTime.zone).toLocalTime().toString()

    // --- http helpers ---

    protected fun get(path: String): ResponseEntity<JsonNode> = rest.getForEntity(path, JsonNode::class.java)

    protected fun post(path: String, body: Any? = null): ResponseEntity<JsonNode> = rest.postForEntity(path, body, JsonNode::class.java)

    protected fun bookingRequest(
        startTime: Instant,
        practitionerId: UUID = NORDVIK,
        treatmentTypeId: UUID = CHECK_UP,
        name: String = "Ingrid Bakken",
        phone: String = "+47 913 44 208",
        email: String = "ingrid.bakken@gmail.com",
    ): Map<String, Any> = mapOf(
        "treatmentTypeId" to treatmentTypeId,
        "practitionerId" to practitionerId,
        "startTime" to startTime.toString(),
        "patient" to mapOf("name" to name, "phone" to phone, "email" to email),
    )

    protected fun book(startTime: Instant, practitionerId: UUID = NORDVIK, treatmentTypeId: UUID = CHECK_UP): JsonNode {
        val response = post("/api/appointments", bookingRequest(startTime, practitionerId, treatmentTypeId))
        check(response.statusCode.value() == 201) { "Expected 201 but got ${response.statusCode}: ${response.body}" }
        return response.body!!
    }

    protected fun slotsOn(date: LocalDate, practitionerId: UUID? = NORDVIK, treatmentTypeId: UUID = CHECK_UP): List<String> {
        val query = "treatmentTypeId=$treatmentTypeId&from=$date&to=$date" + (practitionerId?.let { "&practitionerId=$it" } ?: "")
        val day = get("/api/availability?$query").body!!["days"][0]
        return day["slots"].map { it["startTime"].localTime() }
    }

    protected fun joinWaitlist(
        treatmentTypeId: UUID = CHECK_UP,
        windows: List<String> = listOf("WEEKDAY_MORNINGS"),
        practitionerId: UUID? = null,
        name: String = "Vilde Sørensen",
        email: String = "vilde.sorensen@gmail.com",
        phone: String = "+47 924 61 038",
    ): JsonNode {
        val body = mutableMapOf<String, Any>(
            "treatmentTypeId" to treatmentTypeId,
            "preferredWindows" to windows,
            "patient" to mapOf("name" to name, "phone" to phone, "email" to email),
        )
        practitionerId?.let { body["practitionerId"] = it }
        val response = post("/api/waitlist", body)
        check(response.statusCode.value() == 201) { "Expected 201 but got ${response.statusCode}: ${response.body}" }
        return response.body!!
    }

    protected fun waitlistEntry(id: String): JsonNode = get("/api/waitlist/$id").body!!

    protected fun delete(path: String): ResponseEntity<JsonNode> = rest.exchange(path, org.springframework.http.HttpMethod.DELETE, null, JsonNode::class.java)

    // --- db helpers ---

    /** Live (calendar-blocking) appointments starting at [start]; cancelled ones are not counted. */
    protected fun appointmentsStartingAt(start: Instant): Int = jdbc.queryForObject(
        "select count(*) from appointment where start_time = ? and status in ('REQUESTED', 'CONFIRMED')",
        Int::class.java,
        Timestamp.from(start),
    )!!
}
