// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.3.21"
  id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = "com.intellij.helidon"
version = "262.0.13"

kotlin {
  jvmToolchain(21)
}

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    intellijIdea("2026.1.1")
    bundledPlugin("com.intellij.java")
    bundledPlugin("com.intellij.properties")
    bundledPlugin("com.intellij.modules.json")
    bundledPlugin("org.jetbrains.idea.maven")
    bundledPlugin("JUnit")
    bundledPlugin("org.jetbrains.plugins.yaml")
    bundledPlugin("com.intellij.microservices.jvm")
    bundledPlugin("com.intellij.diagram")

    testBundledPlugin("com.intellij.java-i18n")
    testBundledPlugin("com.intellij.javaee")
    testBundledPlugin("tanvd.grazi")

    testFramework(TestFrameworkType.Plugin.Java)
    testFramework(TestFrameworkType.Platform)
  }
  testImplementation("junit:junit:4.13.2")
}

java.sourceSets["main"].java {
  srcDir("src")
  srcDir("gen")
}

java.sourceSets["main"].resources {
  srcDir("resources")
}

java.sourceSets["test"].java {
  srcDir("test")
}

intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "261"
    }

    changeNotes = """
      <ul>
        <li>Fixed Helidon Services tool window startup so it remains registered while project libraries are still being imported.</li>
        <li>Enhanced the Declarative HTTP Service template with an editable endpoint path and generated GET, POST, PUT, and DELETE greeting handlers.</li>
        <li>Registered Helidon built-in templates in the plugin descriptor so New menu actions resolve the bundled service, test, LangChain4j, and OCI templates reliably.</li>
      </ul>
    """.trimIndent()
  }

  pluginVerification {
    // This fork intentionally keeps the inherited JetBrains plugin ID for the custom update repository.
    freeArgs.addAll("-mute", "ForbiddenPluginIdPrefix", "-mute", "TemplateWordInPluginId")

    ides {
      // IDEA 2026.1 is distributed as the unified IU product; IC verifier artifacts are no longer published.
      // Community compatibility is covered by descriptor tests that keep microservices registrations optional.
      create(IntelliJPlatformType.IntellijIdea, "2026.1.1")
      create(IntelliJPlatformType.IntellijIdea, "262.7132.23")
    }
  }
}

intellijPlatformTesting {
  runIde {
    register("runIdeWithoutMicroservices") {
      plugins {
        disablePlugin("com.intellij.microservices.jvm")
      }
    }
  }
}

tasks {
  // Set the JVM compatibility versions
  withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
  }

  test {
    providers.gradleProperty("idea.home.path")
      .orElse(providers.environmentVariable("IDEA_HOME_PATH"))
      .orNull
      ?.let { systemProperty("idea.home.path", it) }
  }
}
