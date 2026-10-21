import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// ---------------------------------------------------------------------------
// Deployment configuration that MUST be supplied before a production release.
// Values are read (in order) from: -P Gradle properties, local.properties,
// then environment variables. Nothing secret is committed to the repository.
//
//   MAPS_API_KEY         Google Maps SDK key (restricted to this app's package
//                        + signing certificate). Without it the map card shows
//                        a placeholder; camera alerts still work.
//   CAMERA_API_BASE_URL  HTTPS base URL of the licensed camera-data service,
//                        e.g. https://api.your-domain.in/ . Without it, sync
//                        and report upload are disabled.
//   PRIVACY_POLICY_URL   Public URL of the hosted privacy policy (Play Console
//                        requires one). The in-app policy is always available.
// ---------------------------------------------------------------------------
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun deploymentValue(key: String): String =
    (project.findProperty(key) as String?)
        ?: localProperties.getProperty(key)
        ?: System.getenv(key)
        ?: ""

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.drivealert.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.drivealert.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val mapsApiKey = deploymentValue("MAPS_API_KEY")
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
        buildConfigField("boolean", "MAPS_CONFIGURED", mapsApiKey.isNotBlank().toString())
        buildConfigField("String", "CAMERA_API_BASE_URL", deploymentValue("CAMERA_API_BASE_URL").asBuildConfigString())
        buildConfigField("String", "PRIVACY_POLICY_URL", deploymentValue("PRIVACY_POLICY_URL").asBuildConfigString())
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // TODO(release): attach a real signingConfig before publishing.
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

    androidResources {
        // Produces the per-app language list (Android 13+ system language picker).
        generateLocaleConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { it.maxHeapSize = "1g" }
        }
    }

    sourceSets {
        // Unit tests validate the TEST-ONLY synthetic dataset that ships in debug assets.
        getByName("test").resources.srcDir("src/debug/assets")
    }

    lint {
        // Hindi strings are provided for the core flow; untranslated strings fall
        // back to English until a native-speaker review completes the file.
        warning += "MissingTranslation"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.play.services.location)
    implementation(libs.maps.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Local JVM tests (pure domain logic + Robolectric for Room, notifications, service).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    // Instrumented tests (run on a OnePlus 13 / emulator).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
