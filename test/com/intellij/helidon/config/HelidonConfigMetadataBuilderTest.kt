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

  fun testCollectsSharedNestedValueTypeUnderSiblingPrefixesAndGuardsCycles() {
    myFixture.addClass("""
      package example;

      public class RootConfig {
      }
    """.trimIndent())
    myFixture.addClass("""
      package example;

      public class SharedConfig {
      }
    """.trimIndent())
    val metadataFile = myFixture.configureByText("config-metadata.json", """
      [{
        "module": "test",
        "types": [{
          "type": "example.RootConfig",
          "standalone": true,
          "prefix": "server",
          "options": [{
            "key": "primary",
            "type": "example.SharedConfig"
          }, {
            "key": "secondary",
            "type": "example.SharedConfig"
          }]
        }, {
          "type": "example.SharedConfig",
          "options": [{
            "key": "endpoint"
          }, {
            "key": "cycle",
            "type": "example.RootConfig"
          }]
        }]
      }]
    """.trimIndent())

    val moduleMetadata = HelidonConfigMetadataParser().parse(metadataFile)
    assertNotNull(moduleMetadata)

    val keys = HelidonConfigMetadataBuilder(listOf(moduleMetadata!!), project).collectKeys(module)

    assertEquals(listOf("server.primary.endpoint", "server.secondary.endpoint"), keys.map { it.name })
  }
}
