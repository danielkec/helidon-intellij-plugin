// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.helidon.config.HelidonMetaConfigKeyManager
import com.intellij.helidon.config.HelidonOciConfigOptions
import com.intellij.helidon.config.HelidonOciRegions
import com.intellij.helidon.config.isHelidonOciConfigFile
import com.intellij.helidon.config.isOciRegionKeyName
import com.intellij.helidon.langchain4j.HelidonLangChain4jConfigResolver
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.util.PlatformIcons
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

internal class HelidonYamlValueCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, HELIDON_YAML_VALUE_PATTERN, object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters,
                                  context: ProcessingContext,
                                  result: CompletionResultSet) {
        val element = CompletionUtil.getOriginalElement(parameters.position) ?: parameters.position
        if (addOciRegionCompletions(parameters, result)) return

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

  private fun addOciRegionCompletions(parameters: CompletionParameters, result: CompletionResultSet): Boolean {
    val element = CompletionUtil.getOriginalElement(parameters.position) ?: parameters.position
    val keyValue = getYamlParentKeyValue(parameters.position, element) ?: return false
    val file = keyValue.containingFile?.originalFile as? YAMLFile ?: return false
    if (!isHelidonOciConfigFile(file)) return false

    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return false
    val keyName = getQualifiedConfigKeyName(keyValue)
    if (!isOciRegionKeyName(keyName)) return false
    val hasMetadataKey = HelidonMetaConfigKeyManager.getInstance().getMetaConfigKeys(module, file).any { it.name == keyName }
    if (!hasMetadataKey && !HelidonOciConfigOptions.isBuiltInKeyName(keyName)) return false

    result.addAllElements(HelidonOciRegions.regionIdentifiers().map {
      LookupElementBuilder.create(it)
        .withIcon(PlatformIcons.PROPERTY_ICON)
        .withTypeText("OCI region", true)
    })
    result.stopHere()
    return true
  }
}
