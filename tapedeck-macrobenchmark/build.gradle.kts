plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.impecabel.tapedeck.macrobenchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":tapedeck-core"))
    api(libs.okhttp)
    api(libs.okhttp.mockwebserver)
    api(libs.junit)

    compileOnly(libs.androidx.benchmark.macro)
    compileOnly(libs.androidx.uiautomator)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

mavenPublishing {
    coordinates("com.impecabel.tapedeck", "tapedeck-macrobenchmark", "0.1.0")

    pom {
        name.set("Tapedeck Macrobenchmark")
        description.set("Macrobenchmark test rule and harness for HTTP record/replay in Android benchmarks")
        inceptionYear.set("2026")
        url.set("https://github.com/tapedeck/tapedeck")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("tapedeck")
                name.set("Tapedeck Contributors")
                url.set("https://github.com/tapedeck/tapedeck")
            }
        }
        scm {
            url.set("https://github.com/tapedeck/tapedeck")
            connection.set("scm:git:git://github.com/tapedeck/tapedeck.git")
            developerConnection.set("scm:git:ssh://git@github.com/tapedeck/tapedeck.git")
        }
    }
}
