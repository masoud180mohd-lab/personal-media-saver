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

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "PersonalMediaSaver"

// Application module
include(":app")

// Core modules
include(":core:common")
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:database")
include(":core:network")
include(":core:download")
include(":core:storage")
include(":core:ui")

// Platform adapters
include(":platform:youtube")
include(":platform:tiktok")
include(":platform:whatsapp")
include(":platform:instagram")

// Feature modules
include(":feature:home")
include(":feature:download")
include(":feature:history")
include(":feature:status")
include(":feature:settings")
