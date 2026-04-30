// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.hints

import com.intellij.helidon.config.HelidonMetaConfigKeyTestCase
import com.intellij.lang.properties.psi.impl.PropertyImpl
import com.intellij.microservices.jvm.config.MetaConfigKey
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

class HelidonHintReferencesProviderTest : HelidonMetaConfigKeyTestCase() {

  fun testClassExtendsWildcardHasClassValueReferences() {
    assertTrue(getReferences("java.lang.Class<? extends java.lang.Number>").isNotEmpty())
  }

  fun testClassUnboundedWildcardHasNoValueReferences() {
    assertEmpty(getReferences("java.lang.Class<?>"))
  }

  fun testClassSuperWildcardHasNoValueReferences() {
    assertEmpty(getReferences("java.lang.Class<? super java.lang.Number>"))
  }

  private fun getReferences(typeText: String): Array<PsiReference> {
    val key = createKey("test.class", createType(typeText), MetaConfigKey.AccessType.NORMAL)
    val propertiesFile = configureApplicationProperties("test.class=java.lang.Integer")
    val property = PsiTreeUtil.findChildOfType(propertiesFile, PropertyImpl::class.java)!!
    val valuePsiElement = property.valueNode!!.psi

    return HelidonHintReferencesProvider().getValueReferences(key,
                                                              null,
                                                              valuePsiElement,
                                                              listOf(ElementManipulators.getValueTextRange(valuePsiElement)),
                                                              ProcessingContext())
  }

  private fun createType(typeText: String) = JavaPsiFacade.getElementFactory(project).createTypeFromText(typeText, null)
}
