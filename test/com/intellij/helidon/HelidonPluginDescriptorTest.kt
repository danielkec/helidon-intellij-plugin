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
    assertFalse(pluginIds.contains("com.intellij.diagram"))

    val optionalMicroservices = document.getElementsByTagName("depends").elements()
      .any { element ->
        element.textContent.trim() == "com.intellij.microservices.jvm" &&
          element.getAttribute("optional") == "true" &&
          element.getAttribute("config-file") == "helidon-microservices.xml"
      }

    assertTrue(optionalMicroservices)

    val optionalDiagram = document.getElementsByTagName("depends").elements()
      .any { element ->
        element.textContent.trim() == "com.intellij.diagram" &&
          element.getAttribute("optional") == "true" &&
          element.getAttribute("config-file") == "helidon-langchain4j-diagram.xml"
      }

    assertTrue(optionalDiagram)
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
      "Helidon Services tool window",
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
  fun testMainDescriptorRegistersCommunityConfigCompletion() {
    val document = parseDescriptor(Path.of("resources/META-INF/plugin.xml"))
    val completionContributors = document.getElementsByTagName("completion.contributor").elements()

    assertTrue(completionContributors.any { element ->
      element.getAttribute("language") == "Properties" &&
        element.getAttribute("implementationClass") == "com.intellij.helidon.config.ce.HelidonPropertiesKeyCompletionContributor"
    })
    assertTrue(completionContributors.any { element ->
      element.getAttribute("language") == "yaml" &&
        element.getAttribute("implementationClass") == "com.intellij.helidon.config.ce.HelidonYamlKeyCompletionContributor"
    })
  }

  @Test
  fun testMainDescriptorRegistersCommunityConfigFileIconProvider() {
    val document = parseDescriptor(Path.of("resources/META-INF/plugin.xml"))
    val iconProviders = document.getElementsByTagName("iconProvider").elements()

    assertTrue(iconProviders.any { element ->
      element.getAttribute("implementation") == "com.intellij.helidon.config.ce.HelidonConfigFileIconProvider"
    })
  }

  @Test
  fun testMainDescriptorRegistersConfigFileContributors() {
    val document = parseDescriptor(Path.of("resources/META-INF/plugin.xml"))
    val contributors = document.getElementsByTagName("helidon.configFileContributor").elements()

    assertTrue(contributors.any { element ->
      element.getAttribute("id") == "propertiesConfigFileContributor" &&
        element.getAttribute("implementation") == "com.intellij.helidon.config.properties.HelidonPropertiesConfigFileContributor"
    })
    assertTrue(contributors.any { element ->
      element.getAttribute("order") == "after propertiesConfigFileContributor" &&
        element.getAttribute("implementation") == "com.intellij.helidon.config.yaml.HelidonYamlConfigFileContributor"
    })
  }

  @Test
  fun testMainDescriptorRegistersHelidonServicesToolWindow() {
    val document = parseDescriptor(Path.of("resources/META-INF/plugin.xml"))
    val toolWindows = document.getElementsByTagName("toolWindow").elements()
    val extensionPoints = document.getElementsByTagName("extensionPoint").elements()

    assertTrue(toolWindows.any { element ->
      element.getAttribute("id") == "Helidon Services" &&
        element.getAttribute("factoryClass") == "com.intellij.helidon.services.HelidonServicesToolWindowFactory" &&
        element.getAttribute("icon") == "/icons/helidonToolWindow.svg"
    })
    assertTrue(extensionPoints.any { element ->
      element.getAttribute("name") == "servicesViewContributor" &&
        element.getAttribute("interface") == "com.intellij.helidon.services.HelidonServicesViewContributor"
    })
  }

  @Test
  fun testMainDescriptorRegistersConfigFileContributorExtensionPoint() {
    val document = parseDescriptor(Path.of("resources/META-INF/plugin.xml"))
    val extensionPoints = document.getElementsByTagName("extensionPoint").elements()

    assertTrue(extensionPoints.any { element ->
      element.getAttribute("name") == "configFileContributor" &&
        element.getAttribute("interface") == "com.intellij.helidon.config.HelidonConfigFileContributor"
    })
  }

  @Test
  fun testMainDescriptorRegistersHelidonTemplatesAndTestProducer() {
    val document = parseDescriptor(Path.of("resources/META-INF/plugin.xml"))
    val fileTemplateGroups = document.getElementsByTagName("fileTemplateGroup").elements()
    val newMenuGroups = document.getElementsByTagName("group").elements()
    val runConfigurationProducers = document.getElementsByTagName("runConfigurationProducer").elements()
    val pluginIds = (document.getElementsByTagName("dependencies").item(0) as Element)
      .getElementsByTagName("plugin")
      .attributes("id")

    assertTrue(fileTemplateGroups.any { element ->
      element.getAttribute("implementation") ==
        "com.intellij.helidon.templates.HelidonFileTemplateGroupDescriptorFactory"
    })
    assertTrue(runConfigurationProducers.any { element ->
      element.getAttribute("implementation") ==
        "com.intellij.helidon.testing.HelidonMavenTestRunConfigurationProducer"
    })
    assertTrue("JUnit dependency is required by HelidonTestTargetResolver",
               pluginIds.contains("JUnit"))

    val newMenuGroup = newMenuGroups.single { element -> element.getAttribute("id") == "Helidon.New" }
    val newMenuActionClasses = newMenuGroup.getElementsByTagName("action")
      .elements()
      .map { element -> element.getAttribute("class") }
    val newMenuRegistration = newMenuGroup.getElementsByTagName("add-to-group").elements().single()

    assertTrue("All Helidon templates should have Project View New menu actions",
               newMenuActionClasses.size == 8)
    assertTrue(newMenuActionClasses.containsAll(listOf(
      "com.intellij.helidon.templates.HelidonCreateSeServiceAction",
      "com.intellij.helidon.templates.HelidonCreateMpResourceAction",
      "com.intellij.helidon.templates.HelidonCreateDeclarativeHttpServiceAction",
      "com.intellij.helidon.templates.HelidonCreateConfigClassAction",
      "com.intellij.helidon.templates.HelidonCreateServerTestAction",
      "com.intellij.helidon.templates.HelidonCreateLangChain4jServiceAction",
      "com.intellij.helidon.templates.HelidonCreateLangChain4jAgentAction",
      "com.intellij.helidon.templates.HelidonCreateOciConfigAction",
    )))
    val ociConfigAction = newMenuGroup.getElementsByTagName("action")
      .elements()
      .single { element -> element.getAttribute("id") == "Helidon.New.OciConfig" }
    assertTrue(ociConfigAction.getAttribute("icon") == "/icons/ora.svg")
    assertTrue("Helidon templates must be visible from the Project View New menu",
               newMenuRegistration.getAttribute("group-id") == "NewGroup")
    assertTrue("Helidon templates should appear near the standard template actions",
               newMenuRegistration.getAttribute("relative-to-action") == "NewFromTemplate")
  }

  @Test
  fun testMicroservicesDescriptorContributesHelidonServicesEndpoints() {
    val document = parseDescriptor(Path.of("resources/META-INF/helidon-microservices.xml"))
    val contributors = document.getElementsByTagName("helidon.servicesViewContributor").elements()

    assertTrue(contributors.any { element ->
      element.getAttribute("implementation") == "com.intellij.helidon.services.HelidonHttpServicesViewContributor"
    })
  }

  @Test
  fun testMicroservicesDescriptorDoesNotDeclareBaseExtensionPoints() {
    val document = parseDescriptor(Path.of("resources/META-INF/helidon-microservices.xml"))
    val extensionPoints = document.getElementsByTagName("extensionPoint").elements()

    assertFalse(extensionPoints.any { element -> element.getAttribute("name") == "configFileContributor" })
  }

  @Test
  fun testMicroservicesDescriptorDoesNotRegisterBaseConfigFileContributors() {
    val document = parseDescriptor(Path.of("resources/META-INF/helidon-microservices.xml"))
    val contributors = document.getElementsByTagName("helidon.configFileContributor").elements()

    assertFalse(contributors.any { element ->
      element.getAttribute("implementation") == "com.intellij.helidon.config.properties.HelidonPropertiesConfigFileContributor"
    })
    assertFalse(contributors.any { element ->
      element.getAttribute("implementation") == "com.intellij.helidon.config.yaml.HelidonYamlConfigFileContributor"
    })
  }

  @Test
  fun testOptionalSubDescriptorsDoNotDeclareDependencies() {
    listOf(
      Path.of("resources/META-INF/helidon-microservices.xml"),
      Path.of("resources/META-INF/helidon-langchain4j-diagram.xml"),
    ).forEach { path ->
      val document = parseDescriptor(path)

      assertFalse("$path should not declare hard dependencies",
                  document.getElementsByTagName("dependencies").elements().isNotEmpty())
    }
  }

  @Test
  fun testOptionalSubDescriptorsDoNotRegisterTemplatesOrTestProducers() {
    listOf(
      Path.of("resources/META-INF/helidon-microservices.xml"),
      Path.of("resources/META-INF/helidon-langchain4j-diagram.xml"),
    ).forEach { path ->
      val document = parseDescriptor(path)

      assertFalse("$path should not register base file templates",
                  document.getElementsByTagName("fileTemplateGroup").elements().isNotEmpty())
      assertFalse("$path should not register base test run producers",
                  document.getElementsByTagName("runConfigurationProducer").elements().isNotEmpty())
    }
  }

  @Test
  fun testLangChain4jDiagramDescriptorIsOptional() {
    val document = parseDescriptor(Path.of("resources/META-INF/helidon-langchain4j-diagram.xml"))
    val diagramProviders = document.getElementsByTagName("diagram.Provider").elements()
    val actions = document.getElementsByTagName("action").elements()

    assertTrue(diagramProviders.any { element ->
      element.getAttribute("implementation") ==
        "com.intellij.helidon.langchain4j.diagram.HelidonLangChain4jDiagramProvider"
    })
    assertTrue(actions.any { element ->
      element.getAttribute("id") == "Helidon.ShowLangChain4jWorkflowDiagram" &&
        element.getAttribute("class") ==
        "com.intellij.helidon.langchain4j.diagram.HelidonLangChain4jShowDiagramAction"
    })
  }

  @Test
  fun testDescriptorsDoNotRegisterKotlinSourceLanguageSupport() {
    listOf(
      Path.of("resources/META-INF/plugin.xml"),
      Path.of("resources/META-INF/helidon-microservices.xml"),
      Path.of("resources/META-INF/helidon-langchain4j-diagram.xml"),
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

  @Test
  fun testCommunityConfigCompletionDoesNotUseMicroservicesApis() {
    val ceSafeSources = listOf(
      Path.of("src/com/intellij/helidon/config/ce"),
      Path.of("src/com/intellij/helidon/config/HelidonYamlCompletionInsertHandlers.kt"),
    )
    for (source in ceSafeSources) {
      val sourceUsesMicroservicesPackage = Files.walk(source).use { paths ->
        paths
          .filter { it.toString().endsWith(".kt") }
          .anyMatch { Files.readString(it).contains("import com.intellij.microservices") }
      }

      assertFalse("$source should not import Microservices APIs", sourceUsesMicroservicesPackage)
    }
  }

  private fun parseDescriptor(path: Path) = DocumentBuilderFactory.newInstance()
    .newDocumentBuilder()
    .parse(path.toFile())

  private fun NodeList.elements(): List<Element> = (0 until length)
    .map { item(it) as Element }

  private fun NodeList.attributes(name: String): List<String> = elements()
    .map { it.getAttribute(name) }
}
