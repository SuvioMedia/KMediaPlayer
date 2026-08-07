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
        exclusiveContent {
            forRepository {
                maven("https://suviomedia.github.io/Nucleus/maven")
            }
            filter {
                includeGroup("dev.nucleusframework")
            }
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "kmediaplayer-public-maven-consumer"
