pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MG4Control"
include(":app")

// Shared vehicle layer, vendored as a git submodule at ./MG4Hardware and consumed as a
// Gradle subproject. One implementation of MG4Hardware/VehicleWriteGate/FirmwareInfo/models.
include(":mg4hardware")
project(":mg4hardware").projectDir = file("MG4Hardware/lib")
