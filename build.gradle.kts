// Root build: settings shared by every module. Each module's own build.gradle.kts adds the rest.
// The Spring Boot plugin is declared here (version pinned exactly) and applied only in the apps.
plugins {
    id("org.springframework.boot") version "4.2.0" apply false
}

subprojects {
    apply(plugin = "java")

    group = "zm.msika"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
