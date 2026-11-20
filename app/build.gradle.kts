plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.bimz.reforge"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.bimz.reforge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("reforgePersistent") {
            val envPath = System.getenv("REFORGE_KEYSTORE_PATH")
            val localKs = file("reforge-signing.jks")
            if (!envPath.isNullOrBlank() && file(envPath).exists()) {
                storeFile = file(envPath)
                storePassword = System.getenv("REFORGE_KEYSTORE_PASS") ?: "reforge_signing_key_2026"
                keyAlias = System.getenv("REFORGE_KEY_ALIAS") ?: "reforge"
                keyPassword = System.getenv("REFORGE_KEY_PASS") ?: "reforge_signing_key_2026"
            } else if (localKs.exists()) {
                storeFile = localKs
                storePassword = "reforge_signing_key_2026"
                keyAlias = "reforge"
                keyPassword = "reforge_signing_key_2026"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            val sc = signingConfigs.findByName("reforgePersistent")
            if (sc?.storeFile?.exists() == true) {
                signingConfig = sc
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val sc = signingConfigs.findByName("reforgePersistent")
            if (sc?.storeFile?.exists() == true) {
                signingConfig = sc
            }
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
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-utils"))
    implementation(project(":engine-registry"))
    implementation(project(":binary-analyzer"))
    implementation(project(":hex-editor"))
    implementation(project(":terminal-interface"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
