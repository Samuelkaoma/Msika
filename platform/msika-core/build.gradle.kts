// Plain Java. Depends on msika-ledger only. No Spring, JPA, Jackson or HTTP clients.
plugins {
    `java-library`
}

dependencies {
    api(project(":msika-ledger"))

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
