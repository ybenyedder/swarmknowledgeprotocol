plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.swarmknowledge.ospbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.swarmknowledge.ospbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        aidl = true
        // no compose / no XML layouts: the activity builds its UI in code,
        // so no resource pipeline is needed beyond the manifest
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
    implementation(project(":osp-lite"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
