plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt") // Enable kapt to compile Kotlin Room annotation database classes
}

android {
    namespace = "com.nuwa.skillchat"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file("nuwa_keystore.jks")
            storePassword = "123456"
            keyAlias = "nuwa_alias"
            keyPassword = "123456"
        }
        getByName("debug") {
            storeFile = file("nuwa_keystore.jks")
            storePassword = "123456"
            keyAlias = "nuwa_alias"
            keyPassword = "123456"
        }
    }

    defaultConfig {
        applicationId = "com.nuwa.skillchat"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // OkHttp (API layer)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Room Database - Compiled with Kapt
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Markwon (Markdown + LaTeX rendering)
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")
    implementation("io.noties:jlatexmath-android:0.2.0")
}
