plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.mjolnir"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mjolnir"
        minSdk = 29
        targetSdk = 36
        val build = rootProject.extra["mjolnirBuild"] as Int
        versionCode = maxOf(build, 1)
        versionName = rootProject.extra["mjolnirVersion"] as String
        val relayUrl = (findProperty("mjolnir.relayUrl") ?: "").toString().replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    // Adaptive icons need the -v26 resource qualifier even though minSdk is 29.
    lint { disable += "ObsoleteSdkInt" }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":relay-client"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":relay-client")))
    testImplementation(libs.ktor.client.mock)
}

if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")
