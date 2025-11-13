import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.changelog)
}

group = "lol.bai.ravel"
version = libs.versions.ravel.get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform.defaultRepositories()
}

configurations {
    create("stub")
}

dependencies {
    implementation(libs.mapping.io)

    val stub by configurations
    stub(libs.kotlin.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    intellijPlatform {
        create {
            type = IntelliJPlatformType.IntellijIdeaCommunity
            version = libs.versions.intellij.idea
        }

        // https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")

        testFramework(TestFrameworkType.Platform)
    }
}

// https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = project.name
        version = project.version.toString()

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        changeNotes = with(project.changelog) {
            renderItem(
                (getOrNull(project.version.toString()) ?: getUnreleased())
                    .withHeader(false)
                    .withEmptySections(false),
                Changelog.OutputType.HTML,
            )
        }

        ideaVersion {
            // Supported build number ranges and IntelliJ Platform versions
            // https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html
            sinceBuild = "251"
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf(
            project.version.toString()
                .substringAfter('-', "")
                .substringBefore('.')
                .ifEmpty { "default" }
        )
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = "https://github.com/badasintended/ravel"
}

tasks {
    publishPlugin {
        dependsOn(patchChangelog)
    }
}
