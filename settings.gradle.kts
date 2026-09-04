pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "USB_Advance"

// Core modules
include(":core:storage-api")
include(":core:usb")
include(":core:partition")
include(":core:fs-native")
include(":core:root")

// Feature modules
include(":feature:device-list")
include(":feature:formatter")
include(":feature:diagnostic")

// Application entrypoint
include(":app")
