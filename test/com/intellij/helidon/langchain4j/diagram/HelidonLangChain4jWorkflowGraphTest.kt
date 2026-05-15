// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j.diagram

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML

class HelidonLangChain4jWorkflowGraphTest : HelidonHighlightingTestCase() {
  fun testBuildsWorkflowGraphFromLangChain4jYamlAndAnnotations() {
    addLangChain4jStubs()
    addApplicationClasses()
    val file = myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        services:
          assistant-service:
            chat-model: assistant-model
            chat-memory-provider: conversation-memory
            tool-provider: cli-tool-provider
        agents:
          helidon-agent:
            chat-model: assistant-model
            content-retriever: docs
            mcp-clients:
              - filesystem
            tools:
              - demo.CalendarTools
            input-guardrails:
              - demo.InputGuardrail
        models:
          assistant-model:
            provider: openai
        providers:
          openai:
            type: open-ai
        embedding-stores:
          pg:
            provider: pgvector
        content-retrievers:
          docs:
            embedding-model: assistant-model
            embedding-store: pg
        mcp-clients:
          files:
            key: filesystem
    """.trimIndent())

    val seed = HelidonLangChain4jWorkflowGraphBuilder.seedFromPsiElement(file)!!
    val graph = HelidonLangChain4jWorkflowGraphBuilder.build(seed)

    assertNode(graph, HelidonLangChain4jDiagramNodeKind.SERVICE_CONFIG, "assistant-service")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.AGENT_CONFIG, "helidon-agent")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.MODEL_CONFIG, "assistant-model")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.PROVIDER_CONFIG, "openai")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.CONTENT_RETRIEVER_CONFIG, "docs")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.MCP_CLIENT_CONFIG, "filesystem (files)")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_SERVICE, "demo.AssistantService")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, "demo.HelidonAgent")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_CHAT_MODEL, "demo.AssistantModel")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_CHAT_MEMORY_PROVIDER, "demo.ConversationMemory")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_TOOL_PROVIDER, "demo.CliToolProvider")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_CLASS, "demo.CalendarTools")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_CLASS, "demo.InputGuardrail")

    assertEdge(graph, "assistant-service", "assistant-model", "chat-model")
    assertEdge(graph, "assistant-service", "demo.AssistantService", "declares")
    assertEdge(graph, "assistant-model", "openai", "provider")
    assertEdge(graph, "docs", "assistant-model", "embedding-model")
    assertEdge(graph, "docs", "pg", "embedding-store")
    assertEdge(graph, "helidon-agent", "filesystem (files)", "mcp-clients")
    assertEdge(graph, "helidon-agent", "demo.CalendarTools", "tools")
    assertEdge(graph, "helidon-agent", "demo.InputGuardrail", "input-guardrails")
  }

  fun testSeedCanBeLangChain4jAnnotatedClass() {
    addLangChain4jStubs()
    addApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        agents:
          helidon-agent:
            chat-model: assistant-model
        models:
          assistant-model:
            provider: openai
        providers:
          openai:
            type: open-ai
    """.trimIndent())
    val agentClass = myFixture.findClass("demo.HelidonAgent")

    val seed = HelidonLangChain4jWorkflowGraphBuilder.seedFromPsiElement(agentClass.nameIdentifier!!)!!
    val graph = HelidonLangChain4jWorkflowGraphBuilder.build(seed)

    assertEquals(HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, seed.kind)
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.AGENT_CONFIG, "helidon-agent")
    assertEdge(graph, "helidon-agent", "demo.HelidonAgent", "declares")
  }

  private fun assertNode(graph: HelidonLangChain4jWorkflowGraph,
                         kind: HelidonLangChain4jDiagramNodeKind,
                         name: String) {
    assertTrue("Expected node $kind '$name', got ${graph.nodes.map { "${it.kind}:${it.name}" }}",
               graph.nodes.any { it.kind == kind && it.name == name })
  }

  private fun assertEdge(graph: HelidonLangChain4jWorkflowGraph,
                         sourceName: String,
                         targetName: String,
                         label: String) {
    assertTrue("Expected edge $sourceName -[$label]-> $targetName, got ${graph.edges.map { "${it.source.name} -[${it.label}]-> ${it.target.name}" }}",
               graph.edges.any { it.source.name == sourceName && it.target.name == targetName && it.label == label })
  }

  private fun addApplicationClasses() {
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      @Ai.Service("assistant-service")
      @Ai.ChatModel("assistant-model")
      @Ai.ChatMemoryProvider("conversation-memory")
      @Ai.ToolProvider("cli-tool-provider")
      interface AssistantService {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;

      @Ai.Agent("helidon-agent")
      @Ai.McpClients("filesystem")
      interface HelidonAgent {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      @Ai.ChatModel("assistant-model")
      interface AssistantModel {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      @Ai.ChatMemoryProvider("conversation-memory")
      interface ConversationMemory {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;

      @Ai.ToolProvider("cli-tool-provider")
      interface CliToolProvider {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      public class CalendarTools {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      public class InputGuardrail {
      }
    """.trimIndent())
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
      }
    """.trimIndent())
  }
}
