// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.3.21"
  id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = "com.intellij.helidon"
version = "262.0.14"

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
        <li>Enabled open-ended IntelliJ compatibility from the 2026.1 line onward and added verifier coverage for the 2026.2 EAP line.</li>
        <li>Reduced Helidon Services refresh work by reacting only to relevant project files, config keys, annotations, and route path inputs.</li>
        <li>Improved LangChain4j properties support so Services entries and <code>@Ai</code> annotation navigation resolve logical runtime entries, dotted ids, and MCP clients consistently.</li>
        <li>Fixed the LangChain4j Agent file template to import the correct Helidon <code>Ai</code> annotation package.</li>
        <li>Improved config metadata handling for resource-root config file detection, unsaved <code>config-metadata.json</code> edits, reused metadata types, and duplicate metadata keys.</li>
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
