plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.bimz.reforge"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.bimz.reforge"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O2", "-fvisibility=hidden")
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                val capstoneDir = project.findProperty("CAPSTONE_SRC_DIR")?.toString()
                    ?: System.getenv("CAPSTONE_SRC_DIR")
                if (!capstoneDir.isNullOrBlank()) {
                    arguments += "-DCAPSTONE_SRC_DIR=$capstoneDir"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
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
        debug {
            signingConfigs.findByName("reforgePersistent")?.takeIf { it.storeFile?.exists() == true }?.let { signingConfig = it }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            signingConfigs.findByName("reforgePersistent")?.takeIf { it.storeFile?.exists() == true }?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val ksPath = System.getenv("REFORGE_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                signingConfigs {
                    create("ciRelease") {
                        storeFile = file(ksPath)
                        storePassword = System.getenv("REFORGE_KEYSTORE_PASSWORD") ?: ""
                        keyAlias = System.getenv("REFORGE_KEY_ALIAS") ?: ""
                        keyPassword = System.getenv("REFORGE_KEY_PASSWORD") ?: ""
                    }
                }
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module"
            )
        }
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("failed", "standardError")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showExceptions = true
                showCauses = true
                showStackTraces = true
            }
        }
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.compose.material:material-icons-extended")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(project(":core"))
    implementation(project(":engine-registry"))
    implementation(project(":storage"))
    implementation(project(":filemanager"))
    implementation(project(":apk-inspector"))
    implementation(project(":dex-analysis"))
    implementation(project(":resource-inspector"))
    implementation(project(":binary-analysis"))
    implementation(project(":hex-viewer"))
    implementation(project(":patch-workspace"))
    implementation(project(":rebuild-engine"))
    implementation(project(":signing-engine"))
    implementation(project(":terminal-interface"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.android.smali.dexlib2)
    testImplementation(libs.android.smali.assembler)
    testImplementation(libs.android.apksig)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
