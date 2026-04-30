// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.newproject

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.helidon.constants.HelidonConstants
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HelidonRunConfigurationServiceTest : LightJavaCodeInsightFixtureTestCase4(LightJavaCodeInsightFixtureTestCase.JAVA_21) {
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
}
