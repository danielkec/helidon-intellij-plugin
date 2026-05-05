// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.3.21"
  id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = "com.intellij.helidon"
version = "262.0.6"

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
    bundledPlugin("com.intellij.modules.ultimate")
    bundledPlugin("com.intellij.properties")
    bundledPlugin("com.intellij.modules.json")
    bundledPlugin("org.jetbrains.idea.maven")
    bundledPlugin("org.jetbrains.plugins.yaml")
    bundledPlugin("com.intellij.microservices.jvm")

    testBundledPlugin("com.intellij.java-i18n")
    testBundledPlugin("com.intellij.javaee")
    testBundledPlugin("org.jetbrains.kotlin")
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

    changeNotes = ""
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
