// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.HelidonHighlightingTestCase

class HelidonConfigMetadataBuilderTest : HelidonHighlightingTestCase() {

  fun testCollectsKeysWithoutMethodMetadata() {
    myFixture.addClass("""
      package example;

      public class ServerConfig {
      }
    """.trimIndent())
    val metadataFile = myFixture.configureByText("config-metadata.json", """
      [{
        "module": "test",
        "types": [{
          "type": "example.ServerConfig",
          "standalone": true,
          "prefix": "server",
          "options": [{
            "key": "host",
            "description": "Host"
          }]
        }]
      }]
    """.trimIndent())

    val moduleMetadata = HelidonConfigMetadataParser().parse(metadataFile)
    assertNotNull(moduleMetadata)

    val keys = HelidonConfigMetadataBuilder(listOf(moduleMetadata!!), project).collectKeys(module)

    assertContainsElements(keys.map { it.name }, "server.host")
  }
}
