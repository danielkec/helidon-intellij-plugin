// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon

import com.intellij.helidon.newproject.HelidonModuleBuilder
import com.intellij.ide.starters.local.StarterModuleBuilder.Companion.setupTestModule
import com.intellij.ide.starters.shared.*
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase4
import com.intellij.util.lang.JavaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import org.junit.runners.Parameterized.Parameters

@RunWith(Parameterized::class)
class HelidonModuleBuilderTest : LightJavaCodeInsightFixtureTestCase4(LightJavaCodeInsightFixtureTestCase.JAVA_21) {
  @Parameter(0)
  lateinit var generateLanguage: String

  @Parameter(1)
  lateinit var generateBuildSystem: String

  companion object {
    @Parameters(name = "{index}: {0} {1}")
    @JvmStatic
    fun generateOptions(): Collection<Array<*>> {
      val data = mutableListOf<Array<*>>()
      data.add(arrayOf("java", "maven"))
      data.add(arrayOf("java", "gradle"))
      data.add(arrayOf("kotlin", "maven"))
      data.add(arrayOf("kotlin", "gradle"))
      return data
    }

    val LANGUAGES: Map<String, StarterLanguage> = mapOf(
      "java" to JAVA_STARTER_LANGUAGE,
      "kotlin" to KOTLIN_STARTER_LANGUAGE,
    )

    val BUILD_SYSTEMS: Map<String, StarterProjectType> = mapOf(
      "maven" to MAVEN_PROJECT,
      "gradle" to GRADLE_PROJECT
    )
  }

  @Test
  fun generateProject() {
    val moduleBuilder = HelidonModuleBuilder()
    assertEquals(LanguageLevel.JDK_21.toJavaVersion(), getMinJavaVersion(moduleBuilder))

    moduleBuilder.setupTestModule(fixture.module) {
      isCreatingNewProject = true
      language = LANGUAGES[generateLanguage]!!
      projectType = BUILD_SYSTEMS[generateBuildSystem]!!
    }

    expectFileContains(
      "src/main/resources/META-INF/beans.xml",
      "https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd",
      "version=\"4.0\"",
      "bean-discovery-mode=\"annotated\""
    )
    expectFileContains("src/main/resources/META-INF/microprofile-config.properties", "server.port=8080")
    expectBuildMetadata()
  }

  private fun expectBuildMetadata() {
    if (generateBuildSystem == "maven") {
      expectFileContains(
        "pom.xml",
        "<version>4.4.1</version>",
        "<maven.compiler.target>21</maven.compiler.target>",
        "<maven.compiler.source>21</maven.compiler.source>",
        "<maven.compiler.release>21</maven.compiler.release>",
        "<groupId>io.smallrye</groupId>",
        "<artifactId>jandex</artifactId>",
        "<groupId>io.helidon.microprofile.testing</groupId>",
        "<artifactId>helidon-microprofile-testing-junit5</artifactId>"
      )
      expectFileContains(
        ".mvn/wrapper/maven-wrapper.properties",
        "apache-maven/3.9.15/apache-maven-3.9.15-bin.zip",
        "maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar"
      )
      if (generateLanguage == "kotlin") {
        expectFileContains("pom.xml", "<kotlin.version>2.3.21</kotlin.version>", "<jvmTarget>21</jvmTarget>")
      }
    }
    else {
      expectFileContains(
        "build.gradle",
        "id 'org.kordamp.gradle.jandex' version '2.3.0'",
        "sourceCompatibility = JavaVersion.VERSION_21",
        "targetCompatibility = JavaVersion.VERSION_21",
        "io.helidon:helidon-dependencies:4.4.1",
        "runtimeOnly('io.smallrye:jandex')",
        "testImplementation('io.helidon.microprofile.testing:helidon-microprofile-testing-junit5')",
        "testCompileOnly('org.junit.jupiter:junit-jupiter-api')",
        "testRuntimeOnly('org.junit.jupiter:junit-jupiter-engine')"
      )
      expectFileDoesNotContain(
        "build.gradle",
        "testCompileOnly('org.junit.jupiter:junit-jupiter-api:')",
        "testRuntimeOnly('org.junit.jupiter:junit-jupiter-engine:')"
      )
      expectFileContains("gradle/wrapper/gradle-wrapper.properties", "gradle-9.2.0-bin.zip")
      if (generateLanguage == "kotlin") {
        expectFileContains("build.gradle", "id 'org.jetbrains.kotlin.jvm' version '2.3.21'", "jvmTarget = '21'")
      }
    }
  }

  private fun expectFileContains(path: String, vararg expectedSnippets: String) {
    fixture.configureFromTempProjectFile(path)
    val actual = fixture.file.text
    expectedSnippets.forEach {
      assertTrue("Expected $path to contain:\n$it\n\nActual content:\n$actual", actual.contains(it))
    }
  }

  private fun expectFileDoesNotContain(path: String, vararg unexpectedSnippets: String) {
    fixture.configureFromTempProjectFile(path)
    val actual = fixture.file.text
    unexpectedSnippets.forEach {
      assertFalse("Expected $path to not contain:\n$it\n\nActual content:\n$actual", actual.contains(it))
    }
  }

  private fun getMinJavaVersion(moduleBuilder: HelidonModuleBuilder): JavaVersion {
    val method = HelidonModuleBuilder::class.java.getDeclaredMethod("getMinJavaVersion")
    method.isAccessible = true
    return method.invoke(moduleBuilder) as JavaVersion
  }

}
