plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "org.le1a"
version = providers.gradleProperty("pluginVersion").orElse("1.3").get()

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
        intellijIdeaUltimate("2024.1.6")
        bundledPlugins("com.intellij.java")
        
        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }
}
tasks {
    test {
        // These tests compile real bytecode; the IDE's bundled runtime may omit javac.
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        })
        testLogging {
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
