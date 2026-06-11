// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.HelidonProjectDescriptorBuilder
import com.intellij.helidon.config.HELIDON_APPLICATION_PROPERTIES
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML
import com.intellij.helidon.config.HELIDON_OCI_CONFIG_YAML
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil

class HelidonConfigFileIconProviderTest : HelidonHighlightingTestCase() {

  fun testApplicationYamlGetsHelidonIcon() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = configureResourceFile(HELIDON_APPLICATION_YAML, "")

      assertSame(HelidonIcons.Helidon, HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testApplicationPropertiesGetsHelidonIcon() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = configureResourceFile(HELIDON_APPLICATION_PROPERTIES, "")

      assertSame(HelidonIcons.Helidon, HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testOciConfigYamlGetsOraIcon() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = configureResourceFile(HELIDON_OCI_CONFIG_YAML, "")

      assertSame(HelidonIcons.Ora, HelidonConfigFileIconProvider().getIcon(psiFile, 0))
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
      val psiFile = configureResourceFile("other.yaml", "")

      assertNull(HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testApplicationYamlOutsideSourceRootDoesNotGetHelidonIcon() {
    withMicroservicesPluginEnabled(false) {
      val psiFile = configureContentRootOnlyFile(HELIDON_APPLICATION_YAML, "")

      assertNull(HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testApplicationYamlInJavaSourceRootDoesNotGetHelidonIcon() {
    withMicroservicesPluginEnabled(false) {
      configureResourceRoot()
      val psiFile = configureJavaSourceFile(HELIDON_APPLICATION_YAML, "")

      assertNull(HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  fun testMicroservicesModeDoesNotOverrideIconProvider() {
    withMicroservicesPluginEnabled(true) {
      val psiFile = configureResourceFile(HELIDON_APPLICATION_YAML, "")

      assertNull(HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }

  private fun configureResourceFile(fileName: String, text: String): PsiFile {
    configureResourceRoot()
    return configureRootedFile("src/main/resources", fileName, text)
  }

  private fun configureJavaSourceFile(fileName: String, text: String): PsiFile {
    val sourceRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
    PsiTestUtil.addSourceContentToRoots(module, sourceRoot, false)
    Disposer.register(myFixture.testRootDisposable,
                      Disposable { PsiTestUtil.removeContentEntry(module, sourceRoot) })
    return configureRootedFile("src/main/java", fileName, text)
  }

  private fun configureResourceRoot() {
    val resourceRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/resources")
    PsiTestUtil.addResourceContentToRoots(module, resourceRoot, false)
    Disposer.register(myFixture.testRootDisposable,
                      Disposable { PsiTestUtil.removeContentEntry(module, resourceRoot) })
  }

  private fun configureRootedFile(rootPath: String, fileName: String, text: String): PsiFile {
    val file = myFixture.addFileToProject("$rootPath/$fileName", text)
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    return myFixture.file
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
      assertSame(HelidonIcons.Ora, HelidonConfigFileIconProvider().getIcon(psiFile, 0))
    }
  }
}
