plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.impecabel.tapedeck.sample.benchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":sample-app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Declared first, like in real benchmark modules: its network security config ranks above Tapedeck's manifest, so
    // only this module's manifest can pick Tapedeck's config (tools:replace in the library's manifest wouldn't do)
    implementation(libs.androidx.benchmark.common)
    implementation(project(":tapedeck-macrobenchmark"))
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
}

// Regression check (part of `assemble`): the merged test APK manifest must use Tapedeck's network security config
val verifyTapedeckNetworkSecurityConfig by tasks.registering {
    val manifests = fileTree(layout.buildDirectory.dir("intermediates/packaged_manifests")) {
        include("**/AndroidManifest.xml")
    }
    dependsOn("processBenchmarkManifest", "processDebugManifest")
    doLast {
        check(!manifests.isEmpty) { "No merged manifests found" }
        manifests.forEach { manifest ->
            check("android:networkSecurityConfig=\"@xml/tapedeck_network_security_config\"" in manifest.readText()) {
                "$manifest doesn't use @xml/tapedeck_network_security_config"
            }
        }
    }
}
tasks.named("assemble") { dependsOn(verifyTapedeckNetworkSecurityConfig) }
