import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
    implementation("io.nayuki:qrcodegen:1.8.0")

    testImplementation(testFixtures(project(":relay-client")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

tasks.processResources {
    val relayUrl = (findProperty("mjolnir.relayUrl") as String?) ?: ""
    inputs.property("relayUrl", relayUrl)
    filesMatching("mjolnir.properties") { expand("relayUrl" to relayUrl) }
}

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "app.mjolnir.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Mjolnir"
            packageVersion = "1.0.0"
            description = "Send tabs and files between your phone and this PC"
            vendor = "Mjolnir"
            // From suggestRuntimeModules, plus jdk.crypto.ec, which TLS (ECDHE) needs on JDK 21 but jdeps cannot see.
            modules("java.instrument", "java.management", "jdk.unsupported", "jdk.crypto.ec")
            windows {
                menuGroup = "Mjolnir"
                perUserInstall = true
                shortcut = true
                iconFile.set(project.file("src/main/resources/mjolnir.ico"))
                // Generated once; never change it, or upgrades install side by side.
                upgradeUuid = "4d1b4e02-79e1-44b8-9b45-0a822cce7a31"
            }
        }
    }
}
