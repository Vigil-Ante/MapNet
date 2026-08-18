import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val updateProperties = Properties().apply {
    val updatePropertiesFile = rootProject.file("update.properties")
    if (updatePropertiesFile.exists()) {
        updatePropertiesFile.inputStream().use(::load)
    }
}

fun configuredValue(propertyName: String, filePropertyName: String): String? =
    providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(propertyName).orNull
        ?: updateProperties.getProperty(filePropertyName)

val updateManifestUrl = configuredValue("MAPNET_UPDATE_MANIFEST_URL", "update.manifest.url").orEmpty()
val configuredVersionCode = providers.gradleProperty("mapnetVersionCode").orNull?.toIntOrNull()
val configuredVersionName = providers.gradleProperty("mapnetVersionName").orNull
val releaseStoreFile = configuredValue("MAPNET_RELEASE_STORE_FILE", "release.store.file")
val releaseStorePassword = configuredValue("MAPNET_RELEASE_STORE_PASSWORD", "release.store.password")
val releaseKeyAlias = configuredValue("MAPNET_RELEASE_KEY_ALIAS", "release.key.alias")
val releaseKeyPassword = configuredValue("MAPNET_RELEASE_KEY_PASSWORD", "release.key.password")

android {
    namespace = "com.mapnet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mapnet"
        minSdk = 26
        targetSdk = 35
        versionCode = configuredVersionCode ?: 1
        versionName = configuredVersionName ?: "0.1.0"

        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"${updateManifestUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (!releaseStoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
