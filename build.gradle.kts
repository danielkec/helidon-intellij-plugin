// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.3.21"
  id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = "com.intellij.helidon"
version = "262.0.10"

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
      untilBuild = "261.*"
    }

    changeNotes = """
      <ul>
        <li>Added Helidon LangChain4j navigation between <code>@Ai.*</code> annotations and <code>langchain4j.*</code> configuration, including service, agent, model, content retriever, provider, embedding store, and MCP client references.</li>
        <li>Added IntelliJ HTTP Mappings gutter navigation for Helidon Declarative <code>@RestServer.Endpoint</code> HTTP method annotations, including inherited/interface declarations and custom <code>@Http.HttpMethod</code> annotations.</li>
        <li>Improved Declarative HTTP mapping navigation with <code>http-method:</code> Endpoints filters, multi-endpoint handling, endpoint-module-aware navigation, and cached target lookup.</li>
        <li>Expanded Helidon WebServer endpoint discovery for route builder/helper overloads, <code>anyOf</code>, <code>HttpRoute</code> objects, wildcard route types, registered service parent paths, and path matcher factories.</li>
        <li>Improved URL resolver, inlay, and path-parameter reference support for route overloads and path matcher semantics.</li>
        <li>Made the base plugin descriptor compatible with IntelliJ IDEA Community by loading Microservices-dependent integrations only when the optional Microservices plugin is available.</li>
        <li>Removed unsupported Kotlin source-language registration for Helidon config references.</li>
        <li>Hid the Google Login authentication provider from the Helidon new project wizard.</li>
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
