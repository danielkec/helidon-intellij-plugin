// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j

import com.intellij.lang.java.JavaLanguage
import com.intellij.patterns.uast.literalExpression
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.registerUastReferenceProvider
import com.intellij.psi.uastReferenceProvider
import org.jetbrains.uast.ULiteralExpression

internal class HelidonLangChain4jJavaReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    registrar.registerUastReferenceProvider(
      literalExpression(),
      uastReferenceProvider { literal: ULiteralExpression, psiElement: PsiElement ->
        if (literal.isString && psiElement.containingFile?.language?.isKindOf(JavaLanguage.INSTANCE) == true) {
          HelidonLangChain4jConfigResolver.annotationValueReferences(psiElement, ElementManipulators.getValueTextRange(psiElement))
        }
        else {
          PsiReference.EMPTY_ARRAY
        }
      })
  }
}
