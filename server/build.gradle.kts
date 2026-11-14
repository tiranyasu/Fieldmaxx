plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor) // buildFatJar: the one runnable jar the Docker image ships
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.fieldmaxx.server.MainKt")
}

ktor {
    fatJar {
        // A fixed name, so server/Dockerfile copies exactly this file.
        archiveFileName.set("fieldmaxx-server-all.jar")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sharepoint"))
    implementation(project(":importer"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.bouncycastle.prov)
    implementation(libs.ktor.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.fastexcel)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(libs.ktor.server.test.host)
    testImplementation("org.apache.poi:poi-ooxml:5.4.1")
    testImplementation(libs.ktor.client.websockets)
    testImplementation(testFixtures(project(":importer")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    // LiveDeliveryTest runs only when FMX_LIVE_TEST_LINK is set. Gradle doesn't track environment variables, so a live
    // run must never be answered from up-to-date or build-cache results.
    if (providers.environmentVariable("FMX_LIVE_TEST_LINK").isPresent) {
        doNotTrackState("live OneDrive test")
    }
}
