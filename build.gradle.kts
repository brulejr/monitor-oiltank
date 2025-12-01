val ksbCommonsVersion: String by project

plugins {
	kotlin("jvm") version "2.2.10"
	kotlin("plugin.spring") version "2.2.10"
	id("org.springframework.boot") version "3.5.7"
	id("io.spring.dependency-management") version "1.1.7"
    id("com.google.cloud.tools.jib") version "3.5.1"
}

group = "io.jrb.labs"
version = "0.0.1-SNAPSHOT"
description = "Demo project for Spring Boot"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/brulejr/ksb-commons")
        credentials {
            // Local dev: ~/.gradle/gradle.properties
            username = findProperty("gpr.user") as String?
                ?: System.getenv("GITHUB_ACTOR")
                        ?: "brulejr" // fallback, not super important

            password = findProperty("gpr.key") as String?
                ?: System.getenv("GITHUB_TOKEN")
                        ?: System.getenv("GITHUB_PACKAGES_TOKEN")
        }
    }
}

dependencies {
    implementation(platform("io.jrb.labs:ksb-dependency-bom:$ksbCommonsVersion"))

    implementation("io.jrb.labs:ksb-spring-boot-starter-reactive")

    implementation("org.bytedeco:opencv-platform:4.9.0-1.5.10")

    testImplementation("io.jrb.labs:ksb-spring-boot-starter-reactive-test")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

apply(from = "docker/docker.gradle.kts")

// Directory where ffmpeg gets unpacked – must match docker/docker.gradle.kts
val ffmpegExtractDir = layout.buildDirectory.dir("ffmpeg").get().asFile

jib {
    from {
        image = "eclipse-temurin:21-jre-jammy"
    }

    to {
        image = "brulejr/monitor-oiltank:${project.version}"
        tags = setOf("latest")
    }

    container {
        user = "1000:1000"
        ports = listOf("8080")

        environment = mapOf(
            "APP_MAIN_CLASS" to "io.jrb.labs.monitor.oiltank.MonitorOiltankApplicationKt"
        )

        entrypoint = listOf("/bin/bash", "/opt/docker/entrypoint.sh")
    }

    extraDirectories {
        paths {
            // 1) Entry point script under /opt/docker
            path {
                // ⬇⬇ THIS is the key: use setFrom(...) instead of from = ...
                setFrom(file("docker/jib"))
                into = "/opt/docker"

                // permissions is a MapProperty<String, String> in newer Jib
                permissions.set(
                    mapOf("/opt/docker/entrypoint.sh" to "755")
                )
            }

            // 2) ffmpeg directory in /usr/local/bin
            path {
                setFrom(ffmpegExtractDir)
                into = "/usr/local/bin"

                permissions.set(
                    mapOf("/usr/local/bin/ffmpeg" to "755")
                )
            }
        }
    }
}

// Make sure ffmpeg is downloaded before building the image
tasks.named("jib").configure {
    dependsOn("downloadFfmpeg")
}
tasks.named("jibDockerBuild").configure {
    dependsOn("downloadFfmpeg")
}
