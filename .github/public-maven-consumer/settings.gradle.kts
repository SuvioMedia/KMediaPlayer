pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val publicRepositoryOverride = providers.gradleProperty("publicRepositoryUrl").orNull
        if (publicRepositoryOverride != null) {
            maven(publicRepositoryOverride)
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "kmediaplayer-public-maven-consumer"
