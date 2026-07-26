plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.local.taskwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.local.taskwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.9.1"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/taskwidget.jks")
            storePassword = "taskwidget"
            keyAlias = "taskwidget"
            keyPassword = "taskwidget"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // 单元测试里用真实的 org.json(android.jar 里的是抛异常的桩)
    testImplementation("org.json:json:20240303")
}
