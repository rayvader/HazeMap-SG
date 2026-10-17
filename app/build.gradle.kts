plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// CI run number drives the version so every build installs over the last one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")
val hasReleaseKey = !signingStoreFile.isNullOrBlank() && file(signingStoreFile).exists()

android {
    namespace = "io.github.rayvader.hazemap"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.rayvader.hazemap"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "hazemap"
                keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a CI key the release APK is still signed (debug key) so it installs.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    buildFeatures {
        viewBinding = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // Free open-street-map tiles, no API key.
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    // History line chart.
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
