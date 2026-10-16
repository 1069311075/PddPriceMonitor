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
    }
}

rootProject.name = "PddPriceMonitor"
include(":app")
// 离线回归：纯 JVM 跑真 parser（见 replay/sync_from_app.sh）
include(":replay")
