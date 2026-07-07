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

rootProject.name = "SetlistCompanion"

include(":core")

// The :app module needs the Android SDK to configure. Skip it when no SDK is
// available so the pure-JVM :core module and its tests still build (e.g. CI).
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    File(settingsDir, "local.properties").exists()
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found - only including the :core JVM module.")
}
