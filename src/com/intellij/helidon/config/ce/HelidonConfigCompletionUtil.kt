// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.TestOnly

private const val MICROSERVICES_BEANS_PROVIDER_EP = "com.intellij.microservices.jvm.beansProvider"
private var microservicesPluginEnabledOverride: Boolean? = null

internal fun isMicroservicesPluginEnabled(): Boolean {
  microservicesPluginEnabledOverride?.let { return it }
  return ApplicationManager.getApplication().extensionArea.hasExtensionPoint(MICROSERVICES_BEANS_PROVIDER_EP)
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
