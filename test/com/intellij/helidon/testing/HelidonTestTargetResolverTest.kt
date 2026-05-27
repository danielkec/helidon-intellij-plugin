// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.testing

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil

class HelidonTestTargetResolverTest : HelidonHighlightingTestCase() {
  override fun setUp() {
    super.setUp()
    configureMavenLikeSourceRoots()
    addJunit5TestStub()
  }

  fun testResolvesJunitMethodInHelidonTestSource() {
    val file = addJavaFile("src/test/java/example/GreetingTest.java", """
      package example;

      import org.junit.jupiter.api.Test;

      public class GreetingTest {
        @Test
        void shouldGreet() {
        }
      }
    """.trimIndent())

    val target = HelidonTestTargetResolver.resolve(elementAt(file, "shouldGreet"), module, requireMaven = false)

    assertNotNull(target)
    assertEquals("example.GreetingTest", target!!.className)
    assertEquals("shouldGreet", target.methodName)
    assertEquals(listOf("test", "-Dtest=example.GreetingTest#shouldGreet"), target.goals)
  }

  fun testResolvesJunitClassInHelidonTestSource() {
    val file = addJavaFile("src/test/java/example/GreetingTest.java", """
      package example;

      import org.junit.jupiter.api.Test;

      public class GreetingTest {
        @Test
        void shouldGreet() {
        }
      }
    """.trimIndent())

    val target = HelidonTestTargetResolver.resolve(elementAt(file, "GreetingTest"), module, requireMaven = false)

    assertNotNull(target)
    assertEquals("example.GreetingTest", target!!.className)
    assertEquals(null, target.methodName)
    assertEquals(listOf("test", "-Dtest=example.GreetingTest"), target.goals)
  }

  fun testRejectsJunitClassOutsideTestSource() {
    val file = addJavaFile("src/main/java/example/GreetingTest.java", """
      package example;

      import org.junit.jupiter.api.Test;

      public class GreetingTest {
        @Test
        void shouldGreet() {
        }
      }
    """.trimIndent())

    val target = HelidonTestTargetResolver.resolve(elementAt(file, "GreetingTest"), module, requireMaven = false)

    assertNull(target)
  }

  fun testRequiresMavenModuleWhenRequested() {
    val file = addJavaFile("src/test/java/example/GreetingTest.java", """
      package example;

      import org.junit.jupiter.api.Test;

      public class GreetingTest {
        @Test
        void shouldGreet() {
        }
      }
    """.trimIndent())

    val target = HelidonTestTargetResolver.resolve(elementAt(file, "GreetingTest"), module, requireMaven = true)

    assertNull(target)
  }

  private fun configureMavenLikeSourceRoots() {
    val mainJava = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
    val testJava = myFixture.tempDirFixture.findOrCreateDir("src/test/java")
    PsiTestUtil.addSourceContentToRoots(module, mainJava, false)
    PsiTestUtil.addSourceContentToRoots(module, testJava, true)
    Disposer.register(myFixture.testRootDisposable,
                      Disposable {
                        PsiTestUtil.removeContentEntry(module, mainJava)
                        PsiTestUtil.removeContentEntry(module, testJava)
                      })
  }

  private fun addJunit5TestStub() {
    myFixture.addClass("""
      package org.junit.jupiter.api;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Retention(RetentionPolicy.RUNTIME)
      @Target(ElementType.METHOD)
      public @interface Test {
      }
    """.trimIndent())
  }

  private fun addJavaFile(path: String, text: String): PsiFile =
    myFixture.addFileToProject(path, text)

  private fun elementAt(file: PsiFile, text: String) =
    file.findElementAt(file.text.indexOf(text))!!
}
