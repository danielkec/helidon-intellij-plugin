// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.TestOnly

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
  return com.intellij.helidon.config.isHelidonApplicationConfigFile(file)
}
