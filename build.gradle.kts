import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "org.paulg786"
version = "0.3.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.5")
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "org.paulg786.jenkinsbranchmonitor"
        name = "Branch Monitor for Jenkins"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
        }
    }

    // Plugin Verifier: `gradle verifyPlugin` checks against the IDE versions recommended for the since/until range.
    // Quick check against a single version instead: `gradle verifyPlugin -PverifyIde=2025.1`.
    pluginVerification {
        ides {
            val singleIde = providers.gradleProperty("verifyIde").orNull
            if (singleIde != null) ide(IntelliJPlatformType.IntellijIdeaCommunity, singleIde) else recommended()
        }
    }

    // Optional signing — values only from environment variables, never commit keys.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Updates after the first (manual) upload: `gradle publishPlugin` with PUBLISH_TOKEN set.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

kotlin {
    jvmToolchain(21)
}
