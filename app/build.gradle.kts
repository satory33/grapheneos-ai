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
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-ffast-math")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26",
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/llama/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(property("MYAPP_UPLOAD_STORE_FILE") as String)
            storePassword = property("MYAPP_UPLOAD_STORE_PASSWORD") as String
            keyAlias = property("MYAPP_UPLOAD_KEY_ALIAS") as String
            keyPassword = property("MYAPP_UPLOAD_KEY_PASSWORD") as String
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")

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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libggml*.so", "**/libllama.so")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/cpp/llama/prebuilt")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation("androidx.compose.material:material-icons-extended:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.28.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

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

tasks.register("checkLlamaPrebuilt") {
    doLast {
        val prebuiltDir = file("src/main/cpp/llama/prebuilt/arm64-v8a")
        val required = listOf("libggml-base.so", "libggml-cpu.so", "libggml.so", "libllama.so")
        val missing = required.filter {
            !file("src/main/cpp/llama/prebuilt/arm64-v8a/$it").exists()
        }

        if (missing.isNotEmpty()) {
            println("WARNING: Missing prebuilt llama libs: $missing")
            println("Local AI (llama.cpp) will not be available in this build.")
            println("The stub JNI implementation will handle this gracefully.")
        } else {
            println("All required prebuilt llama libs present in ${prebuiltDir.absolutePath}")
        }
    }
}
