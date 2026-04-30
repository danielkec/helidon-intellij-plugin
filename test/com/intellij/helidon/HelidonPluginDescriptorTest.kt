// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon

import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class HelidonPluginDescriptorTest {

  @Test
  fun testInternalMicroservicesYamlConfigModuleIsNotDeclared() {
    val document = DocumentBuilderFactory.newInstance()
      .newDocumentBuilder()
      .parse(Path.of("resources/META-INF/plugin.xml").toFile())
    val dependencies = document.getElementsByTagName("dependencies").item(0) as Element
    val modules = dependencies.getElementsByTagName("module")
    val moduleNames = (0 until modules.length)
      .map { modules.item(it) as Element }
      .map { it.getAttribute("name") }

    assertFalse(moduleNames.contains("intellij.microservices.jvm.config.yaml"))
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
}
