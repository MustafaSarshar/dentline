description = "Consumes booking events, stores the notifications that would be sent, exposes them for the clinic view."

dependencies {
    implementation(libs.bundles.spring.web)
    implementation(libs.bundles.persistence)
    implementation(libs.bundles.observability)
    implementation(libs.spring.kafka)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.bundles.testcontainers)
}
