// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue

private fun findKeyValue(psiElement: PsiElement): YAMLKeyValue? {
  val keyValue = PsiTreeUtil.getParentOfType(psiElement, YAMLKeyValue::class.java, false) ?: return null
  if (psiElement == keyValue) return keyValue

  val key = keyValue.key ?: return null
  return if (psiElement == key || PsiTreeUtil.isAncestor(key, psiElement, true)) keyValue else null
}

internal class HelidonYamlKeyRenameVetoCondition : Condition<PsiElement> {
  override fun value(psiElement: PsiElement): Boolean {
    val keyValue = findKeyValue(psiElement) ?: return false
    if (!isInsideHelidonYamlConfigFile(keyValue)) return false

    return keyValue.references.any { it is HelidonYamlKeyMetaConfigKeyReference && it.resolvedKey != null }
  }
}
