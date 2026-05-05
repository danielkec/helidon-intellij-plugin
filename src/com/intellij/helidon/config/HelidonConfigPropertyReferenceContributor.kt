// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PsiJavaPatterns.psiClass
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.uast.callExpression
import com.intellij.patterns.uast.literalExpression
import com.intellij.psi.ElementManipulators
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.getUastParentOfType

internal class HelidonConfigPropertyReferenceContributor : PsiReferenceContributor() {

  private val configGetCallArgumentPattern: ElementPattern<ULiteralExpression> =
    literalExpression().inCall(
      callExpression()
        .withMethodName(HELIDON_CONFIG_GET_METHOD)
        .withReceiver(psiClass().withQualifiedName(HELIDON_CONFIG_FQN)))

  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    registrar.registerUastReferenceProvider(
      configGetCallArgumentPattern,
      uastReferenceProvider { literal: ULiteralExpression, psiElement: PsiElement ->
        if (literal.isString)
          arrayOf(HelidonConfigPlaceholderReference.Builder(psiElement,
                                                            ElementManipulators.getValueTextRange(psiElement),
                                                            false)
                    .build())
        else
          PsiReference.EMPTY_ARRAY
      })
  }
}

internal class HelidonKotlinConfigPropertyReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    registrar.registerReferenceProvider(
      PlatformPatterns.psiElement().with(KOTLIN_CONFIG_GET_STRING_ARGUMENT),
      object : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
          return arrayOf(HelidonConfigPlaceholderReference.Builder(element,
                                                                  ElementManipulators.getValueTextRange(element),
                                                                  false)
                           .build())
        }
      })
  }

  companion object {
    private val KOTLIN_CONFIG_GET_STRING_ARGUMENT = object : PatternCondition<PsiElement>("kotlinConfigGetStringArgument") {
      override fun accepts(element: PsiElement, context: ProcessingContext): Boolean {
        if (element.javaClass.name != "org.jetbrains.kotlin.psi.KtStringTemplateExpression") return false
        if (!isPlainKotlinStringTemplate(element)) return false
        val callExpression = element.getUastParentOfType<UCallExpression>()
        return callExpression?.methodName == HELIDON_CONFIG_GET_METHOD &&
               callExpression.resolve()?.containingClass?.qualifiedName == HELIDON_CONFIG_FQN
      }
    }

    private fun isPlainKotlinStringTemplate(element: PsiElement): Boolean {
      val entries = (element.javaClass.methods
        .singleOrNull { it.name == "getEntries" && it.parameterCount == 0 }
        ?.invoke(element) as? Array<*>) ?: return false
      return entries.all {
        when (it?.javaClass?.name) {
          "org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry",
          "org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry" -> true
          else -> false
        }
      }
    }
  }
}
