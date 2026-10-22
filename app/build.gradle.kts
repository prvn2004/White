plugins {
    alias(libs.plugins.android.application)
}

val resolvedVersionName = (project.findProperty("versionName") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: "1.2.0"

val resolvedVersionCode = (project.findProperty("versionCode") as? String)
    ?.toIntOrNull()
    ?: System.getenv("APP_VERSION_CODE")?.toIntOrNull()
    ?: run {
        val parts = resolvedVersionName.removePrefix("v").split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.substringBefore("-")?.toIntOrNull() ?: 0
        (major * 10000 + minor * 100 + patch).coerceAtLeast(1)
    }

val keystorePath = System.getenv("SIGNING_STORE_FILE")
val keystorePassword = System.getenv("SIGNING_STORE_PASSWORD")
val keyAliasValue = System.getenv("SIGNING_KEY_ALIAS")
val keyPasswordValue = System.getenv("SIGNING_KEY_PASSWORD")

val hasReleaseSigning = !keystorePath.isNullOrBlank() &&
    !keystorePassword.isNullOrBlank() &&
    !keyAliasValue.isNullOrBlank() &&
    !keyPasswordValue.isNullOrBlank() &&
    file(keystorePath).exists()

val isReleaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}

if (isReleaseTaskRequested && System.getenv("CI") == "true" && !hasReleaseSigning) {
    throw GradleException(
        "Release signing credentials (SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD) are required for CI release builds."
    )
}

android {
    namespace = "open.source.white"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "open.source.white"
        minSdk = 24
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

