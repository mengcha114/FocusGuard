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
        // compose-markdown（AI 对话 Markdown 渲染）发布在 JitPack
        maven("https://jitpack.io")
    }
}

rootProject.name = "FocusGuard"
include(":app")
