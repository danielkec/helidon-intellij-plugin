// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon

import com.intellij.helidon.newproject.HelidonModuleBuilder
import com.intellij.helidon.newproject.HelidonStarterProject
import com.intellij.helidon.newproject.HelidonStarterProjectGeneratorProvider
import com.intellij.helidon.newproject.HelidonStarterRequest
import com.intellij.ide.starters.local.GeneratorFile
import com.intellij.ide.starters.local.StarterModuleBuilder.Companion.setupTestModule
import com.intellij.ide.starters.shared.JAVA_STARTER_LANGUAGE
import com.intellij.ide.starters.shared.MAVEN_PROJECT
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase4
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HelidonModuleBuilderStarterTest : LightJavaCodeInsightFixtureTestCase4(LightJavaCodeInsightFixtureTestCase.JAVA_21) {
  private var capturedRequest: HelidonStarterRequest? = null

  @After
  fun resetStarterGenerator() {
    HelidonStarterProjectGeneratorProvider.generator = com.intellij.helidon.newproject.HelidonStarterClient()
  }

  @Test
  fun generateMavenJavaProjectFromStarter() {
    HelidonStarterProjectGeneratorProvider.generator = { request ->
      capturedRequest = request
      HelidonStarterProject(
        assets = listOf(
          GeneratorFile("pom.xml", "starter pom".toByteArray(StandardCharsets.UTF_8)),
          GeneratorFile("src/main/java/com/examples/myproject/GreetResource.java", "class GreetResource {}".toByteArray(StandardCharsets.UTF_8))
        ),
        filesToOpen = listOf("pom.xml", "src/main/java/com/examples/myproject/GreetResource.java")
      )
    }

    HelidonModuleBuilder().setupTestModule(fixture.module) {
      isCreatingNewProject = true
      language = JAVA_STARTER_LANGUAGE
      projectType = MAVEN_PROJECT
    }

    fixture.configureFromTempProjectFile("pom.xml")
    assertEquals("starter pom", fixture.file.text)
    assertNotNull(fixture.tempDirFixture.getFile("src/main/java/com/examples/myproject/GreetResource.java"))
    assertNull(fixture.tempDirFixture.getFile("src/main/java/com/examples/myproject/HelloResource.java"))
    assertEquals(
      HelidonStarterRequest(
        groupId = "com.example",
        artifactId = "demo",
        projectVersion = "1.0-SNAPSHOT",
        packageName = "com.example.demo"
      ),
      capturedRequest
    )
  }
}
