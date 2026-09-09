import org.jetbrains.kotlin.allopen.gradle.AllOpenExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "no.dentline"
    version = "0.1.0"
}

// The version-catalog accessor is only generated for the root script, so capture it here.
val kotlinVersion = libs.versions.kotlin.get()

// Shared configuration for the two Spring Boot services. Bytecode targets Java 21 so the
// Docker images can run a plain Temurin 21 JRE while local builds may use any newer JDK.
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")
    apply(plugin = "org.springframework.boot")

    // Keep the Boot BOM's Kotlin artifacts in step with the Kotlin plugin version.
    extra["kotlin.version"] = kotlinVersion

    dependencies {
        "implementation"(platform(SpringBootPlugin.BOM_COORDINATES))
        "testImplementation"(platform(SpringBootPlugin.BOM_COORDINATES))
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    // Hibernate needs non-final entity classes for lazy-loading proxies.
    extensions.configure<AllOpenExtension> {
        annotation("jakarta.persistence.Entity")
        annotation("jakarta.persistence.MappedSuperclass")
        annotation("jakarta.persistence.Embeddable")
    }

    // Only the executable Boot jar is needed; the plain jar would just confuse the Dockerfile glob.
    tasks.named<Jar>("jar") { enabled = false }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Both modules start Testcontainers; probing Docker Desktop from two JVMs at the same instant has proven flaky on Windows.
        if (project.name == "notification-service") mustRunAfter(":booking-service:test")
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }
}
