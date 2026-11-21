plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号规则：年.月.次数，例如 26.11.1
// CI 通过 -PappVersionName=26.11.N 注入；本地默认 26.11.1
val appVersionName: String =
    (project.findProperty("appVersionName") as String?)
        ?: System.getenv("APP_VERSION_NAME")
        ?: "26.11.1"

val versionParts = appVersionName.split(".").mapNotNull { it.toIntOrNull() }
val appVersionCode: Int =
    (versionParts.getOrElse(0) { 26 } * 10000) +
        (versionParts.getOrElse(1) { 11 } * 100) +
        versionParts.getOrElse(2) { 1 }

android {
    namespace = "com.cloudhub.drive"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cloudhub.drive"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // CI 从 Secrets 解码 keystore.jks 到 app/ 目录；本地无文件时回退 debug 签名
        create("release") {
            val ks = file("keystore.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "cloudhub2026"
                keyAlias = System.getenv("KEY_ALIAS") ?: "cloudhub"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "cloudhub2026"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (file("keystore.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
