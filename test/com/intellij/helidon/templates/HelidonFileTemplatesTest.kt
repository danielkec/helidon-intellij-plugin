// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.templates

import com.intellij.helidon.HelidonIcons
import com.intellij.ide.fileTemplates.actions.AttributesDefaults
import com.intellij.openapi.actionSystem.DataContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class HelidonFileTemplatesTest {
  @Test
  fun templateGroupRegistersAllHelidonTemplates() {
    val descriptor = HelidonFileTemplateGroupDescriptorFactory().fileTemplatesDescriptor
    val templates = descriptor.templates.map { it.fileName }
    val declarativeHttpTemplate = descriptor.templates.single {
      it.fileName == HELIDON_DECLARATIVE_HTTP_SERVICE_TEMPLATE
    }
    val langChain4jServiceTemplate = descriptor.templates.single {
      it.fileName == HELIDON_LANGCHAIN4J_SERVICE_TEMPLATE
    }
    val langChain4jAgentTemplate = descriptor.templates.single {
      it.fileName == HELIDON_LANGCHAIN4J_AGENT_TEMPLATE
    }
    val ociTemplate = descriptor.templates.single { it.fileName == HELIDON_OCI_CONFIG_TEMPLATE }

    assertEquals("Helidon", descriptor.title)
    assertEquals(HELIDON_FILE_TEMPLATES, templates)
    assertSame(HelidonIcons.HelidonGutter, declarativeHttpTemplate.icon)
    assertSame(HelidonIcons.AiGutter, langChain4jServiceTemplate.icon)
    assertSame(HelidonIcons.AiGutter, langChain4jAgentTemplate.icon)
    assertSame(HelidonIcons.Ora, ociTemplate.icon)
  }

  @Test
  fun templateResourcesExistWithDescriptions() {
    for (template in HELIDON_FILE_TEMPLATES) {
      assertTrue("$template template should exist", Files.exists(templatePath(template, "ft")))
      assertTrue("$template description should exist", Files.exists(templatePath(template, "html")))
    }
  }

  @Test
  fun templatesAreJavaOnly() {
    for (template in HELIDON_JAVA_FILE_TEMPLATES) {
      val text = Files.readString(templatePath(template, "ft"))

      assertTrue("$template should define a Java type", text.contains("\${NAME}"))
      assertFalse("$template should not register Kotlin support", text.contains("kotlin", ignoreCase = true))
    }
  }

  @Test
  fun serverTestTemplateUsesHelidonServerTestAndJunit5() {
    val text = Files.readString(templatePath(HELIDON_SERVER_TEST_TEMPLATE, "ft"))

    listOf(
      "@ServerTest",
      "@SetUpRoute",
      "Http1Client",
      "HttpRouting.Builder",
      "org.junit.jupiter.api.Test",
      "assertEquals",
    ).forEach { expected ->
      assertTrue("Server test template should contain $expected", text.contains(expected))
    }
  }

  @Test
  fun langChain4jAgentTemplateUsesRequiredAgentName() {
    val text = Files.readString(templatePath(HELIDON_LANGCHAIN4J_AGENT_TEMPLATE, "ft"))

    assertTrue("LangChain4j agent template should provide an agent name",
               text.contains("@Ai.Agent(\"\${NAME}\")"))
    assertTrue("LangChain4j agent template should define an agentic method",
               text.contains("@Agent(value = \"\${NAME}\", outputKey = \"response\")"))
  }

  @Test
  fun declarativeHttpTemplateGeneratesConcreteEndpointClass() {
    val text = Files.readString(templatePath(HELIDON_DECLARATIVE_HTTP_SERVICE_TEMPLATE, "ft"))

    assertTrue("Declarative HTTP template should generate a concrete class",
               text.contains("public class \${NAME}"))
    assertTrue("Declarative HTTP template should generate a service bean",
               text.contains("@Service.Singleton"))
    assertFalse("Declarative HTTP endpoint interfaces are ignored by Helidon codegen",
                text.contains("public interface \${NAME}"))
    assertTrue("Declarative HTTP template should generate a method body",
               text.contains("return \"Hello from Helidon\";"))
  }

  @Test
  fun ociConfigTemplateCreatesBootstrapConfigSkeleton() {
    val text = Files.readString(templatePath(HELIDON_OCI_CONFIG_TEMPLATE, "ft"))

    assertTrue(text.contains("helidon:"))
    assertTrue(text.contains("oci:"))
    assertTrue(text.contains("authentication-method: \"auto\""))
    assertTrue(text.contains("# OCI config options:"))
    assertTrue(text.contains("# allowed-authentication-methods:"))
    assertTrue(text.contains("oke-workload-identity"))
    assertFalse(text.contains("\"workload\""))
    assertTrue(text.contains("#   session-token:"))
    assertFalse(text.contains("oci-env:"))
    assertFalse(text.contains("oci-secret-service:"))
  }

  @Test
  fun ociConfigActionUsesFixedFileName() {
    val defaults = templateAttributesDefaults(HelidonCreateOciConfigAction())
      ?: error("OCI config action should provide fixed filename defaults")

    assertTrue(defaults.isFixedName)
    assertEquals(HELIDON_OCI_CONFIG_FILE_STEM, defaults.defaultFileName)
  }

  private fun templatePath(template: String, extension: String): Path =
    Path.of("resources/fileTemplates/j2ee/$template.$extension")

  private fun templateAttributesDefaults(action: HelidonCreateFileFromTemplateAction): AttributesDefaults? {
    val method = HelidonCreateFileFromTemplateAction::class.java.getDeclaredMethod(
      "getAttributesDefaults",
      DataContext::class.java,
    )
    method.isAccessible = true
    return method.invoke(action, DataContext { null }) as AttributesDefaults?
  }
}
