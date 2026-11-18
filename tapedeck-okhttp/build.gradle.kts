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
}

dependencies {
    api(project(":tapedeck-core"))
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

mavenPublishing {
    coordinates("io.github.tapedeck", "tapedeck-okhttp", "0.1.0")

    pom {
        name.set("Tapedeck OkHttp")
        description.set("OkHttp interceptor for Tapedeck record/replay in Android benchmarks")
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
