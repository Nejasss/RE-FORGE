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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "RE-FORGE"
include(
    ":app",
    ":core",
    ":engine-registry",
    ":storage",
    ":filemanager",
    ":apk-inspector",
    ":dex-analysis",
    ":resource-inspector",
    ":binary-analysis",
    ":hex-viewer",
    ":patch-workspace",
    ":rebuild-engine",
    ":signing-engine",
    ":terminal-interface"
)
