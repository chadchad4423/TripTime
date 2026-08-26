import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// TripTime talks to OpenRouteService (openrouteservice.org) for geocoding and driving
// directions, using a single key supplied by whoever builds the app — there is no in-app key
// entry (see DECISIONS.md D-005).
//
// Put `ORS_API_KEY=your-key-here` in this project's local.properties, which is gitignored, so
// the key stays out of source control. It is still baked into the built APK and is extractable
// from it by anyone who has the APK; that is an accepted tradeoff, not an oversight. Get a free
// key at https://openrouteservice.org/dev/#/signup.
//
// Building without the property is allowed so a fresh clone still compiles; the app then says
// so at runtime rather than failing mysteriously.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val orsApiKey: String = localProperties.getProperty("ORS_API_KEY", "")
if (orsApiKey.isBlank()) {
    logger.warn(
        "TripTime: no ORS_API_KEY in local.properties — this build cannot reach " +
            "OpenRouteService. See README.md \"Building from source\"."
    )
}

// Release signing (DECISIONS.md D-013). The keystore itself lives outside every git working
// tree — never inside a project folder, gitignored or not, which is what README "Building a
// signed release" tells you to do with yours. A fresh clone therefore has none of these
// properties and `assembleRelease` falls back to producing an unsigned APK rather than
// failing — same posture as the missing-API-key case above. An unsigned release APK builds
// but Android refuses to install it, which is a clear enough signal on its own.
val releaseStoreFilePath: String = localProperties.getProperty("RELEASE_STORE_FILE", "")
val releaseStorePassword: String = localProperties.getProperty("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias: String = localProperties.getProperty("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword: String = localProperties.getProperty("RELEASE_KEY_PASSWORD", "")
val hasReleaseSigningConfig: Boolean = releaseStoreFilePath.isNotBlank() &&
    file(releaseStoreFilePath).exists() &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank()
if (!hasReleaseSigningConfig) {
    logger.warn(
        "TripTime: no release signing config in local.properties — assembleRelease will " +
            "produce an unsigned, uninstallable APK. See README.md \"Building a signed release\"."
    )
}

android {
    namespace = "com.chad.triptime"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.chad.triptime"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ORS_API_KEY", "\"$orsApiKey\"")
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigningConfig) {
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("com.mudita:MMD:1.0.2")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
