plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "org.le1a"
// Release builds override this with the workflow's tag. Local/branch builds derive
// a version from Git, so a new release never needs a source-version edit.
val gitVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--long", "--always", "--dirty", "--abbrev=12", "--match", "v[0-9]*")
}.standardOutput.asText.map { output ->
    val description = output.trim()
    val tagged = Regex("^v([0-9]+(?:\\.[0-9]+){1,2})-([0-9]+)-g([0-9a-f]+)(-dirty)?$").matchEntire(description)
    if (tagged != null) {
        val (release, distance, commit, dirty) = tagged.destructured
        if (distance == "0" && dirty.isEmpty()) release
        else "$release-dev.$distance.$commit" + if (dirty.isEmpty()) "" else ".dirty"
    } else {
        val untagged = Regex("^([0-9a-f]+)(-dirty)?$").matchEntire(description)
            ?: throw GradleException("Cannot derive plugin version from Git: $description. Use -PpluginVersion=<version>.")
        "0.0.0-dev.${untagged.groupValues[1]}" + if (untagged.groupValues[2].isEmpty()) "" else ".dirty"
    }
}
version = providers.gradleProperty("pluginVersion").orElse(gitVersion).get()

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.aliyun.com/repository/public")
    }
    
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("stdlib"))
    intellijPlatform {
        intellijIdeaUltimate(providers.gradleProperty("ideaVersion").orElse("2024.1.6"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugins("com.intellij.java", "org.jetbrains.java.decompiler")
        
        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }
}
tasks {
    test {
        // Use an external compiler: the IDE test runtime cannot expose it through ToolProvider.
        val fixtureCompiler = project.extensions.getByType<org.gradle.jvm.toolchain.JavaToolchainService>().compilerFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        systemProperty("test.javac", fixtureCompiler.get().executablePath.asFile.absolutePath)
        systemProperty("benchmark.exports", providers.gradleProperty("benchmarkExports").orElse("false").get())
        if (providers.gradleProperty("benchmarkExports").orElse("false").get().toBoolean()) {
            systemProperty("benchmark.junitJar", project.configurations.getByName("testRuntimeClasspath")
                .files.single { it.name == "junit-4.13.2.jar" }.absolutePath)
        }
        maxHeapSize = "1g"
        testLogging {
            showStandardStreams = providers.gradleProperty("benchmarkExports").orElse("false").get().toBoolean()
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("223")
        untilBuild.set("262.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
