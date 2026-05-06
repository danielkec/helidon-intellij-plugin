// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon

import com.intellij.helidon.newproject.HelidonModuleBuilder
import com.intellij.ide.starters.shared.StarterLanguage
import com.intellij.ide.starters.shared.StarterProjectType
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase4
import com.intellij.util.lang.JavaVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class HelidonModuleBuilderTest : LightJavaCodeInsightFixtureTestCase4(LightJavaCodeInsightFixtureTestCase.JAVA_21) {
  @Test
  fun wizardSupportsOnlyJavaAndHidesBuildSystem() {
    val moduleBuilder = HelidonModuleBuilder()

    assertEquals(LanguageLevel.JDK_21.toJavaVersion(), getMinJavaVersion(moduleBuilder))
    assertEquals(listOf("java"), getLanguages(moduleBuilder).map { it.id })
    assertEquals(emptyList<String>(), getProjectTypes(moduleBuilder).map { it.id })
  }

  @Suppress("UNCHECKED_CAST")
  private fun getLanguages(moduleBuilder: HelidonModuleBuilder): List<StarterLanguage> {
    val method = HelidonModuleBuilder::class.java.getDeclaredMethod("getLanguages")
    method.isAccessible = true
    return method.invoke(moduleBuilder) as List<StarterLanguage>
  }

  @Suppress("UNCHECKED_CAST")
  private fun getProjectTypes(moduleBuilder: HelidonModuleBuilder): List<StarterProjectType> {
    val method = HelidonModuleBuilder::class.java.getDeclaredMethod("getProjectTypes")
    method.isAccessible = true
    return method.invoke(moduleBuilder) as List<StarterProjectType>
  }

  private fun getMinJavaVersion(moduleBuilder: HelidonModuleBuilder): JavaVersion {
    val method = HelidonModuleBuilder::class.java.getDeclaredMethod("getMinJavaVersion")
    method.isAccessible = true
    return method.invoke(moduleBuilder) as JavaVersion
  }
}
