plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
}

subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<PublishingExtension>("publishing") {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/jrocharodrigues/tapedeck")
                    credentials {
                        username = (project.findProperty("gpr.user") as? String) ?: System.getenv("GITHUB_ACTOR")
                        password = (project.findProperty("gpr.key") as? String) ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}
