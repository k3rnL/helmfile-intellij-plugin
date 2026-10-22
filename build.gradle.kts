import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.helmfile.intellij"
version = "0.3.2"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val idePath = providers.gradleProperty("localIdePath").orNull
        if (idePath != null) local(idePath) else intellijIdea("2026.1.5")
        bundledPlugin("org.jetbrains.plugins.yaml")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

intellijPlatform {
    pluginConfiguration {
        name = "Helmfile"
        ideaVersion { sinceBuild = "261" }
    }
    buildSearchableOptions = false
    pluginVerification {
        failureLevel = VerifyPluginTask.FailureLevel.ALL
        ides {
            current()
            providers.gradleProperty("verificationIdePath").orNull?.let { local(it) }
        }
    }
}

tasks.test {
    maxHeapSize = "2g"
    systemProperty("java.awt.headless", "true")
}
