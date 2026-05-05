// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.lang.properties.psi.impl.PropertyImpl
import com.intellij.psi.ElementManipulators

class HelidonConfigPropertyReferenceContributorTest : HelidonHighlightingTestCase() {

  fun testConfigGetReferenceUsesJavaTextBlockValueRange() {
    val delimiter = "\"\"\""
    assertConfigGetReferenceRange("Main.java", """
      |import io.helidon.config.Config;
      |
      |class Main {
      |  void test(Config config) {
      |    config.get($delimiter
      |server.<caret>host$delimiter);
      |  }
      |}
    """.trimMargin())
  }

  private fun assertConfigGetReferenceRange(fileName: String, text: String) {
    configureApplicationProperties("server.host=localhost\n")
    myFixture.configureByText(fileName, text)

    val reference = assertInstanceOf(myFixture.getReferenceAtCaretPositionWithAssertion(),
                                     HelidonConfigPlaceholderReference::class.java)
    assertEquals(ElementManipulators.getValueTextRange(reference.element), reference.rangeInElement)
    assertEquals("server.host", reference.canonicalText)

    val property = assertInstanceOf(reference.resolve(), PropertyImpl::class.java)
    assertEquals("server.host", property.key)
  }
}
