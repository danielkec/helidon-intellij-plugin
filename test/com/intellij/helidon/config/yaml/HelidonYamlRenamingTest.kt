// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML
import com.intellij.refactoring.rename.PsiElementRenameHandler

class HelidonYamlRenamingTest : HelidonHighlightingTestCase() {
  fun testMetadataBackedKeyRenamingVetoed() {
    assertRenamingVetoed("""
      server:
        ho<caret>st: localhost
    """.trimIndent(), false)
  }

  fun testUnresolvedKeyRenamingAllowed() {
    assertRenamingVetoed("""
      so<caret>me:
        INVALID: 42
    """.trimIndent(), true, false)
  }

  fun testUserKeyRenamingAllowed() {
    assertRenamingVetoed("""
      app:
        custom:
          ke<caret>y: value
    """.trimIndent(), false, false)
  }

  fun testKeyViaPropertyPlaceholderRenamingVetoed() {
    assertRenamingVetoed("""
      server:
        host: ${"$"}{server.<caret>host}
    """.trimIndent(), false)
  }

  fun testSystemPropertyPlaceholderRenamingVetoed() {
    assertRenamingVetoed("""
      my:
        integer: ${"$"}{user.<caret>home}
    """, false)
  }

  private fun assertRenamingVetoed(applicationYml: String,
                                   usePlainElementFind: Boolean,
                                   prohibited: Boolean = true) {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, applicationYml)
    val element = if (usePlainElementFind) {
      myFixture.file.findElementAt(myFixture.caretOffset)
    }
    else {
      myFixture.elementAtCaret
    }
    assertEquals(prohibited, PsiElementRenameHandler.isVetoed(element))
  }
}
