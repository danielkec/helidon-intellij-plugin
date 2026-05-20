// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiDocumentManager

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

  fun testLangChain4jAiServiceHasGutterMarkerForExtensionsPackage() {
    assertLangChain4jGutterMarker("io.helidon.extensions.langchain4j", "Service")
  }

  fun testLangChain4jAiAgentHasGutterMarkerForExtensionsPackage() {
    assertLangChain4jGutterMarker("io.helidon.extensions.langchain4j", "Agent")
  }

  fun testLangChain4jAiServiceHasGutterMarkerForIntegrationsPackage() {
    assertLangChain4jGutterMarker("io.helidon.integrations.langchain4j", "Service")
  }

  fun testLangChain4jAiAgentHasGutterMarkerForIntegrationsPackage() {
    assertLangChain4jGutterMarker("io.helidon.integrations.langchain4j", "Agent")
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

    val targets = HelidonCoreUtils.getHelidonServiceUsageTargets(module, serviceClass)

    assertTrue(targets.any { it.text == "greeting" })
    assertTrue(targets.any { it.text == "Greeting.class" })
  }

  fun testSharedContractUsageTargetsAreFilteredPerService() {
    addServiceRegistryStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.service.registry.Service;

      @Service.Contract
      interface Greeting {
      }

      @Service.Singleton
      class GreetingService implements Greeting {
      }

      @Service.Singleton
      class FormalGreetingService implements Greeting {
        @Service.Inject
        Greeting selfGreeting;
      }

      @Service.Singleton
      class GreetingConsumer {
        @Service.Inject
        Greeting greeting;
      }
    """.trimIndent())

    val module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!
    val greetingService = myFixture.findClass("GreetingService")
    val formalGreetingService = myFixture.findClass("FormalGreetingService")

    HelidonCoreUtils.getHelidonServiceUsageTargets(module, greetingService)
    val targets = HelidonCoreUtils.getHelidonServiceUsageTargets(module, formalGreetingService)

    assertTrue(targets.any { it.text == "greeting" })
    assertFalse(targets.any { it.text == "selfGreeting" })
  }

  fun testServiceUsageTargetsInvalidateAfterAddingAndRemovingInjectionAndLookup() {
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
        void lookup() {
        }
      }
    """.trimIndent())

    val serviceClass = myFixture.findClass("GreetingService")
    val module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!
    assertEmpty(HelidonCoreUtils.getHelidonServiceUsageTargets(module, serviceClass))

    val document = myFixture.editor.document
    val fieldText = "  @Service.Inject\n  Greeting greeting;\n\n"
    val lookupText = "    Services.get(Greeting.class);\n"
    WriteCommandAction.runWriteCommandAction(project) {
      document.insertString(requireOffset(document.text, "  void lookup()"), fieldText)
      document.insertString(requireOffset(document.text, "  }\n}"), lookupText)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val addedTargets = HelidonCoreUtils.getHelidonServiceUsageTargets(module, serviceClass)
    assertTrue(addedTargets.any { it.text == "greeting" })
    assertTrue(addedTargets.any { it.text == "Greeting.class" })

    WriteCommandAction.runWriteCommandAction(project) {
      val fieldOffset = requireOffset(document.text, fieldText)
      document.deleteString(fieldOffset, fieldOffset + fieldText.length)
      val lookupOffset = requireOffset(document.text, lookupText)
      document.deleteString(lookupOffset, lookupOffset + lookupText.length)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    assertEmpty(HelidonCoreUtils.getHelidonServiceUsageTargets(module, serviceClass))
  }

  private fun requireOffset(text: String, needle: String): Int {
    val offset = text.indexOf(needle)
    assertTrue("Missing text in test fixture: $needle", offset >= 0)
    return offset
  }

  private fun assertLangChain4jGutterMarker(packageName: String, annotationName: String) {
    addLangChain4jAiStub(packageName)
    myFixture.configureByText("Main.java", """
      import ${packageName}.Ai;

      @Ai.${annotationName}("assistant")
      interface Assistant {
      }
    """.trimIndent())

    val serviceClass = myFixture.findClass("Assistant")
    val result = mutableListOf<RelatedItemLineMarkerInfo<*>>()

    HelidonClassAnnotator().collectNavigationMarkers(listOf(serviceClass.nameIdentifier!!), result, true)

    assertSize(1, result)
    assertSame(HelidonIcons.HelidonBeanGutter, result.single().icon)
  }

  private fun addLangChain4jAiStub(packageName: String) {
    myFixture.addClass("""
      package ${packageName};

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      public final class Ai {
        private Ai() {
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Service {
          String value() default "";
          boolean autoDiscovery() default true;
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Agent {
          String value();
        }
      }
    """.trimIndent())
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
