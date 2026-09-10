// LarzOS phone app — root build file. Toolchain matched to the vendored Termux
// terminal modules (submodule termux-terminal @ 3b66f879 → Gradle 9.2 / AGP 8.13).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}

val larzVersionName: String by extra("0.1.0")
val larzVersionCode: Int by extra(1)

// The vendored Termux library modules declare a Maven publication for JitPack
// (`from components.default`). We only consume them as project dependencies,
// so ensure a "release" variant exists rather than letting publishing fail.
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            @Suppress("UnstableApiUsage")
            publishing { singleVariant("release") }
        }
    }
}
