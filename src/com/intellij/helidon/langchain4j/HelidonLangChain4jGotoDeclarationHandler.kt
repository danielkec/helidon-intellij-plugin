// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

internal class HelidonLangChain4jGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(sourceElement: PsiElement?,
                                         offset: Int,
                                         editor: Editor?): Array<PsiElement>? {
    val identifier = sourceElement as? PsiIdentifier ?: return null
    val reference = PsiTreeUtil.getParentOfType(identifier, PsiReferenceExpression::class.java, false) ?: return null
    if (reference.referenceNameElement != identifier) return null

    val variable = reference.resolve() as? PsiVariable ?: return null
    val configTargets = HelidonLangChain4jConfigResolver.annotationValueTargets(reference)
    if (configTargets.isEmpty()) return null

    return (sequenceOf(variable) + configTargets.asSequence())
      .distinct()
      .toList()
      .toTypedArray()
  }
}
