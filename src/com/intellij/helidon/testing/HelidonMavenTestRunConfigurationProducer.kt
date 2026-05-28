// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.testing

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import org.jetbrains.idea.maven.execution.MavenRunConfiguration
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType

class HelidonMavenTestRunConfigurationProducer : LazyRunConfigurationProducer<MavenRunConfiguration>() {
  override fun getConfigurationFactory(): ConfigurationFactory =
    MavenRunConfigurationType.getInstance().configurationFactories[0]

  override fun setupConfigurationFromContext(configuration: MavenRunConfiguration,
                                             context: ConfigurationContext,
                                             sourceElement: Ref<PsiElement>): Boolean {
    val target = HelidonTestTargetResolver.resolve(context) ?: return false
    configure(configuration, target)
    sourceElement.set(target.sourceElement)
    return true
  }

  override fun isConfigurationFromContext(configuration: MavenRunConfiguration,
                                          context: ConfigurationContext): Boolean {
    val target = HelidonTestTargetResolver.resolve(context) ?: return false
    val parameters = configuration.runnerParameters
    return parameters.workingDirPath == target.workingDirectory &&
      parameters.pomFileName == (target.pomFile?.name ?: "pom.xml") &&
      parameters.goals == target.goals
  }

  private fun configure(configuration: MavenRunConfiguration, target: HelidonMavenTestTarget) {
    configuration.name = target.configurationName
    configuration.setRunnerParameters(target.createRunnerParameters())
  }
}
