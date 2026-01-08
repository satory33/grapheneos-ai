plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.satory.graphenosai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.satory.graphenosai"
        minSdk = 26 // Required for Android Keystore AES-GCM
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // NDK configuration for native whisper.cpp
        // Temporarily disabled - using Vosk for speech recognition
        // ndk {
        //     abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        // }
        
        // External native build
        // externalNativeBuild {
        //     cmake {
        //         cppFlags += listOf("-std=c++17", "-O3")
        //         arguments += listOf(
        //             "-DANDROID_STL=c++_shared",
        //             "-DANDROID_PLATFORM=android-26"
        //         )
        //     }
        // }
    }
    
    // Native build configuration - temporarily disabled
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with release key for production
            // signingConfig = signingConfigs.getByName("release")
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
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    
    // Reproducible builds
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    
    // Additional Compose components
    implementation("androidx.compose.material:material-icons-extended:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    
    // Security - Android Keystore
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Network - using built-in HttpsURLConnection for minimal dependencies
    // Optional: Add OkHttp for better HTTP/2 support
    // implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON parsing - using built-in org.json
    // Optional: Add Moshi/Gson for better ergonomics
    // implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    
    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    
    // Vosk - offline speech recognition (no Google dependency)
    implementation("com.alphacephei:vosk-android:0.3.47")
    
    // Markdown rendering
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.28.0")
    
    // PDF text extraction
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    
    // Testing
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}