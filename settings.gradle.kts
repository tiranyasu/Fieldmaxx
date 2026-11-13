pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fieldmaxx"

include(":core")
// Added by later tasks, once their directories exist:
// include(":sharepoint")
// include(":importer")
// include(":server")
// include(":app")
