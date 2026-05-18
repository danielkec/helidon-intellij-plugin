// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j

import com.intellij.lang.java.JavaLanguage
import com.intellij.patterns.uast.injectionHostOrReferenceExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.registerUastReferenceProvider
import com.intellij.psi.uastReferenceProvider
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UExpression

internal class HelidonLangChain4jJavaReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    registrar.registerUastReferenceProvider(
      injectionHostOrReferenceExpression(),
      uastReferenceProvider { expression: UExpression, psiElement: PsiElement ->
        if ((expression as? ULiteralExpression)?.isString == false ||
            psiElement.containingFile?.language?.isKindOf(JavaLanguage.INSTANCE) != true) {
          return@uastReferenceProvider PsiReference.EMPTY_ARRAY
        }
        val range = if (psiElement is PsiLiteralExpression) {
          ElementManipulators.getValueTextRange(psiElement)
        }
        else {
          TextRange.allOf(psiElement.text)
        }
        HelidonLangChain4jConfigResolver.annotationValueReferences(psiElement, range)
      },
      PsiReferenceRegistrar.HIGHER_PRIORITY)
  }
}
