// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class HelidonPluginDescriptorTest {

  @Test
  fun testMainDescriptorDoesNotRequireUltimateOrMicroservices() {
    val document = parseDescriptor(Path.of("resources/META-INF/plugin.xml"))
    val hardDependencies = document.getElementsByTagName("dependencies").item(0) as Element
    val pluginIds = hardDependencies.getElementsByTagName("plugin").attributes("id")

    assertFalse(pluginIds.contains("com.intellij.modules.ultimate"))
    assertFalse(pluginIds.contains("com.intellij.microservices.jvm"))

    val optionalMicroservices = document.getElementsByTagName("depends").elements()
      .any { element ->
        element.textContent.trim() == "com.intellij.microservices.jvm" &&
          element.getAttribute("optional") == "true" &&
          element.getAttribute("config-file") == "helidon-microservices.xml"
      }

    assertTrue(optionalMicroservices)
  }

  @Test
  fun testDescriptionDocumentsOptionalMicroservicesFeatures() {
    val description = parseDescriptor(Path.of("resources/META-INF/plugin.xml"))
      .getElementsByTagName("description")
      .item(0)
      .textContent
      .replace(Regex("\\s+"), " ")
      .trim()

    listOf(
      "Endpoints tool window",
      "URL resolver",
      "declarative HTTP endpoint",
      "Helidon configuration",
      "IntelliJ IDEA Ultimate",
      "Microservices integration",
    ).forEach { expected ->
      assertTrue("Description should mention $expected", description.contains(expected))
    }
  }

  @Test
  fun testInternalMicroservicesYamlConfigModuleIsNotDeclared() {
    val document = parseDescriptor(Path.of("resources/META-INF/plugin.xml"))
    val dependencies = document.getElementsByTagName("dependencies").item(0) as Element
    val moduleNames = dependencies.getElementsByTagName("module").attributes("name")

    assertFalse(moduleNames.contains("intellij.microservices.jvm.config.yaml"))
  }

  @Test
  fun testMicroservicesSubDescriptorDoesNotDeclareDependencies() {
    val document = parseDescriptor(Path.of("resources/META-INF/helidon-microservices.xml"))

    assertFalse(document.getElementsByTagName("dependencies").elements().isNotEmpty())
  }

  @Test
  fun testDescriptorsDoNotRegisterKotlinSourceLanguageSupport() {
    listOf(
      Path.of("resources/META-INF/plugin.xml"),
      Path.of("resources/META-INF/helidon-microservices.xml"),
    ).forEach { path ->
      val elements = parseDescriptor(path)
        .getElementsByTagName("*")
        .elements()

      assertFalse("$path should not register Kotlin source-language extensions",
                  elements.any { element ->
                    element.getAttribute("language").equals("kotlin", ignoreCase = true) ||
                      element.getAttribute("implementation") ==
                      "com.intellij.helidon.config.HelidonKotlinConfigPropertyReferenceContributor"
                  })
    }
  }

  @Test
  fun testInternalMicroservicesYamlConfigPackageIsNotUsed() {
    val internalPackage = "com.intellij.microservices.jvm.config.yaml"
    val sourceUsesInternalPackage = Files.walk(Path.of("src/com/intellij/helidon/config/yaml")).use { paths ->
      paths
        .filter { it.toString().endsWith(".kt") }
        .anyMatch { Files.readString(it).contains(internalPackage) }
    }

    assertFalse(sourceUsesInternalPackage)
  }

  private fun parseDescriptor(path: Path) = DocumentBuilderFactory.newInstance()
    .newDocumentBuilder()
    .parse(path.toFile())

  private fun NodeList.elements(): List<Element> = (0 until length)
    .map { item(it) as Element }

  private fun NodeList.attributes(name: String): List<String> = elements()
    .map { it.getAttribute(name) }
}
