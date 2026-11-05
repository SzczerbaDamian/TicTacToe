plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.szczerba.handguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.szczerba.handguard"
        minSdk = 26
        // 34, not 35: Android 15 forces edge-to-edge layouts for apps targeting 35.
        targetSdk = 34
        versionCode = 2
        versionName = "2.1"
    }

    // A key kept in the repository, so every APK built by CI is signed the same
    // way and installs over the previous one without uninstalling (keeps data).
    // Fine for a personal, sideloaded app; never use it for a Play Store release.
    signingConfigs {
        create("shared") {
            storeFile = file("handguard.keystore")
            storePassword = "handguard"
            keyAlias = "handguard"
            keyPassword = "handguard"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
