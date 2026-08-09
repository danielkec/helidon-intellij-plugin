// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.newproject

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.helidon.constants.HelidonConstants
import com.intellij.helidon.run.HelidonRunConfigurationType
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable
import java.util.function.Consumer

internal data class HelidonRunConfigurationTarget(
  val module: Module,
  val mainClassName: String
)

@Service(Service.Level.PROJECT)
class HelidonRunConfigurationService {
  fun createRunConfigurations(project: Project, onlyForNewProjects: Boolean = true) {
    if (shouldSkipRunConfigurationCreation(project, onlyForNewProjects)) {
      return
    }

    ReadAction.nonBlocking(Callable {
      modulesForRunConfigurations(project)
    })
      .coalesceBy(this)
      .inSmartMode(project)
      .finishOnUiThread(ModalityState.nonModal(), Consumer { targets ->
        runWriteAction {
          createRunConfigurations(project, targets)
        }
      })
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  internal fun createRunConfigurations(project: Project,
                                       targets: Collection<HelidonRunConfigurationTarget>,
                                       onlyForNewProjects: Boolean = true) {
    if (shouldSkipRunConfigurationCreation(project, onlyForNewProjects)) {
      return
    }

    ReadAction.nonBlocking(Callable {
      targets.filter { !it.module.isDisposed && !it.module.name.endsWith(".test") }
    })
      .coalesceBy(this)
      .inSmartMode(project)
      .finishOnUiThread(ModalityState.nonModal(), Consumer { validTargets ->
        runWriteAction {
          createRunConfigurations(project, validTargets)
        }
      })
      .submit(AppExecutorUtil.getAppExecutorService())
  }

  internal fun modulesForRunConfigurations(project: Project): List<HelidonRunConfigurationTarget> {
    val existingRunConfigurations = existingRunConfigurationKeys(project)

    return ModuleManager.getInstance(project).modules.asSequence()
      .filter { !it.name.endsWith(".test") }
      .mapNotNull(::defaultRunConfigurationTarget)
      .filterNot { it.key() in existingRunConfigurations }
      .toList()
  }

  private fun createRunConfigurations(project: Project, targets: Collection<HelidonRunConfigurationTarget>) {
    ApplicationManager.getApplication().assertWriteAccessAllowed()
    val existingRunConfigurations = existingRunConfigurationKeys(project).toMutableSet()
    for (target in targets) {
      createRunConfiguration(target, existingRunConfigurations)
    }
  }

  private fun existingRunConfigurationKeys(project: Project): Set<RunConfigurationKey> =
    RunManager.getInstance(project)
      .allSettings
      .asSequence()
      .map { it.configuration }
      .filterIsInstance<ApplicationConfiguration>()
      .mapNotNull {
        val moduleName = it.configurationModule.moduleName
        val mainClassName = it.mainClassName ?: return@mapNotNull null
        RunConfigurationKey(moduleName, normalizedMainClassName(mainClassName))
      }
      .toSet()

  private fun defaultRunConfigurationTarget(module: Module): HelidonRunConfigurationTarget? {
    if (JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.HELIDON_MAIN)) {
      return HelidonRunConfigurationTarget(module, HelidonConstants.HELIDON_MAIN)
    }
    if (HelidonCoreUtils.hasHelidonMPLibrary(module)) {
      return HelidonRunConfigurationTarget(module, HelidonConstants.MP_MAIN)
    }
    return null
  }

  private fun isNewProject(project: Project): Boolean {
    return project.getUserData(NEW_HELIDON_PROJECT_KEY) == java.lang.Boolean.TRUE
  }

  private fun shouldSkipRunConfigurationCreation(project: Project, onlyForNewProjects: Boolean): Boolean {
    val application = ApplicationManager.getApplication()
    if (application.isHeadlessEnvironment || application.isUnitTestMode) {
      return true
    }

    if (onlyForNewProjects && !isNewProject(project)) {
      // By default, create run configurations only for newly created projects.
      return true
    }

    return false
  }

  internal fun createMicroProfileRunConfiguration(module: Module) {
    createRunConfiguration(module, HelidonConstants.MP_MAIN)
  }

  internal fun createRunConfiguration(module: Module, mainClassName: String) {
    ApplicationManager.getApplication().assertWriteAccessAllowed()
    createRunConfiguration(
      HelidonRunConfigurationTarget(module, mainClassName),
      existingRunConfigurationKeys(module.project).toMutableSet()
    )
  }

  private fun createRunConfiguration(target: HelidonRunConfigurationTarget,
                                     existingRunConfigurations: MutableSet<RunConfigurationKey>) {
    val module = target.module
    if (!existingRunConfigurations.add(target.key())) {
      return
    }

    val runManager = RunManager.getInstance(module.project)
    try {
      val settings = runManager.createConfiguration("", HelidonRunConfigurationType.getInstance().configurationFactories[0])
      val newRunConfig = settings.configuration as ApplicationConfiguration
      newRunConfig.setModule(module)
      newRunConfig.mainClassName = target.mainClassName
      settings.name = module.name
      newRunConfig.setGeneratedName()
      runManager.setUniqueNameIfNeeded(settings)
      runManager.addConfiguration(settings)
      if (runManager.allSettings.size == 1) {
        runManager.selectedConfiguration = settings
      }
    }
    catch (e: ProcessCanceledException) {
      //reattempt to create run configuration
      throw e
    }
    catch (t: Throwable) {
      logger<HelidonRunConfigurationService>().error("Error creating Helidon run configuration for module ${module.name}", t)
    }
  }

  private fun HelidonRunConfigurationTarget.key(): RunConfigurationKey =
    RunConfigurationKey(module.name, normalizedMainClassName(mainClassName))

  private fun normalizedMainClassName(mainClassName: String): String =
    if (mainClassName == HelidonConstants.HELIDON_MAIN || mainClassName == HelidonConstants.MP_MAIN) {
      HelidonConstants.HELIDON_MAIN
    }
    else {
      mainClassName
    }

  private data class RunConfigurationKey(
    val moduleName: String,
    val mainClassName: String
  )
}
