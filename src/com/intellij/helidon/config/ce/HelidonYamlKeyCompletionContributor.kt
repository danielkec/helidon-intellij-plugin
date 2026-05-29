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
import com.intellij.helidon.config.YAML_KEY_INSERT_HANDLER
import com.intellij.helidon.config.YAML_SCALAR_KEY_INSERT_HANDLER
import com.intellij.helidon.config.isHelidonConfigFile
import com.intellij.helidon.config.isHelidonOciConfigFile
import com.intellij.helidon.config.isOciRegionKeyName
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.PlatformIcons
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
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
    extendAt(keyPosition.withSuperParent(2, YAMLSequenceItem::class.java))
    extendAt(keyPosition.withSuperParent(3, YAMLSequenceItem::class.java))
  }

  private fun extendAt(place: PsiElementPattern.Capture<out PsiElement>) {
    extend(CompletionType.BASIC, place.with(CE_HELIDON_YAML_CONFIG_CONDITION), provider)
  }

  private class HelidonYamlKeyCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
      if (isMicroservicesPluginEnabled()) return

      val yamlFile = parameters.originalFile as? YAMLFile ?: return
      if (!isHelidonConfigFile(yamlFile)) return

      val module = ModuleUtilCore.findModuleForPsiElement(yamlFile) ?: return
      val element = CompletionUtil.getOriginalElement(parameters.position) ?: parameters.position
      val resultWithMatcher = result.withPrefixMatcher(HelidonConfigKeyPrefixMatcher(result.prefixMatcher))
      val parentKeyValue = getParentKeyValue(element, parameters.offset)
      val parentSequenceItem = getParentSequenceItem(element)
      val parentQualifiedName = getQualifiedConfigKeyName(parentKeyValue, parentSequenceItem)
      val existingKeys = getExistingChildKeys(parentKeyValue, parentSequenceItem, yamlFile)
      val lookupNames = LinkedHashSet<String>()
      val scalarKeyLookupNames = HashSet<String>()

      for (key in HelidonConfigKeyService.getInstance().getAllKeys(module, yamlFile)) {
        val lookupName = HelidonConfigKeyMatcher.childLookupName(key.name, parentQualifiedName) ?: continue
        if (lookupName == "*" || lookupName in existingKeys) continue
        if (!resultWithMatcher.prefixMatcher.prefixMatches(lookupName)) continue
        lookupNames.add(lookupName)
      }
      if (isHelidonOciConfigFile(yamlFile)) {
        val previousParentQualifiedName = getPreviousMappingKeyValue(element, parameters.offset)
          ?.let { getQualifiedConfigKeyName(it, parentSequenceItem) }
        val ociParentQualifiedName = when {
          previousParentQualifiedName == "helidon.oci" ||
          previousParentQualifiedName?.startsWith("helidon.oci.") == true -> previousParentQualifiedName
          previousParentQualifiedName?.startsWith("helidon.oci-") == true -> null
          else -> parentQualifiedName
        }
        if (ociParentQualifiedName != null) {
          for (lookupName in HelidonOciConfigOptions.childLookupNames(ociParentQualifiedName)) {
            if (lookupName in existingKeys) continue
            if (!resultWithMatcher.prefixMatcher.prefixMatches(lookupName)) continue
            lookupNames.add(lookupName)
            if (isOciRegionKeyName("$ociParentQualifiedName.$lookupName")) {
              scalarKeyLookupNames.add(lookupName)
            }
          }
        }
      }

      for (lookupName in lookupNames) {
        val qualifiedLookupName = if (parentQualifiedName.isBlank()) lookupName else "$parentQualifiedName.$lookupName"
        val insertHandler = if (isHelidonOciConfigFile(yamlFile) &&
                                (lookupName in scalarKeyLookupNames || isOciRegionKeyName(qualifiedLookupName))) {
          YAML_SCALAR_KEY_INSERT_HANDLER
        }
        else {
          YAML_KEY_INSERT_HANDLER
        }
        resultWithMatcher.addElement(LookupElementBuilder.create(lookupName)
                                      .withIcon(PlatformIcons.PROPERTY_ICON)
                                      .withInsertHandler(insertHandler))
      }
      if (lookupNames.isNotEmpty()) {
        result.stopHere()
      }
    }

    private fun getParentKeyValue(element: PsiElement, offset: Int): YAMLKeyValue? {
      val parentSequenceItem = getParentSequenceItem(element)
      if (parentSequenceItem != null) {
        val sequenceItemKeyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, true, YAMLSequenceItem::class.java)
        val parentKeyValue = sequenceItemKeyValue?.let {
          PsiTreeUtil.getParentOfType(it, YAMLKeyValue::class.java, true, YAMLSequenceItem::class.java)
        }
        if (sequenceItemKeyValue == null ||
            element.node.elementType == YAMLTokenTypes.SCALAR_KEY && parentKeyValue == null) {
          return getSequenceOwnerKeyValue(parentSequenceItem)
        }
      }

      val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)
      if (element.node.elementType == YAMLTokenTypes.SCALAR_KEY) {
        return PsiTreeUtil.getParentOfType(keyValue, YAMLKeyValue::class.java)
      }

      val mapping = PsiTreeUtil.getParentOfType(element, YAMLMapping::class.java)
      return mapping?.let { PsiTreeUtil.getParentOfType(it, YAMLKeyValue::class.java) }
             ?: keyValue
             ?: getPreviousMappingKeyValue(element, offset)
    }

    private fun getSequenceOwnerKeyValue(parentSequenceItem: YAMLSequenceItem): YAMLKeyValue? {
      val parentSequence = PsiTreeUtil.getParentOfType(parentSequenceItem, YAMLSequence::class.java)
      return PsiTreeUtil.getParentOfType(parentSequence, YAMLKeyValue::class.java)
    }

    private fun getPreviousMappingKeyValue(element: PsiElement, offset: Int): YAMLKeyValue? {
      val file = element.containingFile.originalFile
      val text = file.text
      if (text.isEmpty()) return null

      val safeOffset = offset.coerceIn(0, text.length)
      val currentLineStart = text.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)) + 1
      var previousLineEnd = currentLineStart - 1
      while (previousLineEnd > 0) {
        val previousLineStart = text.lastIndexOf('\n', previousLineEnd - 1) + 1
        val lineText = text.substring(previousLineStart, previousLineEnd).trimEnd('\r')
        val firstNonWhitespace = lineText.indexOfFirst { it != ' ' && it != '\t' }
        if (firstNonWhitespace >= 0) {
          if (lineText.substring(firstNonWhitespace).startsWith("#")) {
            previousLineEnd = previousLineStart - 1
            continue
          }

          val previousLineElement = file.findElementAt(previousLineStart + firstNonWhitespace) ?: return null
          val previousKeyValue = PsiTreeUtil.getParentOfType(previousLineElement, YAMLKeyValue::class.java) ?: return null
          val previousValue = previousKeyValue.value
          return if (previousValue == null || previousValue is YAMLMapping || previousValue is YAMLSequence) previousKeyValue else null
        }
        previousLineEnd = previousLineStart - 1
      }
      return null
    }

    private fun getQualifiedConfigKeyName(yamlKeyValue: YAMLKeyValue?, parentSequenceItem: YAMLSequenceItem?): String {
      val result = ArrayList<String>()
      var current = yamlKeyValue
      while (current != null) {
        result.add(current.keyText)
        current = PsiTreeUtil.getParentOfType(current, YAMLKeyValue::class.java, true, YAMLSequenceItem::class.java)
      }
      val qualifiedName = result.asReversed().joinToString(".")
      if (isListItemParent(yamlKeyValue, parentSequenceItem)) {
        return if (qualifiedName.isBlank()) "*" else "$qualifiedName.*"
      }
      return qualifiedName
    }

    private fun getExistingChildKeys(parentKeyValue: YAMLKeyValue?,
                                     parentSequenceItem: YAMLSequenceItem?,
                                     yamlFile: YAMLFile): Set<String> {
      if (isListItemParent(parentKeyValue, parentSequenceItem)) {
        val mapping = parentSequenceItem?.value as? YAMLMapping ?: return emptySet()
        return mapping.keyValues.mapTo(HashSet()) { it.keyText }
      }
      val mapping = parentKeyValue?.value as? YAMLMapping
                    ?: yamlFile.documents.firstOrNull()?.topLevelValue as? YAMLMapping
                    ?: return emptySet()
      return mapping.keyValues.mapTo(HashSet()) { it.keyText }
    }

    private fun getParentSequenceItem(element: PsiElement): YAMLSequenceItem? {
      return PsiTreeUtil.getParentOfType(element, YAMLSequenceItem::class.java)
    }

    private fun isListItemParent(parentKeyValue: YAMLKeyValue?, parentSequenceItem: YAMLSequenceItem?): Boolean {
      val parentValue = parentKeyValue?.value as? YAMLSequence ?: return false
      return parentSequenceItem != null && PsiTreeUtil.isAncestor(parentValue, parentSequenceItem, false)
    }
  }

  private companion object {
    private val CE_HELIDON_YAML_CONFIG_CONDITION = object : PatternCondition<PsiElement>("isCeHelidonYamlConfig") {
      override fun accepts(element: PsiElement, context: ProcessingContext?): Boolean {
        if (isMicroservicesPluginEnabled()) return false
        val originalFile = element.containingFile?.originalFile ?: return false
        return originalFile is YAMLFile && isHelidonConfigFile(originalFile)
      }
    }
  }
}
