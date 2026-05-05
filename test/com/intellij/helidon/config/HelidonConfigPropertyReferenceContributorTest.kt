// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.lang.properties.psi.impl.PropertyImpl
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference
import com.intellij.util.containers.ContainerUtil

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

  fun testConfigGetReferenceUsesKotlinRawStringValueRange() {
    val delimiter = "\"\"\""
    assertConfigGetReferenceRange("Main.kt", """
      |import io.helidon.config.Config
      |
      |fun test(config: Config) {
      |  config.get(${delimiter}server.<caret>host$delimiter)
      |}
    """.trimMargin())
  }

  fun testKotlinInterpolatedConfigGetDoesNotCreateConfigReference() {
    addHelidonConfigClass()
    configureApplicationProperties("server.host=localhost\n")
    myFixture.configureByText("Main.kt", """
      |import io.helidon.config.Config
      |
      |fun test(config: Config, name: String) {
      |  config.get("server.<caret>${'$'}name")
      |}
    """.trimMargin())

    assertNull(findConfigPlaceholderReferenceAtCaret())
  }

  private fun assertConfigGetReferenceRange(fileName: String, text: String) {
    addHelidonConfigClass()
    configureApplicationProperties("server.host=localhost\n")
    myFixture.configureByText(fileName, text)

    val reference = getConfigPlaceholderReference(myFixture.getReferenceAtCaretPositionWithAssertion())
    assertEquals(ElementManipulators.getValueTextRange(reference.element), reference.rangeInElement)
    assertEquals("server.host", reference.canonicalText)

    val property = assertInstanceOf(reference.resolve(), PropertyImpl::class.java)
    assertEquals("server.host", property.key)
  }

  private fun getConfigPlaceholderReference(reference: PsiReference): HelidonConfigPlaceholderReference {
    findConfigPlaceholderReference(reference)?.let { return it }
    findConfigPlaceholderReferenceAtCaret()?.let { return it }

    fail("Expected ${HelidonConfigPlaceholderReference::class.java.name} in ${reference.javaClass.name}")
    error("unreachable")
  }

  private fun findConfigPlaceholderReferenceAtCaret(): HelidonConfigPlaceholderReference? {
    var element = myFixture.file.findElementAt(myFixture.caretOffset)
    while (element != null) {
      for (parentReference in element.references) {
        findConfigPlaceholderReference(parentReference)?.let { return it }
      }
      element = element.parent
    }
    return null
  }

  private fun findConfigPlaceholderReference(reference: PsiReference): HelidonConfigPlaceholderReference? {
    if (reference is HelidonConfigPlaceholderReference) return reference
    if (reference !is PsiMultiReference) return null
    return ContainerUtil.findInstance(reference.references, HelidonConfigPlaceholderReference::class.java)
  }

  private fun addHelidonConfigClass() {
    myFixture.addClass("""
      package io.helidon.config;

      public interface Config {
        Config get(String key);
      }
    """.trimIndent())
  }
}
