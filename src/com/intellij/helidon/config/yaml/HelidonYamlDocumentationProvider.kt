// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.helidon.config.HelidonMetaConfigKeyManager
import com.intellij.microservices.jvm.config.ConfigKeyDocumentationProviderBase
import com.intellij.microservices.jvm.config.MetaConfigKey
import com.intellij.microservices.jvm.config.MetaConfigKeyManager
import com.intellij.microservices.jvm.config.MetaConfigKeyReference
import com.intellij.microservices.jvm.config.MicroservicesConfigBundle
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLKeyValue

internal class HelidonYamlDocumentationProvider : ConfigKeyDocumentationProviderBase() {
  override fun getConfigManager(): MetaConfigKeyManager = HelidonMetaConfigKeyManager.getInstance()

  override fun getQuickNavigateInfo(element: PsiElement, originalElement: PsiElement?): String? {
    if (element is YAMLKeyValue &&
        isInsideHelidonYamlConfigFile(element) &&
        MetaConfigKeyReference.getResolvedMetaConfigKey(element) != null) {
      val valueText = element.valueText
      val file = element.containingFile
      return if (file != null) {
        MicroservicesConfigBundle.message("config.key.value.quick.info", valueText, file.name)
      }
      else {
        "\"$valueText\""
      }
    }
    return super.getQuickNavigateInfo(element, originalElement)
  }

  override fun getConfigKey(element: PsiElement): String? {
    val yamlKeyValue = yamlKeyValue(element)
    if (yamlKeyValue == null || !isInsideHelidonYamlConfigFile(yamlKeyValue)) {
      return null
    }
    if (MetaConfigKeyReference.getResolvedMetaConfigKey(yamlKeyValue) == null) {
      return null
    }
    return getQualifiedConfigKeyName(yamlKeyValue)
  }

  override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
    val doc = super.generateDoc(element, originalElement)
    if (doc != null) return doc

    val yamlKeyValue = yamlKeyValue(element) ?: return null
    if (!isInsideHelidonYamlConfigFile(yamlKeyValue)) return null
    val key = MetaConfigKeyReference.getResolvedMetaConfigKey(yamlKeyValue) ?: return null
    return renderConfigKeyDoc(key)
  }

  private fun yamlKeyValue(element: PsiElement): YAMLKeyValue? {
    return when {
      element.node.elementType == YAMLTokenTypes.SCALAR_KEY -> PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)
      element is YAMLKeyValue -> element
      else -> null
    }
  }

  private fun renderConfigKeyDoc(key: MetaConfigKey): String {
    val builder = StringBuilder()
    builder.append("<div class='definition'><pre><b>")
      .append(StringUtil.escapeXmlEntities(key.name))
      .append("</b>")
    key.type?.let {
      builder.append("<br>")
        .append(StringUtil.escapeXmlEntities(it.presentableText))
    }
    builder.append("</pre></div>")

    val description = key.descriptionText.fullText
    if (description.isNotEmpty()) {
      builder.append("<div class='content'>")
        .append(StringUtil.escapeXmlEntities(description))
        .append("</div>")
    }

    key.defaultValue?.let {
      builder.append("<table class='sections'><tr><td valign='top' class='section'><p>Default:</p></td><td valign='top'><pre>")
        .append(StringUtil.escapeXmlEntities(it))
        .append("</pre></td></tr></table>")
    }
    return builder.toString()
  }
}
