description = "Owns the booking domain: practitioners, treatments, appointments, waitlist. Publishes events to Kafka."

dependencies {
    implementation(libs.bundles.spring.web)
    implementation(libs.bundles.persistence)
    implementation(libs.bundles.observability)
    implementation(libs.spring.kafka)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.bundles.testcontainers)
}
