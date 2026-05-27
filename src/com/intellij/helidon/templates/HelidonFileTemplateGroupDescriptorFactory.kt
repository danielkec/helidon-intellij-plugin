// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.templates

import com.intellij.helidon.HelidonIcons
import com.intellij.ide.fileTemplates.FileTemplateDescriptor
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptor
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptorFactory

internal const val HELIDON_SE_SERVICE_TEMPLATE = "Helidon SE Service.java"
internal const val HELIDON_MP_RESOURCE_TEMPLATE = "Helidon MP Resource.java"
internal const val HELIDON_DECLARATIVE_HTTP_SERVICE_TEMPLATE = "Helidon Declarative HTTP Service.java"
internal const val HELIDON_CONFIG_CLASS_TEMPLATE = "Helidon Config Class.java"
internal const val HELIDON_SERVER_TEST_TEMPLATE = "Helidon Server Test.java"
internal const val HELIDON_LANGCHAIN4J_SERVICE_TEMPLATE = "Helidon LangChain4j Service.java"
internal const val HELIDON_LANGCHAIN4J_AGENT_TEMPLATE = "Helidon LangChain4j Agent.java"

internal val HELIDON_JAVA_FILE_TEMPLATES = listOf(
  HELIDON_SE_SERVICE_TEMPLATE,
  HELIDON_MP_RESOURCE_TEMPLATE,
  HELIDON_DECLARATIVE_HTTP_SERVICE_TEMPLATE,
  HELIDON_CONFIG_CLASS_TEMPLATE,
  HELIDON_SERVER_TEST_TEMPLATE,
  HELIDON_LANGCHAIN4J_SERVICE_TEMPLATE,
  HELIDON_LANGCHAIN4J_AGENT_TEMPLATE,
)

class HelidonFileTemplateGroupDescriptorFactory : FileTemplateGroupDescriptorFactory {
  override fun getFileTemplatesDescriptor(): FileTemplateGroupDescriptor {
    val group = FileTemplateGroupDescriptor("Helidon", HelidonIcons.Helidon)
    for (template in HELIDON_JAVA_FILE_TEMPLATES) {
      group.addTemplate(FileTemplateDescriptor(template, HelidonIcons.Helidon))
    }
    return group
  }
}
