// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML

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
}
