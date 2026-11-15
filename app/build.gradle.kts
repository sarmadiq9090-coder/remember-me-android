import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.cyclonedx.bom") version "3.6.1"
}

// Release signing material lives in the git-ignored keystore/ folder.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.fastkiwi.rememberme"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.fastkiwi.rememberme"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"
        // Arabic is the default (values/), English in values-en/.
        androidResources.localeFilters += listOf("ar", "en")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
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
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    // The APK carries both languages; a bundle must not split them away (per-app language switch).
    bundle { language { enableSplit = false } }

    // No dependency-metadata blob in the APK (privacy, size).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "3g" }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // Dependency versions are pinned on purpose (same as kiwi-scan's verified toolchain) and reviewed at
        // each release, so "a newer version exists" is not a lint failure.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // AAPT2 only finds the adaptive launcher icon under mipmap-anydpi-v26, which lint calls redundant at minSdk 26.
        disable += "ObsoleteSdkInt"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Icons: Material Symbols Rounded vendored as drawables (DESIGN-SPEC §3.4); no icons library.
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    // Per-app language on every Android version (AppCompatDelegate.setApplicationLocales).
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // JSON tree API only (no compiler plugin): data.json is read and written as a tree so fields this
    // app does not use (notes, tags, checklists, Windows settings) survive a round trip untouched.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // An independent RFC 5545 implementation, used only to parse and expand the .ics export in tests.
    testImplementation("org.mnode.ical4j:ical4j:4.0.8")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core-ktx:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ---------------------------------------------------------------------------
// Release SBOM (CycloneDX): the Maven libraries shipped in the APK, plus the bundled font files,
// written to dist/RememberMe-<version>.cdx.json.
// ---------------------------------------------------------------------------
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("releaseRuntimeClasspath"))
    projectType.set(org.cyclonedx.model.Component.Type.APPLICATION)
    componentGroup.set("com.fastkiwi")
    componentName.set("remember-me-android")
    componentVersion.set(android.defaultConfig.versionName)
    jsonOutput.set(layout.buildDirectory.file("reports/sbom/remember-me.cdx.json"))
}

tasks.register("releaseSbom") {
    description = "Writes dist/RememberMe-<version>.cdx.json (CycloneDX SBOM of the release APK)."
    dependsOn("cyclonedxDirectBom")
    val input = layout.buildDirectory.file("reports/sbom/remember-me.cdx.json")
    val version = android.defaultConfig.versionName
    val fonts = file("src/main/res/font")
    val output = rootProject.file("dist/RememberMe-$version.cdx.json")
    inputs.file(input)
    outputs.file(output)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val bom = groovy.json.JsonSlurper().parse(input.get().asFile) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val components = (bom["components"] as? MutableList<Any?>) ?: mutableListOf<Any?>().also { bom["components"] = it }
        fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        fonts.listFiles()!!.filter { it.name.endsWith(".ttf") }.sortedBy { it.name }.forEach { f ->
            components += mapOf(
                "type" to "data", "bom-ref" to "font:${f.name}", "name" to "Noto Sans Arabic (${f.nameWithoutExtension})",
                "hashes" to listOf(mapOf("alg" to "SHA-256", "content" to sha256(f))),
                "licenses" to listOf(mapOf("license" to mapOf("id" to "OFL-1.1"))),
                "externalReferences" to listOf(mapOf("type" to "website", "url" to "https://notofonts.github.io/")),
            )
        }
        // The vendored Material Symbols Rounded drawables (Apache-2.0, THIRD_PARTY.md).
        components += mapOf(
            "type" to "data", "bom-ref" to "icons:material-symbols-rounded", "name" to "Material Symbols Rounded (res/drawable/ms_*.xml)",
            "licenses" to listOf(mapOf("license" to mapOf("id" to "Apache-2.0"))),
            "externalReferences" to listOf(mapOf("type" to "vcs", "url" to "https://github.com/google/material-design-icons")),
        )
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(bom)) + "\n")
    }
}
