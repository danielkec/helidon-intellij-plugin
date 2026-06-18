// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.application.AbstractApplicationConfigurationProducer
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.helidon.constants.HelidonConstants
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

class HelidonRunConfigurationProducer : AbstractApplicationConfigurationProducer<HelidonRunConfiguration>(), DumbAware {
  override fun getConfigurationFactory(): ConfigurationFactory =
    HelidonRunConfigurationType.getInstance().configurationFactories[0]

  override fun setupConfigurationFromContext(configuration: HelidonRunConfiguration,
                                             context: ConfigurationContext,
                                             sourceElement: Ref<PsiElement>): Boolean {
    val mainClass = ApplicationConfigurationType.getMainClass(context.psiLocation) ?: return false
    val module = ModuleUtilCore.findModuleForPsiElement(mainClass) ?: context.module ?: return false
    if (!isHelidonModule(module)) {
      return false
    }
    return super.setupConfigurationFromContext(configuration, context, sourceElement)
  }

  override fun isConfigurationFromContext(configuration: HelidonRunConfiguration,
                                          context: ConfigurationContext): Boolean {
    val module = configuration.configurationModule.module ?: context.module ?: return false
    return isHelidonModule(module) && super.isConfigurationFromContext(configuration, context)
  }
}

internal fun isHelidonModule(module: Module): Boolean =
  JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.HELIDON_MAIN) ||
    HelidonCoreUtils.hasHelidonMPLibrary(module)
