// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML
import com.intellij.helidon.langchain4j.HelidonLangChain4jYamlLineMarkerProvider
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue

class HelidonYamlLangChain4jConfigReferenceTest : HelidonHighlightingTestCase() {
  fun testServiceKeyResolvesToAiServiceInterface() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        services:
          gree<caret>ter:
            chat-model: chat
    """.trimIndent())

    assertResolvesToNamedElement("GreetingAiService")
  }

  fun testAgentKeyResolvesToAiAgentInterfaceFromIntegrationsPackage() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        agents:
          plan<caret>ner:
            chat-model: chat
    """.trimIndent())

    assertResolvesToNamedElement("PlanningAgent")
  }

  fun testBlankAiServiceNameResolvesFromInterfaceFqnConfigKey() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        services:
          demo.DefaultNamed<caret>AiService:
            chat-model: chat
    """.trimIndent())

    assertResolvesToNamedElement("DefaultNamedAiService")
  }

  fun testModelProviderValueResolvesToProviderConfigKey() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        providers:
          openai:
            type: open-ai
        models:
          chat:
            provider: open<caret>ai
    """.trimIndent())

    val keyValue = assertInstanceOf(resolveAtCaret(), YAMLKeyValue::class.java)
    assertEquals("openai", keyValue.keyText)
  }

  fun testContentRetrieverValuesResolveToModelAndEmbeddingStoreConfigKeys() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        models:
          embedding:
            provider: openai
        embedding-stores:
          pgvector:
            provider: pg
        content-retrievers:
          docs:
            embedding-model: emb<caret>edding
            embedding-store: pgvector
    """.trimIndent())

    val keyValue = assertInstanceOf(resolveAtCaret(), YAMLKeyValue::class.java)
    assertEquals("embedding", keyValue.keyText)

    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        models:
          embedding:
            provider: openai
        embedding-stores:
          pgvector:
            provider: pg
        content-retrievers:
          docs:
            embedding-model: embedding
            embedding-store: pgv<caret>ector
    """.trimIndent())

    val embeddingStoreKey = assertInstanceOf(resolveAtCaret(), YAMLKeyValue::class.java)
    assertEquals("pgvector", embeddingStoreKey.keyText)
  }

  fun testMcpClientKeyValueResolvesToAiMcpClientsUsage() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        mcp-clients:
          filesystem:
            key: filesys<caret>tem
    """.trimIndent())

    assertResolvesToNamedElement("PlanningAgent")
  }

  fun testClassValuedToolReferenceResolvesToJavaClass() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        agents:
          planner:
            tools:
              - Calendar<caret>Tools
    """.trimIndent())

    assertResolvesToNamedElement("CalendarTools")
  }

  fun testLangChain4jConfigKeyHasGutterNavigation() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        services:
          gree<caret>ter:
            chat-model: chat
    """.trimIndent())

    val keyValue = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), YAMLKeyValue::class.java)!!
    val markers = mutableListOf<RelatedItemLineMarkerInfo<*>>()
    HelidonLangChain4jYamlLineMarkerProvider().collectNavigationMarkers(listOf(keyValue), markers, true)

    assertSize(1, markers)
    assertSame(HelidonIcons.HelidonGutter, markers.single().icon)
  }

  private fun assertResolvesToNamedElement(name: String) {
    val target = assertInstanceOf(resolveAtCaret(), PsiNamedElement::class.java)
    assertEquals(name, target.name)
  }

  private fun resolveAtCaret(): Any? {
    val element = myFixture.file.findElementAt(myFixture.caretOffset) ?: return null
    return generateSequence(element) { it.parent }
      .flatMap { parent ->
        parent.references.asSequence().filter { reference -> reference.coversCaret() }
      }
      .mapNotNull(PsiReference::resolve)
      .firstOrNull()
  }

  private fun PsiReference.coversCaret(): Boolean {
    val relativeOffset = myFixture.caretOffset - element.textRange.startOffset
    return rangeInElement.contains(relativeOffset)
  }

  private fun addLangChain4jStubs() {
    addLangChain4jAiStub("io.helidon.extensions.langchain4j")
    addLangChain4jAiStub("io.helidon.integrations.langchain4j")
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

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ChatModel {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface StreamingChatModel {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ChatMemoryProvider {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ModerationModel {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ContentRetriever {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface RetrievalAugmentor {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ToolProvider {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface McpClients {
          String[] value() default {};
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Tools {
          Class<?>[] value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.PARAMETER})
        public @interface Tool {
        }
      }
    """.trimIndent())
  }

  private fun addLangChain4jApplicationClasses() {
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      @Ai.Service("greeter")
      @Ai.ChatModel("chat")
      interface GreetingAiService {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      @Ai.Service
      interface DefaultNamedAiService {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;

      @Ai.Agent("planner")
      @Ai.McpClients("filesystem")
      interface PlanningAgent {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      class CalendarTools {
      }
    """.trimIndent())
  }
}
