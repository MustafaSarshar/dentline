package no.dentline.booking.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** A single injectable clock so services and scheduled jobs are testable with a fixed time. */
@Configuration
class ClockConfig {
    @Bean
    fun clock(properties: DentlineProperties): Clock = Clock.system(properties.zoneId)
}
