plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.mineblocos.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mineblocos.app"
        minSdk = 24
        targetSdk = 34
        versionCode = run
        versionName = "1.$run"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("mineblocos.keystore")
            storePassword = "mineblocos"
            keyAlias = "mineblocos"
            keyPassword = "mineblocos"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
