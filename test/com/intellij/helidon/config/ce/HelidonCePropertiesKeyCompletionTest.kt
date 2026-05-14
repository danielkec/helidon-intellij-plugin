// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.ce

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.config.HELIDON_APPLICATION_PROPERTIES
import com.intellij.lang.properties.psi.codeStyle.PropertiesCodeStyleSettings

class HelidonCePropertiesKeyCompletionTest : HelidonHighlightingTestCase() {

  fun testCompletesConfigKeyFromMetadata() {
    withMicroservicesPluginEnabled(false) {
      configureApplicationProperties("server.h<caret>")
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "server.host")
    }
  }

  fun testCompletesRelaxedCamelCaseKeyFromMetadata() {
    withMicroservicesPluginEnabled(false) {
      configureApplicationProperties("server.connection-config.keepAlive<caret>")
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings
      assertNotNull(lookupElementStrings)
      assertContainsElements(lookupElementStrings!!, "server.connection-config.keep-alive")
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

      myFixture.checkResult("server.connection-config.keep-alive=<caret>")
    }
  }

  fun testDoesNotSuggestNestedConfigContainerKey() {
    withMicroservicesPluginEnabled(false) {
      configureApplicationProperties("server.connection<caret>")
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings ?: emptyList()
      assertContainsElements(lookupElementStrings, "server.connection-config.keep-alive")
      assertDoesntContain(lookupElementStrings, "server.connection-config")
    }
  }

  fun testDoesNotSuggestNestedConfigContainerKeyInParameterizedMap() {
    withMicroservicesPluginEnabled(false) {
      configureApplicationProperties("server.sockets.admin.connection<caret>")
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings ?: emptyList()
      assertContainsElements(lookupElementStrings, "connection-config.keep-alive")
      assertDoesntContain(lookupElementStrings, "connection-config")
    }
  }

  fun testCompletesParameterizedMapKeyFromMetadata() {
    withMicroservicesPluginEnabled(false) {
      configureApplicationProperties("server.sockets.admin.p<caret>")
      myFixture.completeBasic()
      myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

      myFixture.checkResult("server.sockets.admin.port=<caret>")
    }
  }

  fun testDoesNotSuggestUnboundParameterizedMapKey() {
    withMicroservicesPluginEnabled(false) {
      configureApplicationProperties("server.sockets.<caret>")
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings ?: emptyList()
      assertFalse(lookupElementStrings.any { "*" in it })
    }
  }

  fun testDoesNotCompleteKeysOutsideSourceRoot() {
    withMicroservicesPluginEnabled(false) {
      configureContentRootOnlyFile(HELIDON_APPLICATION_PROPERTIES, "server.")
      myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
      myFixture.completeBasic()

      val lookupElementStrings = myFixture.lookupElementStrings ?: emptyList()
      assertDoesntContain(lookupElementStrings, "server.host", "server.port", "server.sockets")
    }
  }

  fun testCompletesKeyWithConfiguredDelimiter() {
    withMicroservicesPluginEnabled(false) {
      val settings = PropertiesCodeStyleSettings.getInstance(project)
      val originalDelimiterCode = settings.KEY_VALUE_DELIMITER_CODE
      settings.KEY_VALUE_DELIMITER_CODE = 1
      try {
        configureApplicationProperties("server.sockets.admin.p<caret>")
        myFixture.completeBasic()
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        myFixture.checkResult("server.sockets.admin.port:<caret>")
      }
      finally {
        settings.KEY_VALUE_DELIMITER_CODE = originalDelimiterCode
      }
    }
  }

  fun testCompletesKeyWithConfiguredSpaceDelimiter() {
    withMicroservicesPluginEnabled(false) {
      val settings = PropertiesCodeStyleSettings.getInstance(project)
      val originalDelimiterCode = settings.KEY_VALUE_DELIMITER_CODE
      settings.KEY_VALUE_DELIMITER_CODE = 2
      try {
        configureApplicationProperties("server.sockets.admin.p<caret>")
        myFixture.completeBasic()
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        myFixture.checkResult("server.sockets.admin.port <caret>")
      }
      finally {
        settings.KEY_VALUE_DELIMITER_CODE = originalDelimiterCode
      }
    }
  }
}
