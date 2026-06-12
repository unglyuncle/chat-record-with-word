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
        // sherpa-onnx prebuilt artifacts are also mirrored on Maven Central under com.k2-fsa.
        // jitpack kept as a fallback for community mirrors.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MeetingTranscriber"
include(":app")
