plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.larzos.os"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.larzos.os"
        minSdk = 26
        targetSdk = 35
        versionCode = (rootProject.extra["larzVersionCode"] as Int)
        versionName = (rootProject.extra["larzVersionName"] as String)

        // Where the LarzOS rootfs is fetched from on first run. The
        // release job in larz-scripter/larzos-linux publishes this asset.
        buildConfigField(
            "String",
            "ROOTFS_BASE_URL",
            "\"https://github.com/larz-scripter/larzos-linux/releases/download/v0.1.9\""
        )
        buildConfigField("String", "ROOTFS_ARM64", "\"larzos-rootfs-arm64-0.1.9.tar.gz\"")

        ndk {
            // proot / loader ship as jniLibs; ship the arches we have bootstraps for.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Executables (proot, loader) are shipped as lib*.so so Android unpacks them
    // into nativeLibraryDir, the one place an unprivileged app may exec from.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Termux terminal widgets (vendored submodule).
    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))
}
