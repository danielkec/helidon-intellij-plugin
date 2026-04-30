// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.helidon.config.HelidonMetaConfigKeyManager
import com.intellij.microservices.jvm.config.ConfigKeyDocumentationProviderBase
import com.intellij.microservices.jvm.config.MetaConfigKeyManager
import com.intellij.microservices.jvm.config.MicroservicesConfigBundle
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLKeyValue

internal class HelidonYamlDocumentationProvider : ConfigKeyDocumentationProviderBase() {
  override fun getConfigManager(): MetaConfigKeyManager = HelidonMetaConfigKeyManager.getInstance()

  override fun getQuickNavigateInfo(element: PsiElement, originalElement: PsiElement?): String? {
    if (element is YAMLKeyValue && isInsideApplicationYamlFile(element)) {
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
    val yamlKeyValue = when {
      element.node.elementType == YAMLTokenTypes.SCALAR_KEY -> PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)
      element is YAMLKeyValue -> element
      else -> null
    }
    if (yamlKeyValue == null || !isInsideApplicationYamlFile(yamlKeyValue)) {
      return null
    }
    return getQualifiedConfigKeyName(yamlKeyValue)
  }
}
