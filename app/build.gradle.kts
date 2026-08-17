plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.terra.planet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.terra.planet"
        minSdk = 24
        targetSdk = 34
        versionCode = 126
        versionName = "0.51.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        // Nécessaire pour lire VERSION_NAME dans le HUD : sous AGP 8,
        // BuildConfig n'est plus généré par défaut.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sim"))
}
