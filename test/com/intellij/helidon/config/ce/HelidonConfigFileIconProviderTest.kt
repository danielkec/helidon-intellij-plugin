// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.config.HELIDON_APPLICATION_PROPERTIES
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML

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
