plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
    // Readable by consumers on Kotlin 2.0+: metadata for 2.0, and a dependency on the 2.0 standard library
    coreLibrariesVersion = libs.versions.kotlin.consumer.get()
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    // Only its constants are used: implementation keeps kotlinx-serialization off the app's compile classpath
    implementation(project(":tapedeck-core"))
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

mavenPublishing {
    coordinates("com.impecabel.tapedeck", "tapedeck-okhttp", "0.2.1")

    pom {
        name.set("Tapedeck OkHttp")
        description.set("OkHttp interceptor for Tapedeck record/replay in Android benchmarks")
        inceptionYear.set("2026")
        url.set("https://github.com/jrocharodrigues/tapedeck")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("jrocharodrigues")
                name.set("José Rodrigues")
                url.set("https://github.com/jrocharodrigues")
            }
        }
        scm {
            url.set("https://github.com/jrocharodrigues/tapedeck")
            connection.set("scm:git:git://github.com/jrocharodrigues/tapedeck.git")
            developerConnection.set("scm:git:ssh://git@github.com/jrocharodrigues/tapedeck.git")
        }
    }
}
