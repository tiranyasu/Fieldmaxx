plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    // Android consumes :sharepoint, so emit Java 17 bytecode while building with the JDK 21 toolchain.
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":core"))
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // SharePointLiveTest runs only when FMX_LIVE_TEST_LINK is set. Gradle doesn't track environment
    // variables, so a live run must never be answered from up-to-date or build-cache results.
    if (providers.environmentVariable("FMX_LIVE_TEST_LINK").isPresent) {
        doNotTrackState("live OneDrive test")
    }
}
