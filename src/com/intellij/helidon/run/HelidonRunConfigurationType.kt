// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.helidon.HelidonIcons
import com.intellij.openapi.project.Project

class HelidonRunConfigurationType : ConfigurationTypeBase(
  ID,
  "Helidon",
  "Helidon application",
  HelidonIcons.Helidon
) {
  init {
    addFactory(HelidonRunConfigurationFactory(this))
  }

  companion object {
    const val ID: String = "HelidonRunConfiguration"

    fun getInstance(): HelidonRunConfigurationType =
      ConfigurationTypeUtil.findConfigurationType(HelidonRunConfigurationType::class.java)
  }
}

private class HelidonRunConfigurationFactory(type: HelidonRunConfigurationType) : ConfigurationFactory(type) {
  override fun getId(): String = HelidonRunConfigurationType.ID

  override fun getName(): String = "Helidon"

  override fun createTemplateConfiguration(project: Project): HelidonRunConfiguration =
    HelidonRunConfiguration("Helidon", project, this)
}
