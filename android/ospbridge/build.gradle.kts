plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.swarmknowledge.ospbridge"
    compileSdk = 36

    defaultConfig {
        // Play listing is under the Tree4Five brand, same family as the
        // LLMProvider engine (com.tree4five.gguf) this app binds to.
        applicationId = "com.tree4five.osp"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.6.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // same convention as harnessDroid / LLMProvider: keystore lives one
            // level up, passwords come from gradle properties (never committed)
            storeFile = file("../release.keystore")
            storePassword = project.findProperty("MYAPP_RELEASE_STORE_PASSWORD") as String? ?: "password123"
            keyAlias = project.findProperty("MYAPP_RELEASE_KEY_ALIAS") as String? ?: "release"
            keyPassword = project.findProperty("MYAPP_RELEASE_KEY_PASSWORD") as String? ?: "password123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        aidl = true
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
}
