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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // No jitpack: libsu (`com.github.topjohnwu.libsu`) was the only dependency that needed it,
        // and Odin replaced it. Jitpack builds arbitrary GitHub tags on demand, so leaving it
        // declared widens the supply chain for nothing. Re-add it only with a real dependency.
    }
}

rootProject.name = "Loki"
include(":app")

// Local cross-repo development against the Odin root-shell library. Set `odinDir` to a local Odin
// checkout (via -PodinDir=... or a Gradle properties file — local.properties is NOT consulted) to
// build against its source without publishing; unset to use the pinned published
// `com.trinadhthatakula:odin`. Odin's Gradle project is `:odin` and it publishes the `odin`
// artifact, so the mapping is explicit. Mirrors Thor's settings.gradle.kts.
val odinDir = providers.gradleProperty("odinDir").orNull
if (odinDir != null) {
    includeBuild(odinDir) {
        dependencySubstitution {
            substitute(module("com.trinadhthatakula:odin"))
                .using(project(":odin"))
        }
    }
}
 