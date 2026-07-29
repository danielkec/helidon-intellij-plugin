// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.HelidonProjectDescriptorBuilder
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML
import com.intellij.helidon.config.HELIDON_CONFIG_METADATA
import com.intellij.helidon.config.HELIDON_OCI_CONFIG_YAML
import com.intellij.helidon.config.crossProviderMetadata
import com.intellij.helidon.config.envConfigMetadata
import com.intellij.helidon.config.sharedClientMetadata
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import java.nio.file.Files

class HelidonCeYamlKeyCompletionTest : HelidonHighlightingTestCase() {

  fun testCompletesNestedConfigKeyFromMetadata() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        server:
          <caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "host", "port", "sockets")
    }
  }

  fun testCompletesNestedConfigKeyAtEmptyLineStartBeforeExistingKeys() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        server:
        <caret>
          port: 8789
          host: 0.0.0.0
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "backlog", "name", "sockets")
      assertDoesntContain(lookupElementStrings, "server", "server.backlog", "server.name", "server.sockets")
    }
  }

  fun testCompletesNestedConfigKeyBeforeIndentOnBlankLineBeforeExistingKeys() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        server:
        <caret>${"  "}
          port: 8789
          host: 0.0.0.0
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "backlog", "name", "sockets")
      assertDoesntContain(lookupElementStrings, "server", "server.backlog", "server.name", "server.sockets")
    }
  }

  fun testCompletesRelaxedCamelCaseKeyFromMetadata() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        server:
          connection-config:
            keepA<caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "keep-alive")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

      myFixture.checkResult("""
        server:
          connection-config:
            keep-alive: <caret>
      """.trimIndent())
    }
  }

  fun testCompletesParameterizedMapChildKeyFromMetadata() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        server:
          sockets:
            admin:
              p<caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "port")
    }
  }

  fun testCompletesNestedConfigGroupChildKeyFromMetadata() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        server:
          connection-config:
            keep<caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "keep-alive")
    }
  }

  fun testCompletesListItemChildKeyFromMetadata() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        security:
          secrets:
            - <caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "name", "provider", "config")
      assertDoesntContain(lookupElementStrings, "*")
    }
  }

  fun testCompletesPrefixedListItemChildKeyFromMetadata() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        security:
          secrets:
            - n<caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "name")
    }
  }

  fun testDoesNotCompleteExistingListItemChildKey() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        security:
          secrets:
            - name: dev
              <caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings ?: emptyList()
      assertContainsElements(lookupElementStrings, "provider", "config")
      assertDoesntContain(lookupElementStrings, "name")
    }
  }

  fun testDoesNotCompleteExistingRootKey() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        server:
          port: 8080
        s<caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      if (lookupElementStrings == null) {
        val resultText = myFixture.file.text
        assertEquals(1, Regex("(?m)^server:").findAll(resultText).count())
        assertTrue(resultText.contains("security"))
      }
      else {
        assertContainsElements(lookupElementStrings, "security")
        assertDoesntContain(lookupElementStrings, "server")
      }
    }
  }

  fun testDoesNotCompleteKeysOutsideSourceRoot() {
    withMicroservicesPluginEnabled(false) {
      configureContentRootOnlyFile(HELIDON_APPLICATION_YAML, """
        server:
          h
      """.trimIndent())
      myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings ?: emptyList()
      assertDoesntContain(lookupElementStrings, "host")
    }
  }

  fun testDoesNotCompleteHelidonKeysInOtherYamlFiles() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText("other.yaml", """
        server:
          <caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings ?: emptyList()
      assertDoesntContain(lookupElementStrings, "host", "port", "sockets")
    }
  }

  fun testCompletesOciConfigYamlProviderRootFromMetadata() {
    withMicroservicesPluginEnabled(false) {
      addOciEnvProviderLibrary()
      myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
        helidon:
          oci-env:
            <caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "prefix", "use-physical-availability-domain")
      assertDoesntContain(lookupElementStrings, "server")
    }
  }

  fun testCompletesOciConfigYamlProviderRootAfterComment() {
    withMicroservicesPluginEnabled(false) {
      addOciEnvProviderLibrary()
      myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
        helidon:
          oci-env:
            # provider options:
            # prefix: "oci"
            <caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "prefix", "use-physical-availability-domain")
      assertDoesntContain(lookupElementStrings, "server")
    }
  }

  fun testCompletesOciConfigYamlProviderNestedTypeFromSeparateMetadataFile() {
    withMicroservicesPluginEnabled(false) {
      addOciCrossProviderLibrary()
      myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
        helidon:
          oci-cross:
            shared-client:
              <caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "endpoint")
    }
  }

  private fun addOciEnvProviderLibrary() {
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

    val libraryRootPath = Files.createTempDirectory("ce-oci-env-provider")
    val libraryRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRootPath)!!
    VfsTestUtil.createFile(libraryRoot,
                           "META-INF/services/io.helidon.config.spi.ConfigSourceProvider",
                           "com.oracle.helidon.oci.envconfig.OciEnvConfigSourceProvider")
    VfsTestUtil.createFile(libraryRoot, "META-INF/helidon/$HELIDON_CONFIG_METADATA", envConfigMetadata())
    PsiTestUtil.addLibrary(myFixture.testRootDisposable,
                           module,
                           "ce-oci-env-provider",
                           libraryRootPath.parent.toString(),
                           libraryRootPath.fileName.toString())
  }

  private fun addOciCrossProviderLibrary() {
    myFixture.addClass("""
      package com.oracle.helidon.oci.cross;

      public final class CrossConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.cross;

      public final class CrossConfigSourceProvider {
        static final String TYPE = "oci-cross";
      }
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.shared;

      public final class SharedClientConfig {}
    """.trimIndent())

    addMetadataLibrary("ce-oci-shared-metadata", sharedClientMetadata())
    addProviderLibrary(
      "ce-oci-cross-provider",
      "com.oracle.helidon.oci.cross.CrossConfigSourceProvider",
      crossProviderMetadata())
  }

  private fun addMetadataLibrary(rootName: String, metadata: String) {
    val libraryRootPath = Files.createTempDirectory(rootName)
    val libraryRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRootPath)!!
    VfsTestUtil.createFile(libraryRoot, "META-INF/helidon/$HELIDON_CONFIG_METADATA", metadata)
    PsiTestUtil.addLibrary(myFixture.testRootDisposable,
                           module,
                           rootName,
                           libraryRootPath.parent.toString(),
                           libraryRootPath.fileName.toString())
  }

  private fun addProviderLibrary(rootName: String, providerClass: String, metadata: String) {
    val libraryRootPath = Files.createTempDirectory(rootName)
    val libraryRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRootPath)!!
    VfsTestUtil.createFile(libraryRoot,
                           "META-INF/services/io.helidon.config.spi.ConfigSourceProvider",
                           providerClass)
    VfsTestUtil.createFile(libraryRoot, "META-INF/helidon/$HELIDON_CONFIG_METADATA", metadata)
    PsiTestUtil.addLibrary(myFixture.testRootDisposable,
                           module,
                           rootName,
                           libraryRootPath.parent.toString(),
                           libraryRootPath.fileName.toString())
  }
}

class HelidonCeYamlOciConfigNoHelidonLibraryKeyCompletionTest : HelidonHighlightingTestCase() {
  override fun getProjectDescriptor(): LightProjectDescriptor {
    return HelidonProjectDescriptorBuilder().build()
  }

  fun testCompletesBuiltInOciKeysWithoutHelidonLibraries() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
        helidon:
          oci:
            <caret>
      """.trimIndent())
      myFixture.completeBasic()

      val lookupStrings = myFixture.lookupElementStrings!!
      assertContainsElements(lookupStrings, "authentication", "region", "tenant-id")
      assertDoesntContain(lookupStrings, "server")
    }
  }

  fun testSelectingBuiltInOciRegionKeyKeepsCaretAtValueWithoutHelidonLibraries() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
        helidon:
          oci:
            reg<caret>
      """.trimIndent())
      myFixture.completeBasic()

      myFixture.checkResult("""
        helidon:
          oci:
            region: <caret>
      """.trimIndent())
    }
  }
}
