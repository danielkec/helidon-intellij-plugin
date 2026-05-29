// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.HelidonProjectDescriptorBuilder
import com.intellij.helidon.config.HELIDON_CONFIG_METADATA
import com.intellij.helidon.config.HELIDON_OCI_CONFIG_YAML
import com.intellij.helidon.config.envConfigMetadata
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import java.nio.file.Files
import java.nio.file.Path

class HelidonCeYamlValueCompletionTest : HelidonHighlightingTestCase() {
  fun testCompletesOciRegionFromUserRegionsConfigFirst() {
    withMicroservicesPluginEnabled(false) {
      addOciMetadataAndProviders()
      val home = Files.createTempDirectory("ce-oci-regions-home")
      Files.createDirectories(home.resolve(".oci"))
      Files.writeString(home.resolve(".oci/regions-config.json"), """
        [{
          "regionIdentifier": "ce-custom-region-1"
        }]
      """.trimIndent())

      withUserHome(home) {
        myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
          helidon:
            oci:
              region: <caret>
        """.trimIndent())
        myFixture.completeBasic()
      }

      val lookupStrings = myFixture.lookupElementStrings!!
      assertContainsElements(lookupStrings, "ce-custom-region-1")
      assertDoesntContain(lookupStrings, "us-ashburn-1")
    }
  }

  fun testCompletesOciEnvLocationOverrideRegionFromFallbackEnum() {
    withMicroservicesPluginEnabled(false) {
      addOciMetadataAndProviders()
      val home = Files.createTempDirectory("ce-oci-regions-empty-home")

      withUserHome(home) {
        myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
          helidon:
            oci-env:
              location-override:
                region: <caret>
        """.trimIndent())
        myFixture.completeBasic()
      }

      assertContainsElements(myFixture.lookupElementStrings!!, "us-ashburn-1", "eu-frankfurt-1", "sol-mars-1")
    }
  }

  private fun addOciMetadataAndProviders() {
    myFixture.addClass("""
      package io.helidon.integrations.oci;

      public final class OciConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.envconfig;

      public final class OciEnvConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.envconfig;

      public final class OciEnvLocationOverride {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.envconfig;

      public final class OciEnvDynamicCoreRegions {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.envconfig;

      public final class OciEnvConfigSourceProvider {
        static final String TYPE = "oci-env";
      }
    """.trimIndent())

    addLibrary("ce-public-oci-metadata", publicOciMetadata())
    addProviderLibrary(
      "ce-oci-env-provider",
      "com.oracle.helidon.oci.envconfig.OciEnvConfigSourceProvider",
      envConfigMetadata(),
    )
  }

  private fun addLibrary(rootName: String,
                         metadata: String,
                         metadataPath: String = "META-INF/helidon/$HELIDON_CONFIG_METADATA") {
    val libraryRootPath = Files.createTempDirectory(rootName)
    val libraryRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRootPath)!!
    VfsTestUtil.createFile(libraryRoot, metadataPath, metadata)
    PsiTestUtil.addLibrary(myFixture.testRootDisposable,
                           module,
                           rootName,
                           libraryRootPath.parent.toString(),
                           libraryRootPath.fileName.toString())
  }

  private fun addProviderLibrary(rootName: String,
                                 providerClass: String,
                                 metadata: String,
                                 metadataPath: String = "META-INF/helidon/$HELIDON_CONFIG_METADATA") {
    val libraryRootPath = Files.createTempDirectory(rootName)
    val libraryRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRootPath)!!
    VfsTestUtil.createFile(libraryRoot,
                           "META-INF/services/io.helidon.config.spi.ConfigSourceProvider",
                           providerClass)
    VfsTestUtil.createFile(libraryRoot, metadataPath, metadata)
    PsiTestUtil.addLibrary(myFixture.testRootDisposable,
                           module,
                           rootName,
                           libraryRootPath.parent.toString(),
                           libraryRootPath.fileName.toString())
  }

  private fun <T> withUserHome(home: Path, action: () -> T): T {
    val oldValue = System.getProperty("user.home")
    System.setProperty("user.home", home.toString())
    try {
      return action()
    }
    finally {
      if (oldValue == null) {
        System.clearProperty("user.home")
      }
      else {
        System.setProperty("user.home", oldValue)
      }
    }
  }

  private fun publicOciMetadata(): String {
    return """
      [{
        "module": "helidon-integrations-oci",
        "types": [{
          "type": "io.helidon.integrations.oci.OciConfig",
          "standalone": true,
          "prefix": "helidon.oci",
          "options": [{
            "key": "region",
            "description": "OCI region"
          }]
        }]
      }]
    """.trimIndent()
  }
}

class HelidonCeYamlOciConfigNoHelidonLibraryValueCompletionTest : HelidonHighlightingTestCase() {
  override fun getProjectDescriptor(): LightProjectDescriptor {
    return HelidonProjectDescriptorBuilder().build()
  }

  fun testCompletesBuiltInOciRegionValuesWithoutHelidonLibraries() {
    withMicroservicesPluginEnabled(false) {
      val home = Files.createTempDirectory("ce-oci-regions-no-helidon-home")
      Files.createDirectories(home.resolve(".oci"))
      Files.writeString(home.resolve(".oci/regions-config.json"), """
        [{
          "regionIdentifier": "custom-ce-no-helidon-1"
        }]
      """.trimIndent())

      withUserHome(home) {
        myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
          helidon:
            oci:
              region: <caret>
        """.trimIndent())
        myFixture.completeBasic()
      }

      assertContainsElements(myFixture.lookupElementStrings!!, "custom-ce-no-helidon-1")
    }
  }

  private fun <T> withUserHome(home: Path, action: () -> T): T {
    val oldValue = System.getProperty("user.home")
    System.setProperty("user.home", home.toString())
    try {
      return action()
    }
    finally {
      if (oldValue == null) {
        System.clearProperty("user.home")
      }
      else {
        System.setProperty("user.home", oldValue)
      }
    }
  }
}
