import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Secrets are read from local.properties (never committed). Missing values disable the
// matching feature at runtime instead of failing the build.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: "").replace("\"", "")

android {
    namespace = "com.antigravity.monarch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.antigravity.monarch"
        minSdk = 26
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"

        buildConfigField("String", "GEMINI_API_KEY", "\"${secret("GEMINI_API_KEY")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${secret("GEMINI_MODEL")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${secret("GOOGLE_WEB_CLIENT_ID")}\"")
        buildConfigField("String", "STRAVA_CLIENT_ID", "\"${secret("STRAVA_CLIENT_ID")}\"")
        buildConfigField("String", "STRAVA_CLIENT_SECRET", "\"${secret("STRAVA_CLIENT_SECRET")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Release signing comes from local.properties; without it the release APK is unsigned.
        val keystore = secret("MONARCH_KEYSTORE")
        if (keystore.isNotBlank() && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = secret("MONARCH_KEYSTORE_PASSWORD")
                keyAlias = secret("MONARCH_KEY_ALIAS")
                keyPassword = secret("MONARCH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        // Exported Room schemas let MigrationTestHelper verify upgrades on device.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
