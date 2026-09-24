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

rootProject.name = "swarmknowledge-android"

include(":osp-lite", ":ospbridge")

// The Linux/JVM node lives in ../linux/ but shares this build so osp-lite
// stays the single protocol core that every platform compiles from.
include(":ospnode")
project(":ospnode").projectDir = file("../linux/ospnode")
