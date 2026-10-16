plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val stableKeystore = providers.environmentVariable("GROK_LIGHT_KEYSTORE").orNull
val stablePassword = providers.environmentVariable("GROK_LIGHT_KEYSTORE_PASSWORD").orNull

android {
    namespace = "com.eliotok.groklight"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eliotok.groklight"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.1.4"
    }

    signingConfigs {
        if (!stableKeystore.isNullOrBlank()) {
            require(!stablePassword.isNullOrBlank()) { "Stable signing requires a keystore password" }
            create("stable") {
                storeFile = file(stableKeystore)
                storePassword = stablePassword
                keyAlias = "grok-light"
                keyPassword = stablePassword
            }
        }
    }

    buildTypes {
        debug {
            if (!stableKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (!stableKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("stable")
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
