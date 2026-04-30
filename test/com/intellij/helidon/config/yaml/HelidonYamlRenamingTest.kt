// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML
import com.intellij.refactoring.rename.PsiElementRenameHandler

class HelidonYamlRenamingTest : HelidonHighlightingTestCase() {
  fun testMetadataBackedKeyRenamingVetoed() {
    assertRenameVetoState("""
      server:
        ho<caret>st: localhost
    """.trimIndent(), usePlainElementFind = false)
  }

  fun testUnresolvedKeyRenamingAllowed() {
    assertRenameVetoState("""
      so<caret>me:
        INVALID: 42
    """.trimIndent(), usePlainElementFind = true, expectedVetoed = false)
  }

  fun testUserKeyRenamingAllowed() {
    assertRenameVetoState("""
      app:
        custom:
          ke<caret>y: value
    """.trimIndent(), usePlainElementFind = false, expectedVetoed = false)
  }

  fun testKeyViaPropertyPlaceholderRenamingVetoed() {
    assertRenameVetoState("""
      server:
        host: ${"$"}{server.<caret>host}
    """.trimIndent(), usePlainElementFind = false)
  }

  fun testSystemPropertyPlaceholderRenamingVetoed() {
    assertRenameVetoState("""
      my:
        integer: ${"$"}{user.<caret>home}
    """, usePlainElementFind = false)
  }

  private fun assertRenameVetoState(applicationYml: String,
                                    usePlainElementFind: Boolean,
                                    expectedVetoed: Boolean = true) {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, applicationYml)
    val element = if (usePlainElementFind) {
      myFixture.file.findElementAt(myFixture.caretOffset)
    }
    else {
      myFixture.elementAtCaret
    }
    assertEquals(expectedVetoed, PsiElementRenameHandler.isVetoed(element))
  }
}
