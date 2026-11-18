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
    api(libs.okhttp)
    testImplementation(libs.junit)
}

mavenPublishing {
    coordinates("com.impecabel.tapedeck", "tapedeck-okhttp-noop", "0.1.0")

    pom {
        name.set("Tapedeck OkHttp No-Op")
        description.set("No-op implementation of Tapedeck OkHttp interceptor for release builds")
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
