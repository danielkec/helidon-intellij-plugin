// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.templates

import com.intellij.helidon.HelidonIcons
import com.intellij.ide.fileTemplates.actions.AttributesDefaults
import com.intellij.openapi.actionSystem.DataContext
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.helpers.NOPLogger
import java.io.StringWriter
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
    assertTrue("Declarative HTTP template should generate an in-memory greeting bean",
               text.contains("private volatile String greeting = \"Hello from Helidon\";"))
    assertTrue("Declarative HTTP template should expose an editable endpoint path",
               text.contains("\${$HELIDON_ENDPOINT_PATH_ATTRIBUTE}"))
    assertTrue("Declarative HTTP template should fall back to the default endpoint path",
               text.contains("#else$HELIDON_ENDPOINT_PATH_DEFAULT#end"))
    assertFalse("Declarative HTTP endpoint interfaces are ignored by Helidon codegen",
                text.contains("public interface \${NAME}"))
    listOf(
      "@Http.GET",
      "@Http.POST",
      "@Http.PUT",
      "@Http.DELETE",
      "@Http.Entity String greeting",
      "@RestServer.Status(Status.CREATED_201_CODE)",
      "@RestServer.Status(Status.NO_CONTENT_204_CODE)",
    ).forEach { expected ->
      assertTrue("Declarative HTTP template should contain $expected", text.contains(expected))
    }
  }

  @Test
  fun declarativeHttpActionProvidesEndpointPathDefault() {
    val defaults = templateAttributesDefaults(HelidonCreateDeclarativeHttpServiceAction())
      ?: error("Declarative HTTP action should provide endpoint path defaults")

    assertFalse(defaults.isFixedName)
    assertEquals(HELIDON_ENDPOINT_PATH_DEFAULT, defaults.getDefaultValueFor(HELIDON_ENDPOINT_PATH_ATTRIBUTE))
    assertEquals("Endpoint path", defaults.attributeVisibleNames?.get(HELIDON_ENDPOINT_PATH_ATTRIBUTE))
    assertFalse(defaults.defaultProperties?.containsKey(HELIDON_ENDPOINT_PATH_ATTRIBUTE) ?: false)
  }

  @Test
  fun declarativeHttpEndpointPathTemplateRendersWithDefaultAndEnteredValue() {
    val snippet = "@Path(\"#if (\${$HELIDON_ENDPOINT_PATH_ATTRIBUTE} && " +
      "\${$HELIDON_ENDPOINT_PATH_ATTRIBUTE} != '')\${$HELIDON_ENDPOINT_PATH_ATTRIBUTE}" +
      "#else$HELIDON_ENDPOINT_PATH_DEFAULT#end\")"

    assertEquals("@Path(\"$HELIDON_ENDPOINT_PATH_DEFAULT\")", renderVelocity(snippet))
    assertEquals("@Path(\"/hello\")", renderVelocity(snippet, mapOf(HELIDON_ENDPOINT_PATH_ATTRIBUTE to "/hello")))
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

  private fun renderVelocity(template: String, values: Map<String, String> = emptyMap()): String {
    val engine = VelocityEngine()
    engine.setProperty("runtime.log.instance", NOPLogger.NOP_LOGGER)
    engine.init()
    val context = VelocityContext()
    values.forEach { (key, value) -> context.put(key, value) }
    val writer = StringWriter()
    assertTrue(engine.evaluate(context, writer, "", template))
    return writer.toString()
  }

  private fun templateAttributesDefaults(action: HelidonCreateFileFromTemplateAction): AttributesDefaults? {
    val method = HelidonCreateFileFromTemplateAction::class.java.getDeclaredMethod(
      "getAttributesDefaults",
      DataContext::class.java,
    )
    method.isAccessible = true
    return method.invoke(action, DataContext { null }) as AttributesDefaults?
  }
}
