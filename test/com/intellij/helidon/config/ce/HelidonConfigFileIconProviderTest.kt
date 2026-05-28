// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.HelidonProjectDescriptorBuilder
import com.intellij.helidon.config.HELIDON_APPLICATION_PROPERTIES
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML
import com.intellij.helidon.config.HELIDON_OCI_CONFIG_YAML
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.testFramework.LightProjectDescriptor

class HelidonConfigFileIconProviderTest : HelidonHighlightingTestCase() {

  fun testApplicationYamlGetsHelidonIcon() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = myFixture.configureByText(HELIDON_APPLICATION_YAML, "")

      assertSame(HelidonIcons.Helidon, HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testApplicationPropertiesGetsHelidonIcon() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = myFixture.configureByText(HELIDON_APPLICATION_PROPERTIES, "")

      assertSame(HelidonIcons.Helidon, HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testOciConfigYamlGetsHelidonIcon() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, "")

      assertSame(HelidonIcons.Helidon, HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testOciConfigPropertiesDoesNotGetHelidonIcon() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = myFixture.configureByText("oci-config.properties", "")

      assertNull(HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testOtherYamlDoesNotGetHelidonIcon() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = myFixture.configureByText("other.yaml", "")

      assertNull(HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testApplicationYamlOutsideSourceRootDoesNotGetHelidonIcon() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = configureContentRootOnlyFile(HELIDON_APPLICATION_YAML, "")

      assertNull(HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testMicroservicesModeDoesNotOverrideIconProvider() {
    withMicroservicesPluginEnabled(true) {
      val psiFile = myFixture.configureByText(HELIDON_APPLICATION_YAML, "")

      assertNull(HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }
}

class HelidonConfigOnlyFileIconProviderTest : HelidonHighlightingTestCase() {
  override fun getProjectDescriptor(): LightProjectDescriptor {
    return HelidonProjectDescriptorBuilder()
      .withConfig()
      .build()
  }

  fun testConfigOnlyModuleGetsConfigFileIconWithoutGlobalHelidonFeatureGate() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, "")

      assertFalse(HelidonCoreUtils.hasHelidonLibrary(module))
      assertTrue(HelidonCoreUtils.hasHelidonConfigLibrary(module))
      assertSame(HelidonIcons.Helidon, HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }
}
