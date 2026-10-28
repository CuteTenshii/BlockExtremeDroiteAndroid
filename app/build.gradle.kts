import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from keystore.properties (git-ignored) or BED_* environment variables.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingValue(key: String, environmentVariable: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(environmentVariable)

android {
    namespace = "moe.tenshii.blockextremedroite"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "moe.tenshii.blockextremedroite"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            signingValue("storeFile", "BED_KEYSTORE_FILE")?.let { path ->
                storeFile = rootProject.file(path)
                storePassword = signingValue("storePassword", "BED_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "BED_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "BED_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
