// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue

private fun findKeyValue(psiElement: PsiElement): YAMLKeyValue? {
  if (psiElement is YAMLKeyValue) return psiElement

  if (psiElement is LeafPsiElement) {
    return psiElement.getParent() as? YAMLKeyValue
  }

  return null
}

internal class HelidonYamlKeyRenameVetoCondition : Condition<PsiElement> {
  override fun value(psiElement: PsiElement): Boolean {
    val keyValue = findKeyValue(psiElement) ?: return false
    if (!isInsideApplicationYamlFile(keyValue)) return false

    return keyValue.references.any { it is HelidonYamlKeyMetaConfigKeyReference && it.resolvedKey != null }
  }
}
