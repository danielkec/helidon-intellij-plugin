// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult

internal class HelidonLangChain4jConfigReference(
  element: PsiElement,
  range: TextRange,
  private val resolver: () -> List<PsiElement>,
) : PsiReferenceBase.Poly<PsiElement>(element, range, true) {

  override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult?> {
    return PsiElementResolveResult.createResults(resolver())
  }

  override fun getVariants(): Array<Any> = emptyArray()
}
