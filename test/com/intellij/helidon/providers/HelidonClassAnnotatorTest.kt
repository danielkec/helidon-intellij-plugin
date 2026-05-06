// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.openapi.module.ModuleUtilCore

class HelidonClassAnnotatorTest : HelidonHighlightingTestCase() {

  fun testServiceSingletonHasGutterMarker() {
    addServiceRegistryStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.service.registry.Service;

      @Service.Singleton
      class GreetingService {
      }
    """.trimIndent())

    val serviceClass = myFixture.findClass("GreetingService")
    val result = mutableListOf<RelatedItemLineMarkerInfo<*>>()

    HelidonClassAnnotator().collectNavigationMarkers(listOf(serviceClass.nameIdentifier!!), result, true)

    assertSize(1, result)
    assertSame(HelidonIcons.HelidonBeanGutter, result.single().icon)
  }

  fun testServiceSingletonUsageTargetsIncludeInjectionAndLookup() {
    addServiceRegistryStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.service.registry.Service;
      import io.helidon.service.registry.Services;

      @Service.Contract
      interface Greeting {
      }

      @Service.Singleton
      class GreetingService implements Greeting {
      }

      @Service.Singleton
      class GreetingConsumer {
        @Service.Inject
        GreetingConsumer(Greeting greeting) {
        }

        void lookup() {
          Services.get(Greeting.class);
        }
      }
    """.trimIndent())

    val serviceClass = myFixture.findClass("GreetingService")
    val module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!

    val targets = HelidonCommonUtils.getHelidonServiceUsageTargets(module, serviceClass)

    assertTrue(targets.any { it.text == "greeting" })
    assertTrue(targets.any { it.text == "Greeting.class" })
  }

  private fun addServiceRegistryStubs() {
    myFixture.addClass("""
      package io.helidon.service.registry;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      public final class Service {
        private Service() {
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
        public @interface Singleton {
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
        public @interface Inject {
        }

        @Retention(RetentionPolicy.CLASS)
        @Target(ElementType.TYPE)
        public @interface Contract {
        }

        @Retention(RetentionPolicy.CLASS)
        @Target(ElementType.TYPE)
        public @interface ExternalContracts {
          Class<?>[] value();
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.service.registry;

      public final class Services {
        private Services() {
        }

        public static <T> T get(Class<T> contract) {
          return null;
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.service.registry;

      public interface ServiceRegistry {
        <T> T get(Class<T> contract);
      }
    """.trimIndent())
  }
}
