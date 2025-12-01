import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java") // Java support
    id("org.jetbrains.kotlin.jvm") version "2.2.0" // Kotlin support
    id("org.jetbrains.intellij.platform") version "2.7.0" // IntelliJ Platform Gradle Plugin
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
    id("org.jetbrains.changelog") version "2.3.0" // Gradle Changelog Plugin
    id("org.jetbrains.qodana") version "2025.1.1" // Gradle Qodana Plugin
    id("org.jetbrains.kotlinx.kover") version "0.9.1" // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    // Remove JUnit 5 - use ONLY JUnit 4 for compatibility with IntelliJ Platform testing
    // testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    // testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1") 
    // testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")

    // Keep only JUnit 4 for IntelliJ Platform compatibility
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Add MockK for Kotlin mocking
    testImplementation("io.mockk:mockk:1.13.8")

    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))

        // Remove com.intellij.terminal since it's not in IC by default
        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins")
                .map { it.split(',') }
        )

        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
        testFramework(TestFrameworkType.Platform)
    }
}


// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

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

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    signing {
        val certChainFile = providers.environmentVariable("CERTIFICATE_CHAIN")
        val privateKeyFile = providers.environmentVariable("PRIVATE_KEY")

        certificateChain = certChainFile.map {
            File(it).readText(Charsets.UTF_8)
        }
        privateKey = privateKeyFile.map {
            File(it).readText(Charsets.UTF_8)
        }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            ide(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
            // Optionally verify against additional IDEs:
            // ide("IC", "2024.3.6")
            // ide("IU", "2024.3.6")
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    // Task to copy runtime dependencies for SBOM generation
    register<Copy>("copyDependencies") {
        from(configurations.runtimeClasspath)
        into("build/sbom-deps")
    }
}

tasks.test {
    useJUnit()  // Use ONLY JUnit 4 (remove useJUnitPlatform())
    
    // Set system properties to bypass biometric
    systemProperty("keeper.test.mode", "true")
    systemProperty("keeper.skip.auth", "true") 
    systemProperty("java.awt.headless", "true")  // Prevent UI dialogs in tests
    
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showCauses = true
        showExceptions = true
    }
    
    // For now, exclude integration tests that require real Keeper CLI
    exclude("**/KeeperActionTestSuite*")
    exclude("**/KeeperActionsIntegrationTest*") 
    
    // Include your action tests
    include("**/KeeperActionsBasicTest*")
    include("**/KeeperCommandUtilsTest*")
    include("**/KeeperModelsTest*") 
    include("**/KeeperShellServiceTest*")
    include("**/KeeperPluginServiceTest*")
    include("**/KeeperRecordAddActionTest*")
    include("**/KeeperGenerateSecretsActionTest*")
    include("**/KeeperFolderSelectActionTest*")
    include("**/KeeperGetSecretActionTest*")
    include("**/KeeperRecordUpdateActionTest*")
    include("**/KeeperSecretActionTest*")
    include("**/KeeperAuthActionTest*")
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}
