// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

internal class HelidonYamlKeyCompletionContributor : CompletionContributor() {
  private val provider = HelidonYamlKeyCompletionProvider()

  init {
    val keyPosition = PlatformPatterns.psiElement()
      .andNot(PlatformPatterns.psiComment())

    extendAt(keyPosition.withSuperParent(2, YAMLMapping::class.java))
    extendAt(keyPosition.withSuperParent(2, YAMLDocument::class.java))
    extendAt(
      keyPosition.withParent(
        PlatformPatterns.psiElement(YAMLScalar::class.java)
          .afterLeaf(PlatformPatterns.psiElement(YAMLTokenTypes.INDENT))))
    extendAt(keyPosition.afterLeaf(PlatformPatterns.psiElement(YAMLTokenTypes.INDENT)))
    extendAt(
      PlatformPatterns.psiElement(LeafPsiElement::class.java)
        .withElementType(YAMLTokenTypes.SCALAR_KEY)
        .withParent(YAMLKeyValue::class.java))
    extendAt(
      PlatformPatterns.psiElement(LeafPsiElement::class.java)
        .withSuperParent(2, YAMLSequenceItem::class.java))
  }

  private fun extendAt(place: PsiElementPattern.Capture<out PsiElement>) {
    extend(CompletionType.BASIC, place.with(APPLICATION_YAML_CONDITION), provider)
  }
}
