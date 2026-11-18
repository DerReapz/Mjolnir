plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":relay-client"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(testFixtures(project(":relay-client")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
}

tasks.processResources {
    val relayUrl = (findProperty("mjolnir.relayUrl") as String?) ?: ""
    inputs.property("relayUrl", relayUrl)
    filesMatching("mjolnir.properties") { expand("relayUrl" to relayUrl) }
}

tasks.test { useJUnitPlatform() }
