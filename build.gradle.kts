
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileOutputStream
import java.util.*

plugins {
	java
	kotlin("jvm") version "1.7.21"
	kotlin("plugin.serialization") version "1.7.21"
	application
	id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "org.rickosborne"
version = "1.0.4"
val ktlint: Configuration by configurations.creating

repositories {
	mavenCentral()
}

dependencies {
	testImplementation(kotlin("test"))
	implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
	implementation("com.charleskorn.kaml:kaml:0.49.0")
	implementation("ch.qos.logback:logback-classic:1.4.5")
	implementation("io.github.microutils:kotlin-logging-jvm:3.0.4")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
	implementation("org.apache.commons:commons-text:1.10.0")
	implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.7.21")
	implementation("org.yaml:snakeyaml:1.33")
	implementation("org.snakeyaml:snakeyaml-engine:2.5")
	ktlint("com.pinterest:ktlint:0.47.1") {
		attributes {
			attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
		}
	}
}

val outputDir = "${project.buildDir}/reports/ktlint/"
val inputFiles = project.fileTree(mapOf("dir" to "src", "include" to "**/*.kt"))
val generatedVersionDir = "$buildDir/generated-version"

val ktlintCheck by tasks.creating(JavaExec::class) {
	inputs.files(inputFiles)
	outputs.dir(outputDir)

	description = "Check Kotlin code style."
	classpath = ktlint
	mainClass.set("com.pinterest.ktlint.Main")
	// see https://pinterest.github.io/ktlint/install/cli/#command-line-usage for more information
	args = listOf("src/**/*.kt")
}

val ktlintFormat by tasks.creating(JavaExec::class) {
	inputs.files(inputFiles)
	outputs.dir(outputDir)

	description = "Fix Kotlin code style deviations."
	classpath = ktlint
	mainClass.set("com.pinterest.ktlint.Main")
	// see https://pinterest.github.io/ktlint/install/cli/#command-line-usage for more information
	args = listOf("-F", "src/**/*.kt")
}

tasks.test {
	useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
	kotlinOptions.jvmTarget = "1.8"
}

tasks.withType<ShadowJar> {
	archiveFileName.set("websrvmon.jar")
}

application {
	mainClass.set("MainKt")
}

sourceSets {
	main {
		kotlin {
			output.dir(generatedVersionDir)
		}
	}
}

tasks.jar {
	manifest {
		attributes["Main-Class"] = "MainKt"
		attributes["Implementation-Version"] = archiveVersion
		attributes["Implementation-Title"] = "websrvmon"
		attributes["Implementation-Vendor"] = "rickosborne.org"
	}
	configurations["compileClasspath"].forEach { file: File ->
		from(zipTree(file.absoluteFile))
	}
	duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.register("generateVersionProperties") {
	doLast {
		val propertiesFile = file("$generatedVersionDir/version.properties")
		propertiesFile.parentFile.mkdirs()
		val properties = Properties()
		properties.setProperty("version", "$version")
		val out = FileOutputStream(propertiesFile)
		properties.store(out, null)
	}
}

tasks.named("processResources") {
	dependsOn("generateVersionProperties")
}
