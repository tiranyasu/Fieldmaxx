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
include(":importer")
// Added by later tasks, once their directories exist:
include(":sharepoint")
include(":server")
include(":app")
