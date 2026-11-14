pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.PREFER_PROJECT); repositories { google(); mavenCentral() } }
rootProject.name = "VLtelecom3"
include(":app")
