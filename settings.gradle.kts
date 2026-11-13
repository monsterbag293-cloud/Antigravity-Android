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

rootProject.name = "agentic-workspace"

// Isolated diagnostic module (Phase P3.6.3)
include(":proot-diagnostic")

// Application entry
include(":app")

// Core domain & platform modules
include(":core:model")
include(":core:designsystem")
include(":core:network")
include(":core:data")

// Agent runtime & decoupled interfaces (ADR-01, ADR-02)
include(":agent-runtime")
include(":model-provider")
include(":tools:spi")
include(":tools:local")

// Feature modules
include(":feature:conversations")
include(":feature:projects")
include(":feature:files")
include(":feature:agentmanager")
include(":feature:settings")
include(":feature:terminal")
include(":feature:browser")
