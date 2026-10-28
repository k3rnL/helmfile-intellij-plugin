import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.helmfile.intellij"
version = "0.5.2"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation("org.snakeyaml:snakeyaml-engine:3.2")
    intellijPlatform {
        val idePath = providers.gradleProperty("localIdePath").orNull
        if (idePath != null) local(idePath) else intellijIdea("2026.1.5")
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledModule("intellij.platform.vcs.impl")
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
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("marketplaceChannel").map { listOf(it) }.orElse(listOf("default"))
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    pluginVerification {
        failureLevel = VerifyPluginTask.FailureLevel.ALL
        ides {
            current()
            providers.gradleProperty("verificationIdePath").orNull?.let { local(it) }
        }
    }
}

tasks.register("verifyReleaseVersion") {
    val pluginReleaseVersion = project.version.toString()
    val releaseTag = providers.environmentVariable("RELEASE_TAG")
    group = "verification"
    description = "Check that RELEASE_TAG matches the plugin version before publishing."
    inputs.property("pluginVersion", pluginReleaseVersion)
    inputs.property("releaseTag", releaseTag.orElse(""))
    doLast {
        check(releaseTag.orNull == "v$pluginReleaseVersion") {
            "Release tag must be v$pluginReleaseVersion (the version in build.gradle.kts); received '${releaseTag.orNull.orEmpty()}'."
        }
    }
}

tasks.named("verifyPluginSignature") {
    dependsOn(tasks.named("signPlugin"))
}

tasks.test {
    maxHeapSize = "2g"
    systemProperty("java.awt.headless", "true")
}
