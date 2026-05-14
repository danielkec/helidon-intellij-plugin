// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.TestOnly

private const val APPLICATION_PREFIX = "application"
private const val APPLICATION_ENV_SPECIFIC_PREFIX = "$APPLICATION_PREFIX-"
private const val MICROPROFILE_CONFIG = "microprofile-config"
private val MICROSERVICES_PLUGIN_ID = PluginId.getId("com.intellij.microservices.jvm")
private var microservicesPluginEnabledOverride: Boolean? = null

@Suppress("DEPRECATION")
internal fun isMicroservicesPluginEnabled(): Boolean {
  microservicesPluginEnabledOverride?.let { return it }
  return PluginManagerCore.getPlugin(MICROSERVICES_PLUGIN_ID)?.isEnabled == true
}

@TestOnly
internal fun <T> withMicroservicesPluginEnabled(enabled: Boolean, action: () -> T): T {
  val oldValue = microservicesPluginEnabledOverride
  microservicesPluginEnabledOverride = enabled
  try {
    return action()
  }
  finally {
    microservicesPluginEnabledOverride = oldValue
  }
}

internal fun isHelidonApplicationConfigFile(file: PsiFile): Boolean {
  val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return false
  if (!HelidonCommonUtils.hasHelidonLibrary(module)) return false
  val virtualFile = file.virtualFile ?: return false

  val sourceRoots = ModuleRootManager.getInstance(module).sourceRoots.toSet()
  return (virtualFile.nameWithoutExtension == APPLICATION_PREFIX ||
          virtualFile.nameWithoutExtension == MICROPROFILE_CONFIG ||
          virtualFile.nameWithoutExtension.startsWith(APPLICATION_ENV_SPECIFIC_PREFIX)) &&
         VfsUtilCore.isUnder(virtualFile, sourceRoots)
}
