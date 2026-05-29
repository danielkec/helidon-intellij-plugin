// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.templates

import com.intellij.helidon.HelidonIcons
import com.intellij.ide.fileTemplates.FileTemplateDescriptor
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptor
import com.intellij.ide.fileTemplates.FileTemplateGroupDescriptorFactory

internal const val HELIDON_DECLARATIVE_HTTP_SERVICE_TEMPLATE = "Helidon Declarative HTTP Service.java"
internal const val HELIDON_SERVER_TEST_TEMPLATE = "Helidon Server Test.java"
internal const val HELIDON_LANGCHAIN4J_SERVICE_TEMPLATE = "Helidon LangChain4j Service.java"
internal const val HELIDON_LANGCHAIN4J_AGENT_TEMPLATE = "Helidon LangChain4j Agent.java"
internal const val HELIDON_OCI_CONFIG_FILE_STEM = "oci-config"
internal const val HELIDON_OCI_CONFIG_TEMPLATE = "oci-config.yaml"

internal val HELIDON_JAVA_FILE_TEMPLATES = listOf(
  HELIDON_DECLARATIVE_HTTP_SERVICE_TEMPLATE,
  HELIDON_SERVER_TEST_TEMPLATE,
  HELIDON_LANGCHAIN4J_SERVICE_TEMPLATE,
  HELIDON_LANGCHAIN4J_AGENT_TEMPLATE,
)

internal val HELIDON_FILE_TEMPLATES = HELIDON_JAVA_FILE_TEMPLATES + HELIDON_OCI_CONFIG_TEMPLATE

class HelidonFileTemplateGroupDescriptorFactory : FileTemplateGroupDescriptorFactory {
  override fun getFileTemplatesDescriptor(): FileTemplateGroupDescriptor {
    val group = FileTemplateGroupDescriptor("Helidon", HelidonIcons.Helidon)
    group.addTemplate(FileTemplateDescriptor(
      HELIDON_DECLARATIVE_HTTP_SERVICE_TEMPLATE,
      HelidonIcons.HelidonGutter,
    ))
    group.addTemplate(FileTemplateDescriptor(HELIDON_SERVER_TEST_TEMPLATE, HelidonIcons.Helidon))
    group.addTemplate(FileTemplateDescriptor(HELIDON_LANGCHAIN4J_SERVICE_TEMPLATE, HelidonIcons.AiGutter))
    group.addTemplate(FileTemplateDescriptor(HELIDON_LANGCHAIN4J_AGENT_TEMPLATE, HelidonIcons.AiGutter))
    group.addTemplate(FileTemplateDescriptor(HELIDON_OCI_CONFIG_TEMPLATE, HelidonIcons.Ora))
    return group
  }
}
