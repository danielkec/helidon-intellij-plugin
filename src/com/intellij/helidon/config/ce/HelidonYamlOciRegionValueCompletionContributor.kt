// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.helidon.config.HelidonOciConfigOptions
import com.intellij.helidon.config.HelidonOciRegions
import com.intellij.helidon.config.isOciRegionKeyName
import com.intellij.helidon.config.isHelidonOciConfigFile
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.PlatformIcons
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.YAMLElementTypes
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLSequenceItem

internal class HelidonYamlOciRegionValueCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, CE_HELIDON_YAML_VALUE_PATTERN, object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters,
                                  context: ProcessingContext,
                                  result: CompletionResultSet) {
        if (isMicroservicesPluginEnabled()) return

        val keyValue = getCompletedKeyValue(parameters) ?: return
        val file = keyValue.containingFile?.originalFile as? YAMLFile ?: return
        if (!isHelidonOciConfigFile(file)) return

        val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return
        val keyName = qualifiedKeyName(keyValue)
        if (!isOciRegionKeyName(keyName)) return
        val hasMetadataKey = HelidonConfigKeyService.getInstance().getAllKeys(module, file).any { it.name == keyName }
        if (!hasMetadataKey && !HelidonOciConfigOptions.isBuiltInKeyName(keyName)) return

        result.addAllElements(HelidonOciRegions.regionIdentifiers().map {
          LookupElementBuilder.create(it)
            .withIcon(PlatformIcons.PROPERTY_ICON)
            .withTypeText("OCI region", true)
        })
        result.stopHere()
      }
    })
  }

  private fun getCompletedKeyValue(parameters: CompletionParameters): YAMLKeyValue? {
    val originalElement = CompletionUtil.getOriginalElement(parameters.position)
    return PsiTreeUtil.getParentOfType(originalElement, YAMLKeyValue::class.java)
           ?: PsiTreeUtil.getParentOfType(parameters.position, YAMLKeyValue::class.java)
  }

  private fun qualifiedKeyName(yamlKeyValue: YAMLKeyValue): String {
    val parts = ArrayList<String>()
    var current: YAMLKeyValue? = yamlKeyValue
    while (current != null) {
      parts.add(configKeyText(current))
      current = PsiTreeUtil.getParentOfType(current, YAMLKeyValue::class.java, true, YAMLSequenceItem::class.java)
    }
    return parts.asReversed().joinToString(".")
  }

  private fun configKeyText(yamlKeyValue: YAMLKeyValue): String {
    val keyText = yamlKeyValue.keyText
    return if (keyText.contains('.')) yamlKeyValue.key?.text ?: keyText else keyText
  }

  private companion object {
    private val CE_HELIDON_YAML_CONFIG_CONDITION = object : com.intellij.patterns.PatternCondition<PsiElement>("isCeHelidonYamlConfig") {
      override fun accepts(element: PsiElement, context: ProcessingContext?): Boolean {
        if (isMicroservicesPluginEnabled()) return false
        val originalFile = element.containingFile?.originalFile ?: return false
        return originalFile is YAMLFile && com.intellij.helidon.config.isHelidonConfigFile(originalFile)
      }
    }

    private val CE_HELIDON_YAML_VALUE_PATTERN = PlatformPatterns.psiElement(LeafPsiElement::class.java)
      .andOr(PlatformPatterns.psiElement().withElementType(YAMLElementTypes.SCALAR_VALUES)
               .andNot(PlatformPatterns.psiElement().afterLeaf(PlatformPatterns.psiElement(YAMLTokenTypes.INDENT))),
             PlatformPatterns.psiElement().afterLeaf(PlatformPatterns.psiElement(YAMLTokenTypes.COLON)))
      .with(CE_HELIDON_YAML_CONFIG_CONDITION)
  }
}
