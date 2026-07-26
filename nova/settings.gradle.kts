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
    }
}

rootProject.name = "Nova"

include(":app")
include(":core:agent")
include(":core:speech")
include(":core:llm")
include(":feature:device")
include(":feature:accessibility")
include(":feature:localllm")
include(":feature:memory")
include(":feature:routines")
include(":feature:notifications")
include(":feature:vision")
