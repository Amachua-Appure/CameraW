import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cameraw"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cameraw"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-O3 -ffast-math -flto"
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts.add("**/libc++_shared.so")
            pickFirsts.add("lib/**/libavcodec.so")
            pickFirsts.add("lib/**/libavformat.so")
            pickFirsts.add("lib/**/libavutil.so")
            pickFirsts.add("lib/**/libswscale.so")
            pickFirsts.add("lib/**/libswresample.so")
            pickFirsts.add("lib/**/libavdevice.so")
            pickFirsts.add("lib/**/libavfilter.so")
        }
        resources {
            pickFirsts.add("META-INF/native-image/**")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")


    implementation("androidx.heifwriter:heifwriter:1.1.0")

    implementation(libs.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation("org.bytedeco:javacpp:1.5.13")
    implementation("org.bytedeco:ffmpeg:8.0.1-1.5.13")


    runtimeOnly("org.bytedeco:javacpp:1.5.13:android-arm64")
    runtimeOnly("org.bytedeco:ffmpeg:8.0.1-1.5.13:android-arm64-gpl")


    implementation("com.google.android.material:material:1.13.0")
    implementation("io.github.awxkee:jxl-coder:2.6.0")
    implementation("com.github.awxkee:avif-coder:2.1.4")
    implementation("com.github.awxkee:avif-coder-coil:1.8.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}