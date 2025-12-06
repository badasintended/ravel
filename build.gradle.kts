import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.kohsuke.github.GitHubBuilder
import java.nio.file.Files

buildscript {
    dependencies {
        classpath(libs.github.api)
    }
}

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

dependencies {
    implementation(libs.mapping.io)

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
        bundledPlugin("com.intellij.modules.json")

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
    versionPrefix = ""
}

tasks {
    publishPlugin {
        dependsOn(patchChangelog)
    }

    val publishGithub by registering(PublishGithubTask::class) {
        dependsOn(buildPlugin)

        githubToken.set(providers.environmentVariable("GITHUB_TOKEN").orNull)

        repoName.set("badasintended/ravel")
        versionName.set(project.version.toString())

        changelogText.set(
            providers.provider {
                val version = project.version.toString()
                val changelog = project.changelog.getOrNull(version) ?: project.changelog.getUnreleased()
                project.changelog.renderItem(changelog, Changelog.OutputType.MARKDOWN)
            }
        )

        archiveFile.set(buildPlugin.flatMap { t ->
            t.outputs.files.singleFile.let { layout.file(project.provider { it }) }
        })
    }

    val publish by registering {
        dependsOn(publishPlugin, publishGithub)
    }
}

abstract class PublishGithubTask : DefaultTask() {
    @get:Input
    abstract val githubToken: Property<String>

    @get:Input
    abstract val repoName: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val changelogText: Property<String>

    @get:InputFile
    abstract val archiveFile: RegularFileProperty

    @TaskAction
    fun publish() {
        val token = githubToken.orNull ?: throw IllegalStateException("GITHUB_TOKEN not provided")
        val gh = GitHubBuilder().withOAuthToken(token).build()
        val repo = gh.getRepository(repoName.get())

        val release = repo.createRelease(versionName.get())
            .name(versionName.get())
            .commitish("master")
            .body(changelogText.get())
            .create()

        val zip = archiveFile.asFile.get()
        release.uploadAsset(zip, Files.probeContentType(zip.toPath()))
    }
}
