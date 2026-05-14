// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.helidon.langchain4j.HelidonLangChain4jConfigResolver
import com.intellij.util.PlatformIcons
import com.intellij.util.ProcessingContext

internal class HelidonYamlValueCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, HELIDON_YAML_VALUE_PATTERN, object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters,
                                  context: ProcessingContext,
                                  result: CompletionResultSet) {
        val element = CompletionUtil.getOriginalElement(parameters.position) ?: parameters.position
        val variants = HelidonLangChain4jConfigResolver.valueCompletionVariants(element)
        if (variants.isEmpty()) return

        result.addAllElements(variants.map {
          LookupElementBuilder.create(it)
            .withIcon(PlatformIcons.PROPERTY_ICON)
        })
        result.stopHere()
      }
    })
  }
}
