plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.exiledddev"
version = "1.0.0"
description = "Random nicknames and skins for recording sessions, with full vanilla selector support."

val paperApi = "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(paperApi)

    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        // `./gradlew runServer` starts a local Paper 1.21.11 test server with the plugin installed.
        minecraftVersion("1.21.11")
    }
}
