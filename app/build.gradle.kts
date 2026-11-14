import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

// Release signing reads the git-ignored keystore.properties (storeFile, storePassword, keyAlias, keyPassword) when it
// exists; without it, debug builds and CI work as usual and a release build comes out unsigned.
val keystoreProperties: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

// The server address the login screen starts with: -Pfieldmaxx.serverUrl=https://… (or set it in ~/.gradle/gradle.properties).
val serverUrl: String = providers.gradleProperty("fieldmaxx.serverUrl").getOrElse("")

android {
    namespace = "com.fieldmaxx.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fieldmaxx.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SERVER_URL", "\"" + serverUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }

    signingConfigs {
        keystoreProperties?.let { props ->
            fun prop(name: String): String = props.getProperty(name) ?: error("keystore.properties has no $name")
            create("release") {
                storeFile = rootProject.file(prop("storeFile"))
                storePassword = prop("storePassword")
                keyAlias = prop("keyAlias")
                keyPassword = prop("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += "en" // English only: libraries' translations stay out, so no screen mixes languages
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    // Exported schemas are the baseline for future migrations; commit them with every version bump.
    schemaDirectory("$projectDir/schemas")
}

androidComponents {
    // Unit tests run against the debug build only: it alone declares the activity that Compose tests launch.
    beforeVariants(selector().withBuildType("release")) {
        (it as HasHostTestsBuilder).hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sharepoint")) // also brings OkHttp (an `api` dependency there)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.play.services.location)
    implementation(libs.maplibre.android)
    implementation(libs.suncalc)
    implementation(libs.coil)
    implementation(libs.coil.compose) // the point sheet's reference images
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface) // a gallery photo's own GPS and time
    debugImplementation(libs.androidx.compose.ui.test.manifest) // the empty activity createComposeRule() launches

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.lifecycle.runtime.testing)
}
