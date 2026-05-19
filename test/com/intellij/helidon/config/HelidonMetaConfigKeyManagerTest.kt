// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.microservices.jvm.config.MetaConfigKey
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class HelidonMetaConfigKeyManagerTest : HelidonHighlightingTestCase() {

  fun testFindCanonicalApplicationMetaConfigKey() {
    val key = HelidonMetaConfigKeyManager.getInstance().findCanonicalApplicationMetaConfigKey(module, "server.host")
    assertNotNull(key)

    val keyWithNonCanonicalName = HelidonMetaConfigKeyManager.getInstance().findCanonicalApplicationMetaConfigKey(module, "Server.host")
    assertNull(keyWithNonCanonicalName)
  }

  fun testMapConfigKeyHasNestedValueTypeMetadata() {
    val key = HelidonMetaConfigKeyManager.getInstance().findCanonicalApplicationMetaConfigKey(module, "server.sockets")
    assertNotNull(key)
    assertTrue(key!!.isAccessType(MetaConfigKey.AccessType.MAP))
    assertEquals("Map<String, ListenerConfig>", key.type!!.presentableText)
    assertEquals("ListenerConfig", key.effectiveValueType!!.presentableText)

    val helidonKey = assertInstanceOf(key, HelidonMetaConfigKey::class.java)
    assertContainsElements(helidonKey.subKeys.map { it.name }, "port", "host", "tls.enabled")
  }

  fun testConfigMetadataCacheSurvivesApplicationYamlPsiEdit() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      server:
        port: <caret>8080
    """.trimIndent())

    val keysBeforeEdit = HelidonMetaConfigKeyManager.getInstance().getAllMetaConfigKeys(module)
    myFixture.type("1")
    val keysAfterEdit = HelidonMetaConfigKeyManager.getInstance().getAllMetaConfigKeys(module)

    assertSame(keysBeforeEdit, keysAfterEdit)
  }

  fun testConfigMetadataCacheReparsesEditedDiscoveredMetadataFile() {
    val metadataFile = addConfigMetadataLibrary("metadata-cache-microservices", metadataWithOption("before"))
    val manager = HelidonMetaConfigKeyManager.getInstance()

    val keysBeforeEdit = manager.getAllMetaConfigKeys(module).map { it.name }
    assertContainsElements(keysBeforeEdit, "review.cache.before")
    assertFalse(keysBeforeEdit.contains("review.cache.after"))

    replaceText(metadataFile, metadataWithOption("after"))

    val keysAfterEdit = manager.getAllMetaConfigKeys(module).map { it.name }
    assertFalse(keysAfterEdit.contains("review.cache.before"))
    assertContainsElements(keysAfterEdit, "review.cache.after")
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
