plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
}

android {
    namespace = "com.example.messenger_app"
    compileSdk = 34

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    defaultConfig {
        val appId = System.getenv("APP_ID") ?: "com.family.messenger.local"
        val appName = System.getenv("APP_NAME") ?: "Family Chat"
        val aesKey = System.getenv("AES_SECRET") ?: "DEFAULT_DEV_KEY_0000000000000000"
        val turnConfig = project.findProperty("turnConfig") as? String ?: ""

        applicationId = appId
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables.useSupportLibrary = true

        // resValue("string", "app_name", appName) // Removed: Injected via strings.xml
        buildConfigField("String", "APP_SIGNATURE_SALT", "\"${System.getenv("AES_SECRET") ?: "dev_salt_change_me"}\"")
        buildConfigField("String", "TURN_CONFIG_JSON", "\"$turnConfig\"")
        
        // Optimize APK size
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Enable minification for debug to reduce size
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Под Kotlin 1.9.24
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    // ==================== AndroidX / Compose ====================
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime-livedata")

    // Compose Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ==================== Firebase ====================
    implementation(platform("com.google.firebase:firebase-bom:34.2.0"))

    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")      // ✅ ДОБАВЛЕНО для чатов
    implementation("com.google.firebase:firebase-messaging")

    // ==================== Lifecycle Service ====================
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // ==================== Coroutines ====================
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ==================== Image Loading ====================
    implementation("io.coil-kt:coil-compose:2.6.0")

    // ==================== WebRTC ====================
    implementation("io.github.webrtc-sdk:android:137.7151.04")

    // ==================== Google Auth ====================
    implementation("com.google.auth:google-auth-library-oauth2-http:1.19.0")

    // ==================== Testing ====================
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    implementation("com.google.code.gson:gson:2.10.1")
    // ==================== Room ====================
    val room_version = "2.7.0-alpha11"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")
    implementation("net.zetetic:android-database-sqlcipher:4.5.3")

    // ==================== Media3 (ExoPlayer) ====================
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
}
// --- AUTO-GENERATED SIGNING CONFIG ---
android {
    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "android"
            keyAlias = "key0"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
// -------------------------------------
