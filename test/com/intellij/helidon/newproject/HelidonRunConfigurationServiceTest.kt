// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.newproject

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.helidon.constants.HelidonConstants
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.module.Module
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class HelidonRunConfigurationServiceTest : LightJavaCodeInsightFixtureTestCase4(LightJavaCodeInsightFixtureTestCase.JAVA_21) {
  @Before
  fun clearRunConfigurations() {
    runWriteAction {
      val runManager = RunManager.getInstance(fixture.project)
      runManager.allSettings.forEach { runManager.removeConfiguration(it) }
    }
  }

  @Test
  fun createMicroProfileRunConfigurationIsIdempotentForModule() {
    val service = HelidonRunConfigurationService()

    runWriteAction {
      service.createMicroProfileRunConfiguration(fixture.module)
      service.createMicroProfileRunConfiguration(fixture.module)
    }

    val settings = RunManager.getInstance(fixture.project)
      .getConfigurationSettingsList(ApplicationConfigurationType::class.java)

    assertEquals(1, settings.size)
    val configuration = settings.single().configuration as ApplicationConfiguration
    assertSame(fixture.module, configuration.configurationModule.module)
    assertEquals(HelidonConstants.MP_MAIN, configuration.mainClassName)
  }

  @Test
  fun createMicroProfileRunConfigurationIsModuleSpecific() {
    val service = HelidonRunConfigurationService()
    val secondModule = moduleNamed("second")

    runWriteAction {
      service.createMicroProfileRunConfiguration(fixture.module)
      service.createMicroProfileRunConfiguration(secondModule)
      service.createMicroProfileRunConfiguration(secondModule)
    }

    val settings = RunManager.getInstance(fixture.project)
      .getConfigurationSettingsList(ApplicationConfigurationType::class.java)
    val moduleNames = settings.map { (it.configuration as ApplicationConfiguration).configurationModule.moduleName }

    assertEquals(2, settings.size)
    assertTrue(moduleNames.containsAll(listOf(fixture.module.name, secondModule.name)))
    assertTrue(settings.all { (it.configuration as ApplicationConfiguration).mainClassName == HelidonConstants.MP_MAIN })
  }

  @Test
  fun createRunConfigurationUsesRequestedMainClass() {
    val service = HelidonRunConfigurationService()

    runWriteAction {
      service.createRunConfiguration(fixture.module, "com.example.demo.Main")
    }

    val settings = RunManager.getInstance(fixture.project)
      .getConfigurationSettingsList(ApplicationConfigurationType::class.java)

    assertEquals(1, settings.size)
    val configuration = settings.single().configuration as ApplicationConfiguration
    assertSame(fixture.module, configuration.configurationModule.module)
    assertEquals("com.example.demo.Main", configuration.mainClassName)
  }

  @Test
  fun createRunConfigurationTreatsHelidonMainAndLegacyMpMainAsAliases() {
    val service = HelidonRunConfigurationService()

    runWriteAction {
      service.createRunConfiguration(fixture.module, HelidonConstants.MP_MAIN)
      service.createRunConfiguration(fixture.module, HelidonConstants.HELIDON_MAIN)
    }

    val settings = RunManager.getInstance(fixture.project)
      .getConfigurationSettingsList(ApplicationConfigurationType::class.java)

    assertEquals(1, settings.size)
  }

  private fun moduleNamed(name: String): Module {
    val project = fixture.project
    val moduleTypeName = fixture.module.moduleTypeName
    return Proxy.newProxyInstance(Module::class.java.classLoader, arrayOf(Module::class.java)) { proxy, method, args ->
      when (method.name) {
        "getProject" -> project
        "getName" -> name
        "getModuleTypeName" -> moduleTypeName
        "isDisposed" -> false
        "equals" -> proxy === args?.firstOrNull()
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> name
        else -> throw UnsupportedOperationException(method.name)
      }
    } as Module
  }
}
