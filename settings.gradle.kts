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
        maven("https://jitpack.io")
    }
}

rootProject.name = "LarzOS"
include(":app")

// Termux's terminal widgets — the proven Android terminal stack. Vendored as a
// git submodule at termux-terminal/ (github.com/termux/termux-app).
include(":terminal-view")
include(":terminal-emulator")
project(":terminal-view").projectDir = file("termux-terminal/terminal-view")
project(":terminal-emulator").projectDir = file("termux-terminal/terminal-emulator")
