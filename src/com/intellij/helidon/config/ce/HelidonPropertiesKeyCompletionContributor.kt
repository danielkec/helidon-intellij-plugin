// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.properties.psi.Property
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.codeStyle.PropertiesCodeStyleSettings
import com.intellij.lang.properties.psi.impl.PropertyKeyImpl
import com.intellij.openapi.editor.EditorModificationUtilEx
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.PlatformIcons

internal class HelidonPropertiesKeyCompletionContributor : CompletionContributor() {
  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    if (isMicroservicesPluginEnabled()) return

    val psiFile = parameters.originalFile
    val propertiesFile = psiFile as? PropertiesFile ?: return
    if (!isHelidonApplicationConfigFile(psiFile)) return
    if (PsiTreeUtil.getParentOfType(parameters.position, PropertyKeyImpl::class.java, false) == null) return

    val module = ModuleUtilCore.findModuleForPsiElement(psiFile) ?: return
    val prefix = result.prefixMatcher.prefix
    val resultWithMatcher = result.withPrefixMatcher(HelidonConfigKeyPrefixMatcher(result.prefixMatcher))
    val existingKeys = propertiesFile.properties.mapNotNullTo(HashSet()) { it.name }
    val keys = HelidonConfigKeyService.getInstance().getAllKeys(module)
    val addedKeys = HashSet<String>()

    for (key in keys) {
      val lookupName = HelidonConfigKeyMatcher.bindParameterizedKey(key.name, prefix) ?: continue
      if (!addedKeys.add(lookupName) || lookupName in existingKeys) continue
      if (!resultWithMatcher.prefixMatcher.prefixMatches(lookupName)) continue

      resultWithMatcher.addElement(LookupElementBuilder.create(lookupName)
                                   .withIcon(PlatformIcons.PROPERTY_ICON)
                                   .withTypeText(key.type, true)
                                   .withInsertHandler(INSERT_DELIMITER_HANDLER))
    }
  }

  private companion object {
    private val INSERT_DELIMITER_HANDLER = InsertHandler<LookupElement> { context: InsertionContext, _: LookupElement ->
      val chars = context.document.charsSequence
      val delimiterOffset = getPropertyDelimiterOffset(chars, context.tailOffset)
      if (delimiterOffset != null) {
        context.editor.caretModel.moveToOffset(delimiterOffset + 1)
      }
      else if (hasExistingPropertyValue(context)) {
        context.editor.caretModel.moveToOffset(context.tailOffset)
      }
      else {
        val delimiter = PropertiesCodeStyleSettings.getInstance(context.project).delimiter
        EditorModificationUtilEx.insertStringAtCaret(context.editor, delimiter.toString())
      }
    }
  }
}

internal fun getPropertyDelimiterOffset(chars: CharSequence, offset: Int): Int? {
  if (offset >= chars.length) return null
  if (chars[offset] in PROPERTY_KEY_VALUE_DELIMITERS) return offset
  if (!chars[offset].isPropertyHorizontalWhitespace()) return null

  var index = offset
  while (index < chars.length && chars[index].isPropertyHorizontalWhitespace()) {
    index++
  }
  if (index < chars.length && chars[index] in PROPERTY_KEY_VALUE_DELIMITERS) return index
  return index - 1
}

private fun hasExistingPropertyValue(context: InsertionContext): Boolean {
  val element = context.file.findElementAt(context.startOffset) ?: return false
  val property = PsiTreeUtil.getParentOfType(element, Property::class.java, false) ?: return false
  return property.value != null
}

private val PROPERTY_KEY_VALUE_DELIMITERS = setOf('=', ':')

private fun Char.isPropertyHorizontalWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\u000C'
