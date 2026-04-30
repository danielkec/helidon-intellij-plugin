// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.helidon.config.isHelidonConfigFile
import com.intellij.microservices.jvm.config.MetaConfigKey
import com.intellij.microservices.jvm.config.MetaConfigKeyManager.ConfigKeyNameBinder
import com.intellij.microservices.jvm.config.MicroservicesConfigBundle
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.editor.EditorModificationUtilEx
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.PlatformIcons
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.completion.YamlKeyCompletionInsertHandler
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue
import java.util.function.Function
import java.util.function.Supplier

fun isInsideApplicationYamlFile(psiElement: PsiElement): Boolean {
  val containingFile = psiElement.containingFile ?: return false
  val originalFile = containingFile.originalFile
  return originalFile is YAMLFile && isHelidonConfigFile(originalFile)
}

fun getQualifiedConfigKeyName(yamlKeyValue: YAMLKeyValue?): String {
  val builder = StringBuilder()
  var keyValue = yamlKeyValue
  var insertDot = false
  while (keyValue != null) {
    if (insertDot) {
      builder.insert(0, '.')
    }
    builder.insert(0, getYamlConfigKeyText(keyValue))
    insertDot = true

    var parent = PsiTreeUtil.getParentOfType(keyValue, YAMLKeyValue::class.java, true, YAMLSequenceItem::class.java)
    if (parent == null) {
      val sequenceItem = PsiTreeUtil.getParentOfType(keyValue, YAMLSequenceItem::class.java)
      if (sequenceItem != null) {
        val sequence = PsiTreeUtil.getParentOfType(sequenceItem, YAMLSequence::class.java)
        val index = sequence?.items?.indexOf(sequenceItem) ?: -1
        if (index >= 0) {
          builder.insert(0, "[$index].")
          insertDot = false
        }
        parent = PsiTreeUtil.getParentOfType(keyValue, YAMLKeyValue::class.java)
      }
    }
    keyValue = parent
  }
  return builder.toString()
}

fun getYamlReferenceDisplayText(yamlKeyValue: YAMLKeyValue): String {
  val keyName = getQualifiedConfigKeyName(yamlKeyValue)
  val valueText = getYamlValuePresentationText(yamlKeyValue)
  return if (valueText.isEmpty()) keyName else "$keyName: $valueText"
}

fun getYamlCurrentLineKeyComponents(element: PsiElement,
                                    binder: ConfigKeyNameBinder,
                                    parentQualifiedName: String,
                                    configKeys: List<MetaConfigKey>): List<LookupElement> {
  val elementType = element.node.elementType
  if (elementType != YAMLTokenTypes.SCALAR_KEY && element.parent !is YAMLScalar) {
    return emptyList()
  }

  val parentKeyValue = getYamlParentKeyValue(element, null)
  val value = parentKeyValue?.value
  val elementKeyText = getYamlElementKeyText(element)
  val result = LinkedHashSet<String>()
  val parentKeyComponentCount = if (parentQualifiedName.isEmpty()) 0 else StringUtil.countChars(parentQualifiedName, '.') + 1

  for (configKey in configKeys) {
    if (parentQualifiedName.isNotEmpty() && !binder.matchesPrefix(configKey, parentQualifiedName)) {
      continue
    }
    val parts = StringUtil.split(configKey.name, ".")
    if (parts.size <= parentKeyComponentCount + 1) {
      continue
    }
    val keyPart = parts[parentKeyComponentCount]
    if (elementKeyText != null && !binder.matchesPart(keyPart, elementKeyText)) {
      continue
    }
    if (findYamlChildRelaxed(value, keyPart, binder) == null) {
      result.add(keyPart)
    }
  }

  return result.map {
    LookupElementBuilder.create(it)
      .withIcon(PlatformIcons.PROPERTY_ICON)
      .withInsertHandler(INSERT_COLON_AND_NEW_LINE_INSERT_HANDLER)
  }
}

fun getYamlElementKeyText(element: PsiElement): String? {
  val elementType = element.node.elementType
  if (elementType == YAMLTokenTypes.SCALAR_KEY) {
    val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)
    return keyValue?.keyText
  }
  if (elementType == YAMLTokenTypes.TEXT) {
    return element.text
  }
  if (elementType == YAMLTokenTypes.SCALAR_STRING || elementType == YAMLTokenTypes.SCALAR_DSTRING) {
    val text = element.text
    if (text.length > 2) {
      return text.substring(1, text.length - 1)
    }
  }
  return null
}

fun addYamlCompletionAdvertisement(parameters: CompletionParameters, result: CompletionResultSet) {
  if (parameters.invocationCount <= 1) {
    result.addLookupAdvertisement(
      MicroservicesConfigBundle.message(
        "config.completion.ad",
        KeymapUtil.getFirstKeyboardShortcutText("CodeCompletion")))
  }
}

fun getYamlNumberValueSanitizer(): Function<String, String> = Function { it.replace("_", "") }

fun sanitizeYamlNumberValueIfNeeded(valueText: String, valueTypeSupplier: Supplier<out PsiType?>): String? {
  if (valueText.isEmpty()) {
    return valueText
  }
  val firstChar = valueText[0]
  if (firstChar != '+' && firstChar != '-' && !firstChar.isDigit()) {
    return null
  }

  val psiClass: PsiClass? = PsiTypesUtil.getPsiClass(valueTypeSupplier.get())
  return when (psiClass?.qualifiedName) {
    CommonNumberTypes.JAVA_LANG_INTEGER,
    CommonNumberTypes.JAVA_LANG_LONG,
    CommonNumberTypes.JAVA_LANG_FLOAT,
    CommonNumberTypes.JAVA_LANG_DOUBLE -> getYamlNumberValueSanitizer().apply(valueText)
    else -> null
  }
}

fun getYamlPlaceholderLookupRenderer(): LookupElementRenderer<LookupElement> = YamlPlaceholderLookupRenderer

fun getYamlParentKeyValue(element: PsiElement, originalElement: PsiElement?): YAMLKeyValue? {
  var parentYamlKeyValue = PsiTreeUtil.getParentOfType(originalElement, YAMLKeyValue::class.java)
  if (parentYamlKeyValue == null) {
    parentYamlKeyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)
  }
  if (element.node.elementType == YAMLTokenTypes.SCALAR_KEY) {
    parentYamlKeyValue = PsiTreeUtil.getParentOfType(parentYamlKeyValue, YAMLKeyValue::class.java)
  }
  return parentYamlKeyValue
}

private fun getYamlConfigKeyText(yamlKeyValue: YAMLKeyValue): String {
  val keyText = yamlKeyValue.keyText
  return if (keyText.contains('.')) yamlKeyValue.key?.text ?: keyText else keyText
}

private fun getYamlValuePresentationText(yamlKeyValue: YAMLKeyValue): String {
  val value = yamlKeyValue.value
  if (value is YAMLScalar) {
    return value.textValue
  }
  if (value is YAMLSequence) {
    val items = value.items
    val suffix = if (items.size > 2) ", [...]" else ""
    return "[" + ContainerUtil.getFirstItems(items, 2).joinToString(",") { getYamlSequenceItemPresentationText(it) } + suffix + "]"
  }
  return ""
}

private fun getYamlSequenceItemPresentationText(sequenceItem: YAMLSequenceItem): String {
  val value = sequenceItem.value
  return if (value is YAMLScalar) value.textValue else getYamlSequenceItemMappingPresentationText(sequenceItem)
}

private fun getYamlValuePresentationText(sequenceItem: YAMLSequenceItem): String {
  val value = sequenceItem.value
  return if (value is YAMLScalar) value.textValue else value?.text ?: ""
}

private fun getYamlSequenceItemMappingPresentationText(sequenceItem: YAMLSequenceItem): String {
  val keys = sequenceItem.keysValues.take(2)
  val suffix = if (sequenceItem.keysValues.size > 2) ", [...]" else ""
  return "[" + keys.joinToString(",") { "${it.keyText}: ${getYamlValuePresentationText(it)}" } + suffix + "]"
}

private fun findYamlChildRelaxed(value: YAMLValue?, subKey: String, binder: ConfigKeyNameBinder): YAMLKeyValue? {
  val mapping = value as? YAMLMapping ?: return null
  mapping.getKeyValueByKey(subKey)?.let { return it }
  return mapping.keyValues.firstOrNull { keyValue ->
    val name = keyValue.name
    name != null && binder.matchesPart(subKey, name)
  }
}

private object YamlPlaceholderLookupRenderer : LookupElementRenderer<LookupElement>() {
  override fun renderElement(element: LookupElement, presentation: LookupElementPresentation) {
    val obj = element.`object`
    if (obj is YAMLKeyValue) {
      presentation.itemText = getQualifiedConfigKeyName(obj)
      presentation.typeText = getYamlValuePresentationText(obj).takeIf { it.isNotEmpty() }
    }
    else {
      element.renderElement(presentation)
    }
  }
}

internal val INSERT_COLON_AND_NEW_LINE_INSERT_HANDLER = InsertHandler<LookupElement> { context: InsertionContext, _: LookupElement ->
  val element = context.file.findElementAt(context.startOffset) ?: return@InsertHandler
  val indent = YAMLUtil.getIndentToThisElement(element) + 2
  val newLine = "\n" + StringUtil.repeatSymbol(' ', indent)
  val editor = context.editor
  val text = if (YamlKeyCompletionInsertHandler.isCharAtCaret(editor, ':')) {
    editor.caretModel.moveCaretRelatively(1, 0, false, false, false)
    newLine
  }
  else {
    ":$newLine"
  }
  EditorModificationUtilEx.insertStringAtCaret(editor, text)
  editor.project?.let { AutoPopupController.getInstance(it).scheduleAutoPopup(editor) }
}

private object CommonNumberTypes {
  const val JAVA_LANG_INTEGER = "java.lang.Integer"
  const val JAVA_LANG_LONG = "java.lang.Long"
  const val JAVA_LANG_FLOAT = "java.lang.Float"
  const val JAVA_LANG_DOUBLE = "java.lang.Double"
}
