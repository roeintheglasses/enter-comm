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
        maven { url = uri("https://jitpack.io") }
        // Add Maven repository for WebRTC
        maven { url = uri("https://repo1.maven.org/maven2/") }
    }
}

rootProject.name = "EnterComm"
include(":app")