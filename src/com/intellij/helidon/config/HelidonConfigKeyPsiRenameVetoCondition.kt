// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.lang.properties.psi.PropertiesElementFactory
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement

internal class HelidonConfigKeyPsiRenameVetoCondition : Condition<PsiElement> {
  override fun value(psiElement: PsiElement): Boolean {
    return psiElement is HelidonConfigKeyDeclarationPsiElement || isSystemProperty(psiElement)
  }

  private fun isSystemProperty(psiElement: PsiElement): Boolean {
    val property = psiElement as? Property ?: return false
    return property.propertiesFile == PropertiesElementFactory.getSystemProperties(psiElement.project)
  }
}
