plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.google.services) apply false
}

// Release version 1.0.N, where N is MJOLNIR_BUILD (Gradle property or environment); 1.0.0 without it.
val mjolnirBuild: Int = ((findProperty("MJOLNIR_BUILD") as String?) ?: System.getenv("MJOLNIR_BUILD"))
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?.let { requireNotNull(it.toIntOrNull()?.takeIf { n -> n in 0..65535 }) { "MJOLNIR_BUILD must be 0..65535, was '$it'" } }
    ?: 0
extra["mjolnirBuild"] = mjolnirBuild
extra["mjolnirVersion"] = "1.0.$mjolnirBuild"
