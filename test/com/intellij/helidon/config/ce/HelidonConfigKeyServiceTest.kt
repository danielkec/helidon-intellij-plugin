// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML
import com.intellij.helidon.config.HELIDON_CONFIG_METADATA
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class HelidonConfigKeyServiceTest : HelidonHighlightingTestCase() {

  fun testLoadsConfigMetadataWithoutMicroservicesModel() {
    val keys = HelidonConfigKeyService.getInstance().getAllKeys(module).associateBy { it.name }

    assertTrue(keys.containsKey("server.host"))
    assertEquals("java.lang.String", keys["server.host"]?.type)
    assertTrue(keys.containsKey("server.sockets"))
    assertEquals("java.util.Map<java.lang.String, io.helidon.webserver.ListenerConfig>", keys["server.sockets"]?.type)
    assertTrue(keys.containsKey("server.sockets.*.port"))
    assertFalse(keys.containsKey("server.connection-config"))
    assertTrue(keys.containsKey("server.connection-config.keep-alive"))
    assertFalse(keys.containsKey("server.sockets.*.connection-config"))
    assertTrue(keys.containsKey("server.sockets.*.connection-config.keep-alive"))
    assertTrue(keys.containsKey("security.secrets"))
    assertEquals("java.util.List<io.helidon.common.config.Config>", keys["security.secrets"]?.type)
    assertTrue(keys.containsKey("security.secrets.*.name"))
    assertTrue(keys.containsKey("security.secrets.*.provider"))
  }

  fun testConfigMetadataCacheSurvivesApplicationYamlPsiEdit() {
    withMicroservicesPluginEnabled(false) {
      myFixture.configureByText(HELIDON_APPLICATION_YAML, """
        server:
          port: <caret>8080
      """.trimIndent())

      val keysBeforeEdit = HelidonConfigKeyService.getInstance().getAllKeys(module)
      myFixture.type("1")
      val keysAfterEdit = HelidonConfigKeyService.getInstance().getAllKeys(module)

      assertSame(keysBeforeEdit, keysAfterEdit)
    }
  }

  fun testConfigMetadataCacheReparsesEditedDiscoveredMetadataFile() {
    withMicroservicesPluginEnabled(false) {
      val metadataFile = addConfigMetadataLibrary("metadata-cache-ce", metadataWithOption("before"))
      val service = HelidonConfigKeyService.getInstance()

      val keysBeforeEdit = service.getAllKeys(module).map { it.name }
      assertContainsElements(keysBeforeEdit, "review.cache.before")
      assertFalse(keysBeforeEdit.contains("review.cache.after"))

      replaceText(metadataFile, metadataWithOption("after"))

      val keysAfterEdit = service.getAllKeys(module).map { it.name }
      assertFalse(keysAfterEdit.contains("review.cache.before"))
      assertContainsElements(keysAfterEdit, "review.cache.after")
    }
  }

  private fun addConfigMetadataLibrary(rootName: String, text: String): VirtualFile {
    val libraryRootPath = Files.createTempDirectory(rootName)
    val libraryRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRootPath)!!
    val metadataFile = VfsTestUtil.createFile(libraryRoot, "META-INF/helidon/$HELIDON_CONFIG_METADATA", text)
    PsiTestUtil.addLibrary(myFixture.testRootDisposable,
                           module,
                           rootName,
                           libraryRootPath.parent.toString(),
                           libraryRootPath.fileName.toString())
    return metadataFile
  }

  private fun replaceText(file: VirtualFile, text: String) {
    runWriteAction {
      file.setBinaryContent(text.toByteArray(StandardCharsets.UTF_8))
    }
  }

  private fun metadataWithOption(optionKey: String): String {
    return """
      [
        {
          "module": "cache-invalidation-test",
          "types": [
            {
              "type": "example.CacheConfig",
              "standalone": true,
              "prefix": "review.cache",
              "options": [
                {
                  "key": "$optionKey",
                  "type": "java.lang.String",
                  "method": "java.lang.String#toString()",
                  "description": "Cache invalidation test"
                }
              ]
            }
          ]
        }
      ]
    """.trimIndent()
  }
}
