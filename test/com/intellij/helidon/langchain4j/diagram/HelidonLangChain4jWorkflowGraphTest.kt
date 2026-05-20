// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j.diagram

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiClass
import com.intellij.testFramework.PsiTestUtil

class HelidonLangChain4jWorkflowGraphTest : HelidonHighlightingTestCase() {
  fun testBuildsWorkflowGraphFromLangChain4jYamlAndAnnotations() {
    addLangChain4jStubs()
    addApplicationClasses()
    val file = myFixture.configureByText("application-dev.yaml", """
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
    val agentNode = graph.nodes.single {
      it.kind == HelidonLangChain4jDiagramNodeKind.JAVA_AGENT && it.name == "HelidonAgent"
    }
    val resolvedAgent = HelidonLangChain4jWorkflowGraphBuilder.findElement(project, agentNode.id)
    val resolvedLegacyAgent = HelidonLangChain4jWorkflowGraphBuilder.findElement(project, "java:AGENT:demo.HelidonAgent")
    val agentConfig = graph.nodes.single {
      it.kind == HelidonLangChain4jDiagramNodeKind.AGENT_CONFIG && it.name == "helidon-agent"
    }
    val resolvedAgentConfig = HelidonLangChain4jWorkflowGraphBuilder.findElement(project, agentConfig.id)
    val configIdParts = agentConfig.id.split(':')

    assertNode(graph, HelidonLangChain4jDiagramNodeKind.SERVICE_CONFIG, "assistant-service")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.AGENT_CONFIG, "helidon-agent")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.MODEL_CONFIG, "assistant-model")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.PROVIDER_CONFIG, "openai")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.CONTENT_RETRIEVER_CONFIG, "docs")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.MCP_CLIENT_CONFIG, "filesystem (files)")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_SERVICE, "AssistantService")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, "HelidonAgent")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_CHAT_MODEL, "AssistantModel")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_CHAT_MEMORY_PROVIDER, "ConversationMemory")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_TOOL_PROVIDER, "CliToolProvider")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_CLASS, "CalendarTools")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_CLASS, "InputGuardrail")
    assertFalse("MCP client usages should not be rendered as declared Java components",
                graph.nodes.any { it.kind == HelidonLangChain4jDiagramNodeKind.JAVA_MCP_CLIENTS })
    assertEquals(HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, resolvedAgent?.kind)
    assertEquals("HelidonAgent", resolvedAgent?.name)
    assertFalse(resolvedAgent?.includeTests ?: true)
    assertEquals(HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, resolvedLegacyAgent?.kind)
    assertEquals("config", configIdParts.first())
    assertEquals("Config diagram IDs must include scope, module, section, and runtime key", 5, configIdParts.size)
    assertEquals(module, resolvedAgentConfig?.module)
    assertFalse(resolvedAgentConfig?.includeTests ?: true)
    assertEquals("helidon-agent", resolvedAgentConfig?.name)

    assertEdge(graph, "assistant-service", "assistant-model", "chat-model")
    assertEdge(graph, "assistant-service", "AssistantService", "declares")
    assertEdge(graph, "assistant-model", "openai", "provider")
    assertEdge(graph, "docs", "assistant-model", "embedding-model")
    assertEdge(graph, "docs", "pg", "embedding-store")
    assertEdge(graph, "helidon-agent", "filesystem (files)", "mcp-clients")
    assertEdge(graph, "helidon-agent", "CalendarTools", "tools")
    assertEdge(graph, "helidon-agent", "InputGuardrail", "input-guardrails")
    assertFalse("MCP client config entries should not declare classes that only use the client",
                graph.edges.any {
                  it.source.kind == HelidonLangChain4jDiagramNodeKind.MCP_CLIENT_CONFIG &&
                    it.target.name == "HelidonAgent" &&
                    it.label == "declares"
                })
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
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.ROOT, "langchain4j")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.AGENT_CONFIG, "helidon-agent")
    assertEdge(graph, "langchain4j", "helidon-agent", "agents")
    assertEdge(graph, "helidon-agent", "HelidonAgent", "declares")
    assertFalse("Java seeds should not become the config root container",
                graph.edges.any {
                  it.source.kind == HelidonLangChain4jDiagramNodeKind.JAVA_AGENT &&
                    it.target.kind == HelidonLangChain4jDiagramNodeKind.AGENT_CONFIG &&
                    it.label == "agents"
                })
  }

  fun testYamlSeedRequiresHelidonConfigFile() {
    addLangChain4jStubs()
    val file = myFixture.configureByText("notes.yaml", """
      langchain4j:
        agents:
          assistant:
            chat-model: demo
    """.trimIndent())

    assertNull(HelidonLangChain4jWorkflowGraphBuilder.seedFromPsiElement(file))
  }

  fun testRestoredDiagramElementKeepsTestScope() {
    addLangChain4jStubs()
    configureMavenLikeRoots()
    val file = myFixture.addFileToProject("src/test/resources/$HELIDON_APPLICATION_YAML", """
      langchain4j:
        agents:
          test-agent:
            chat-model: test-model
    """.trimIndent())

    val seed = HelidonLangChain4jWorkflowGraphBuilder.seedFromPsiElement(file)!!
    val restored = HelidonLangChain4jWorkflowGraphBuilder.findElement(project, seed.id)

    assertTrue(seed.includeTests)
    assertTrue(restored?.includeTests ?: false)
    assertEquals(module, restored?.module)
  }

  fun testRobotIconIsLimitedToAgentsAndServices() {
    val provider = HelidonLangChain4jDiagramProvider()
    val elementManager = provider.elementManager

    assertSame(HelidonIcons.Helidon, provider.getActionIcon(false))
    assertSame(HelidonIcons.RobotGutter,
               elementManager.getItemIcon(diagramElement(HelidonLangChain4jDiagramNodeKind.JAVA_AGENT), null, null))
    assertSame(HelidonIcons.RobotGutter,
               elementManager.getItemIcon(diagramElement(HelidonLangChain4jDiagramNodeKind.JAVA_SERVICE), null, null))
    assertSame(HelidonIcons.RobotGutter,
               elementManager.getItemIcon(diagramElement(HelidonLangChain4jDiagramNodeKind.AGENT_CONFIG), null, null))
    assertSame(HelidonIcons.RobotGutter,
               elementManager.getItemIcon(diagramElement(HelidonLangChain4jDiagramNodeKind.SERVICE_CONFIG), null, null))
    assertSame(HelidonIcons.HelidonGutter,
               elementManager.getItemIcon(diagramElement(HelidonLangChain4jDiagramNodeKind.ENDPOINT), null, null))
    assertSame(HelidonIcons.AiGutter,
               elementManager.getItemIcon(diagramElement(HelidonLangChain4jDiagramNodeKind.MODEL_CONFIG), null, null))
  }

  fun testAgenticWorkflowUsesAgentBoxesAndMetadataRows() {
    addLangChain4jStubs()
    addAgenticStubs()
    addEndpointStub()
    addAgenticWorkflowClasses()
    val file = myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        models:
          cheap-model:
            provider: open-ai
          expensive-model:
            provider: open-ai
        content-retrievers:
          se-content-retriever:
            embedding-store: se-embedding-store
          mp-content-retriever:
            embedding-store: mp-embedding-store
    """.trimIndent())

    val seed = HelidonLangChain4jWorkflowGraphBuilder.seedFromPsiElement(file)!!
    val graph = HelidonLangChain4jWorkflowGraphBuilder.build(seed)

    assertNode(graph, HelidonLangChain4jDiagramNodeKind.ENDPOINT, "ChatBotEndpoint")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.ENDPOINT, "SummaryEndpoint")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, "HelidonExpertAgent")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, "FlavorClassifierAgent")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, "FlavorRouterAgent")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, "SummarizerAgent")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, "HelidonSeExpert")
    assertNode(graph, HelidonLangChain4jDiagramNodeKind.JAVA_AGENT, "HelidonMpExpert")
    assertNodePsiClass(graph, "ChatBotEndpoint", "demo.ChatBotEndpoint")
    assertNodePsiClass(graph, "HelidonExpertAgent", "demo.HelidonExpertAgent")
    assertNodePsiClass(graph, "FlavorClassifierAgent", "demo.FlavorClassifierAgent")
    assertNodePsiClass(graph, "FlavorRouterAgent", "demo.FlavorRouterAgent")
    assertNodePsiClass(graph, "SummarizerAgent", "demo.SummarizerAgent")
    assertNodePsiClass(graph, "HelidonSeExpert", "demo.HelidonSeExpert")
    assertNodePsiClass(graph, "HelidonMpExpert", "demo.HelidonMpExpert")

    assertTrue("Agentic overview should only render agents and service entrypoints as graph nodes",
               graph.nodes.all {
                 it.kind == HelidonLangChain4jDiagramNodeKind.JAVA_AGENT ||
                   it.kind == HelidonLangChain4jDiagramNodeKind.ENDPOINT
               })
    assertTrue("Agentic overview should not render a non-agent group box",
               graph.nodes.all { it.group == null })
    listOf("open-ai", "cheap-model", "expensive-model", "se-content-retriever", "mp-content-retriever", "CliTools")
      .forEach { rawNode ->
        assertFalse("Agentic overview should not render $rawNode as a graph node",
                    graph.nodes.any { it.name == rawNode })
      }

    assertEdge(graph, "ChatBotEndpoint", "HelidonExpertAgent", "question + previousSummary")
    assertEdge(graph, "HelidonExpertAgent", "FlavorClassifierAgent", "sequence")
    assertEdge(graph, "FlavorClassifierAgent", "FlavorRouterAgent", "1) flavor")
    assertEdge(graph, "FlavorRouterAgent", "HelidonSeExpert", "2a) if se expert")
    assertEdge(graph, "FlavorRouterAgent", "HelidonMpExpert", "2b) if mp expert")
    assertEdge(graph, "FlavorRouterAgent", "SummarizerAgent", "3) summarizer")
    assertEdge(graph, "HelidonSeExpert", "SummarizerAgent", "lastResponse")
    assertEdge(graph, "HelidonMpExpert", "SummarizerAgent", "lastResponse")
    assertEdge(graph, "SummaryEndpoint", "SummarizerAgent", "previousSummary + question + lastResponse")
    assertFalse("Agentic metadata should replace resource edges",
                graph.edges.any { it.kind == HelidonLangChain4jWorkflowEdgeKind.RESOURCE })

    assertItem(graph, "ChatBotEndpoint", "input", "question, previousSummary")
    assertItem(graph, "HelidonExpertAgent", "input", "question, previousSummary")
    assertItem(graph, "HelidonExpertAgent", "output", "jsonResponse")
    assertItem(graph, "FlavorClassifierAgent", "input", "question, previousSummary")
    assertItem(graph, "FlavorClassifierAgent", "output", "flavor")
    assertItem(graph, "FlavorClassifierAgent", "model", "cheap-model")
    assertItem(graph, "FlavorRouterAgent", "input", "question, flavor")
    assertItem(graph, "FlavorRouterAgent", "output", "lastResponse")
    assertItem(graph, "HelidonSeExpert", "input", "question")
    assertItem(graph, "HelidonSeExpert", "output", "lastResponse")
    assertItem(graph, "HelidonSeExpert", "model", "expensive-model")
    assertItem(graph, "HelidonSeExpert", "retriever", "se-content-retriever")
    assertItem(graph, "HelidonSeExpert", "tools", "CliTools")
    assertItem(graph, "SummarizerAgent", "input", "previousSummary, question, lastResponse")
    assertItem(graph, "SummarizerAgent", "output", "nextSummary")
    assertItem(graph, "SummarizerAgent", "model", "cheap-model")
  }

  fun testAgenticWorkflowNumbersConditionalBranchesBySequencePosition() {
    addLangChain4jStubs()
    addAgenticStubs()
    addSequenceStartingWithConditionalRouterClasses()
    val file = myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
    """.trimIndent())

    val seed = HelidonLangChain4jWorkflowGraphBuilder.seedFromPsiElement(file)!!
    val graph = HelidonLangChain4jWorkflowGraphBuilder.build(seed)

    assertEdge(graph, "FastSequenceAgent", "FlavorRouterAgent", "sequence")
    assertEdge(graph, "FlavorRouterAgent", "HelidonSeExpert", "1a) if se expert")
    assertEdge(graph, "FlavorRouterAgent", "HelidonMpExpert", "1b) if mp expert")
    assertEdge(graph, "FlavorRouterAgent", "SummarizerAgent", "2) summarizer")
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
                         label: String,
                         kind: HelidonLangChain4jWorkflowEdgeKind = HelidonLangChain4jWorkflowEdgeKind.FLOW) {
    assertTrue("Expected edge $sourceName -[$label]-> $targetName, got ${graph.edges.map { "${it.source.name} -[${it.label}]-> ${it.target.name}" }}",
               graph.edges.any { it.source.name == sourceName && it.target.name == targetName && it.label == label && it.kind == kind })
  }

  private fun assertNodePsiClass(graph: HelidonLangChain4jWorkflowGraph,
                                 nodeName: String,
                                 qualifiedName: String) {
    val node = graph.nodes.firstOrNull { it.name == nodeName }
    if (node == null) {
      fail("Expected node $nodeName, got ${graph.nodes.map { it.name }}")
      return
    }
    val psiClass = node.psiElement as? PsiClass
    assertEquals("Expected $nodeName to navigate to a Java class", qualifiedName, psiClass?.qualifiedName)
  }

  private fun assertItem(graph: HelidonLangChain4jWorkflowGraph,
                         nodeName: String,
                         key: String,
                         value: String) {
    val node = graph.nodes.firstOrNull { it.name == nodeName }
    if (node == null) {
      fail("Expected node $nodeName, got ${graph.nodes.map { it.name }}")
      return
    }
    assertTrue("Expected item $key '$value' on $nodeName, got ${node.items}",
               node.items.any { it.key == key && it.value == value })
  }

  private fun configureMavenLikeRoots() {
    val mainResources = myFixture.tempDirFixture.findOrCreateDir("src/main/resources")
    val testResources = myFixture.tempDirFixture.findOrCreateDir("src/test/resources")

    PsiTestUtil.addResourceContentToRoots(module, mainResources, false)
    PsiTestUtil.addResourceContentToRoots(module, testResources, true)

    Disposer.register(myFixture.testRootDisposable,
                      Disposable {
                        PsiTestUtil.removeContentEntry(module, mainResources)
                        PsiTestUtil.removeContentEntry(module, testResources)
                      })
  }

  private fun diagramElement(kind: HelidonLangChain4jDiagramNodeKind): HelidonLangChain4jDiagramElement {
    return HelidonLangChain4jDiagramElement(
      id = "test:${kind.name}",
      name = kind.presentableName,
      kind = kind,
      psiElement = null,
      module = null,
      includeTests = false,
    )
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

  private fun addAgenticWorkflowClasses() {
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.declarative.SequenceAgent;
      import dev.langchain4j.service.V;

      @Ai.Agent("helidon-expert")
      interface HelidonExpertAgent {
        @SequenceAgent(outputKey = "jsonResponse", subAgents = {
          FlavorClassifierAgent.class,
          FlavorRouterAgent.class,
          SummarizerAgent.class
        })
        String chat(@V("question") String question, @V("previousSummary") String previousSummary);
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.Agent;
      import dev.langchain4j.service.V;

      @Ai.Agent("flavor-classifier")
      @Ai.ChatModel("cheap-model")
      interface FlavorClassifierAgent {
        @Agent(value = "Categorize a user request", outputKey = "flavor")
        String classify(@V("question") String question, @V("previousSummary") String previousSummary);
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.declarative.ActivationCondition;
      import dev.langchain4j.agentic.declarative.ConditionalAgent;
      import dev.langchain4j.service.V;

      @Ai.Agent("flavor-router")
      interface FlavorRouterAgent {
        @ConditionalAgent(subAgents = {
          HelidonMpExpert.class,
          HelidonSeExpert.class
        })
        String askExpert(@V("question") String question);

        @ActivationCondition(HelidonSeExpert.class)
        static boolean activateSeExpert(@V("flavor") String flavor) {
          return true;
        }

        @ActivationCondition(HelidonMpExpert.class)
        static boolean activateMpExpert(@V("flavor") String flavor) {
          return true;
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import demo.tools.CliTools;
      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.Agent;
      import dev.langchain4j.service.V;

      @Ai.Agent("helidon-se-expert")
      @Ai.ChatModel("expensive-model")
      @Ai.ContentRetriever("se-content-retriever")
      @Ai.Tools(CliTools.class)
      interface HelidonSeExpert {
        @Agent(value = "A Helidon SE expert", outputKey = "lastResponse")
        String askExpert(@V("question") String question);
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import demo.tools.CliTools;
      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.Agent;
      import dev.langchain4j.service.V;

      @Ai.Agent("helidon-mp-expert")
      @Ai.ChatModel("expensive-model")
      @Ai.ContentRetriever("mp-content-retriever")
      @Ai.Tools(CliTools.class)
      interface HelidonMpExpert {
        @Agent(value = "A Helidon MP expert", outputKey = "lastResponse")
        String askExpert(@V("question") String question);
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.Agent;
      import dev.langchain4j.service.V;

      @Ai.Agent("summarizer")
      @Ai.ChatModel("cheap-model")
      interface SummarizerAgent {
        @Agent(value = "A Helidon expert summarizer", outputKey = "nextSummary")
        String chat(@V("previousSummary") String previousSummary,
                    @V("question") String question,
                    @V("lastResponse") String lastResponse);
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import demo.HelidonExpertAgent;
      import io.helidon.webserver.http.RestServer;
      import java.util.function.Supplier;

      @RestServer.Endpoint
      class ChatBotEndpoint {
        private final HelidonExpertAgent agent;

        ChatBotEndpoint(HelidonExpertAgent agent) {
          this.agent = agent;
        }
      }

      @RestServer.Endpoint
      class SummaryEndpoint {
        private final Supplier<SummarizerAgent> agent;

        SummaryEndpoint(Supplier<SummarizerAgent> agent) {
          this.agent = agent;
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo.tools;

      public class CliTools {
      }
    """.trimIndent())
  }

  private fun addSequenceStartingWithConditionalRouterClasses() {
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.declarative.SequenceAgent;
      import dev.langchain4j.service.V;

      @Ai.Agent("fast-sequence")
      interface FastSequenceAgent {
        @SequenceAgent(outputKey = "jsonResponse", subAgents = {
          FlavorRouterAgent.class,
          SummarizerAgent.class
        })
        String chat(@V("question") String question, @V("flavor") String flavor);
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.declarative.ActivationCondition;
      import dev.langchain4j.agentic.declarative.ConditionalAgent;
      import dev.langchain4j.service.V;

      @Ai.Agent("flavor-router")
      interface FlavorRouterAgent {
        @ConditionalAgent(subAgents = {
          HelidonMpExpert.class,
          HelidonSeExpert.class
        })
        String askExpert(@V("question") String question);

        @ActivationCondition(HelidonSeExpert.class)
        static boolean activateSeExpert(@V("flavor") String flavor) {
          return true;
        }

        @ActivationCondition(HelidonMpExpert.class)
        static boolean activateMpExpert(@V("flavor") String flavor) {
          return true;
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.Agent;
      import dev.langchain4j.service.V;

      @Ai.Agent("helidon-se-expert")
      interface HelidonSeExpert {
        @Agent(value = "A Helidon SE expert", outputKey = "lastResponse")
        String askExpert(@V("question") String question);
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.Agent;
      import dev.langchain4j.service.V;

      @Ai.Agent("helidon-mp-expert")
      interface HelidonMpExpert {
        @Agent(value = "A Helidon MP expert", outputKey = "lastResponse")
        String askExpert(@V("question") String question);
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;
      import dev.langchain4j.agentic.Agent;
      import dev.langchain4j.service.V;

      @Ai.Agent("summarizer")
      interface SummarizerAgent {
        @Agent(value = "A Helidon expert summarizer", outputKey = "nextSummary")
        String chat(@V("question") String question, @V("lastResponse") String lastResponse);
      }
    """.trimIndent())
  }

  private fun addAgenticStubs() {
    myFixture.addClass("""
      package dev.langchain4j.agentic;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Retention(RetentionPolicy.RUNTIME)
      @Target(ElementType.METHOD)
      public @interface Agent {
        String value() default "";
        String outputKey() default "";
      }
    """.trimIndent())
    myFixture.addClass("""
      package dev.langchain4j.agentic.declarative;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Retention(RetentionPolicy.RUNTIME)
      @Target(ElementType.METHOD)
      public @interface SequenceAgent {
        Class<?>[] subAgents();
        String outputKey() default "";
      }
    """.trimIndent())
    myFixture.addClass("""
      package dev.langchain4j.agentic.declarative;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Retention(RetentionPolicy.RUNTIME)
      @Target(ElementType.METHOD)
      public @interface ConditionalAgent {
        Class<?>[] subAgents();
      }
    """.trimIndent())
    myFixture.addClass("""
      package dev.langchain4j.agentic.declarative;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Retention(RetentionPolicy.RUNTIME)
      @Target(ElementType.METHOD)
      public @interface ActivationCondition {
        Class<?> value();
      }
    """.trimIndent())
    myFixture.addClass("""
      package dev.langchain4j.service;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Retention(RetentionPolicy.RUNTIME)
      @Target(ElementType.PARAMETER)
      public @interface V {
        String value();
      }
    """.trimIndent())
  }

  private fun addEndpointStub() {
    myFixture.addClass("""
      package io.helidon.webserver.http;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      public final class RestServer {
        private RestServer() {
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Endpoint {
        }
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

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Tools {
          Class<?>[] value();
        }
      }
    """.trimIndent())
  }
}
