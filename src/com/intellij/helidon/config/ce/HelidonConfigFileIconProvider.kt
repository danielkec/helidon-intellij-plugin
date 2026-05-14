// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.helidon.HelidonIcons
import com.intellij.ide.IconProvider
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLFile
import javax.swing.Icon

internal class HelidonConfigFileIconProvider : IconProvider() {
  override fun getIcon(element: PsiElement, @Iconable.IconFlags flags: Int): Icon? {
    if (isMicroservicesPluginEnabled()) return null
    if (element !is YAMLFile && element !is PropertiesFile) return null
    return if (isHelidonApplicationConfigFile(element.containingFile)) HelidonIcons.Helidon else null
  }
}
