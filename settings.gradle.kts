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

        // MİCROSOFT AZURE ANBARI (Bunu əlavə et!)
        maven { url = uri("https://csspeechstorage.blob.core.windows.net/maven/") }
    }
}

rootProject.name = "PayroRobot"
include(":app")
