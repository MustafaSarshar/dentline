package no.dentline.booking.kafka

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

object Topics {
    const val APPOINTMENT_EVENTS = "appointment-events"
    const val WAITLIST_EVENTS = "waitlist-events"
    const val RECALL_EVENTS = "recall-events"
}

/** booking-service owns the topics: it is the only producer. Keys are aggregate ids, so per-aggregate order holds within a partition. */
@Configuration
class KafkaTopics {
    @Bean fun appointmentEvents(): NewTopic = topic(Topics.APPOINTMENT_EVENTS)
    @Bean fun waitlistEvents(): NewTopic = topic(Topics.WAITLIST_EVENTS)
    @Bean fun recallEvents(): NewTopic = topic(Topics.RECALL_EVENTS)

    private fun topic(name: String) = TopicBuilder.name(name).partitions(3).replicas(1).build()
}
