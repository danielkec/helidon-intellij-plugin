// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j.diagram

import com.intellij.helidon.constants.HelidonConstants
import com.intellij.helidon.config.HelidonConfigFileContributor
import com.intellij.helidon.config.yaml.HelidonConfigYamlAccessor
import com.intellij.helidon.config.yaml.getQualifiedConfigKeyName
import com.intellij.helidon.config.yaml.isInsideApplicationYamlFile
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val HELIDON_LANGCHAIN4J_DIAGRAM_ID = "HelidonLangChain4jWorkflow"

private const val ROOT = "langchain4j"
private const val SERVICES = "services"
private const val AGENTS = "agents"
private const val MODELS = "models"
private const val PROVIDERS = "providers"
private const val EMBEDDING_STORES = "embedding-stores"
private const val CONTENT_RETRIEVERS = "content-retrievers"
private const val MCP_CLIENTS = "mcp-clients"
private const val VALUE_ATTRIBUTE = "value"
private const val SUB_AGENTS_ATTRIBUTE = "subAgents"
private const val OUTPUT_KEY_ATTRIBUTE = "outputKey"
private const val SEQUENCE_AGENT = "dev.langchain4j.agentic.declarative.SequenceAgent"
private const val CONDITIONAL_AGENT = "dev.langchain4j.agentic.declarative.ConditionalAgent"
private const val ACTIVATION_CONDITION = "dev.langchain4j.agentic.declarative.ActivationCondition"
private const val AGENTIC_AGENT = "dev.langchain4j.agentic.Agent"
private const val SERVICE_VALUE = "dev.langchain4j.service.V"

internal class HelidonLangChain4jDiagramElement(
  val id: String,
  val name: String,
  val kind: HelidonLangChain4jDiagramNodeKind,
  val psiElement: PsiElement?,
  val module: Module?,
  val includeTests: Boolean,
  val group: String? = null,
  val items: List<HelidonLangChain4jDiagramItem> = emptyList(),
) {
  override fun equals(other: Any?): Boolean = other is HelidonLangChain4jDiagramElement && id == other.id

  override fun hashCode(): Int = id.hashCode()

  override fun toString(): String = "$name (${kind.presentableName})"
}

internal data class HelidonLangChain4jDiagramItem(
  val key: String,
  val value: String,
)

internal enum class HelidonLangChain4jDiagramNodeKind(val presentableName: String) {
  ROOT("LangChain4j config"),
  ENDPOINT("Endpoint"),
  SERVICE_CONFIG("AI service config"),
  AGENT_CONFIG("Agent config"),
  MODEL_CONFIG("Model config"),
  PROVIDER_CONFIG("Provider config"),
  EMBEDDING_STORE_CONFIG("Embedding store config"),
  CONTENT_RETRIEVER_CONFIG("Content retriever config"),
  MCP_CLIENT_CONFIG("MCP client config"),
  JAVA_SERVICE("@Ai.Service"),
  JAVA_AGENT("@Ai.Agent"),
  JAVA_CHAT_MODEL("@Ai.ChatModel"),
  JAVA_STREAMING_CHAT_MODEL("@Ai.StreamingChatModel"),
  JAVA_MODERATION_MODEL("@Ai.ModerationModel"),
  JAVA_CHAT_MEMORY_PROVIDER("@Ai.ChatMemoryProvider"),
  JAVA_CONTENT_RETRIEVER("@Ai.ContentRetriever"),
  JAVA_RETRIEVAL_AUGMENTOR("@Ai.RetrievalAugmentor"),
  JAVA_TOOL_PROVIDER("@Ai.ToolProvider"),
  JAVA_MCP_CLIENTS("@Ai.McpClients"),
  JAVA_CLASS("Java class"),
  EXTERNAL_COMPONENT("External component"),
}

internal data class HelidonLangChain4jWorkflowEdge(
  val source: HelidonLangChain4jDiagramElement,
  val target: HelidonLangChain4jDiagramElement,
  val label: String,
  val navigationElement: PsiElement?,
  val kind: HelidonLangChain4jWorkflowEdgeKind = HelidonLangChain4jWorkflowEdgeKind.FLOW,
)

internal enum class HelidonLangChain4jWorkflowEdgeKind {
  FLOW,
  RESOURCE,
}

internal data class HelidonLangChain4jWorkflowGraph(
  val nodes: List<HelidonLangChain4jDiagramElement>,
  val edges: List<HelidonLangChain4jWorkflowEdge>,
)

internal object HelidonLangChain4jWorkflowGraphBuilder {
  private val CONFIG_NODE_KINDS = mapOf(
    SERVICES to HelidonLangChain4jDiagramNodeKind.SERVICE_CONFIG,
    AGENTS to HelidonLangChain4jDiagramNodeKind.AGENT_CONFIG,
    MODELS to HelidonLangChain4jDiagramNodeKind.MODEL_CONFIG,
    PROVIDERS to HelidonLangChain4jDiagramNodeKind.PROVIDER_CONFIG,
    EMBEDDING_STORES to HelidonLangChain4jDiagramNodeKind.EMBEDDING_STORE_CONFIG,
    CONTENT_RETRIEVERS to HelidonLangChain4jDiagramNodeKind.CONTENT_RETRIEVER_CONFIG,
    MCP_CLIENTS to HelidonLangChain4jDiagramNodeKind.MCP_CLIENT_CONFIG,
  )

  private val CONFIG_TO_COMPONENT_KINDS = mapOf(
    SERVICES to setOf(ComponentKind.SERVICE),
    AGENTS to setOf(ComponentKind.AGENT),
    MODELS to setOf(ComponentKind.CHAT_MODEL, ComponentKind.STREAMING_CHAT_MODEL, ComponentKind.MODERATION_MODEL),
    CONTENT_RETRIEVERS to setOf(ComponentKind.CONTENT_RETRIEVER),
  )

  private val VALUE_CONFIG_TARGETS = mapOf(
    "provider" to PROVIDERS,
    "chat-model" to MODELS,
    "streaming-chat-model" to MODELS,
    "moderation-model" to MODELS,
    "embedding-model" to MODELS,
    "embedding-store" to EMBEDDING_STORES,
    "content-retriever" to CONTENT_RETRIEVERS,
  )

  private val VALUE_COMPONENT_TARGETS = mapOf(
    "chat-model" to setOf(ComponentKind.CHAT_MODEL),
    "streaming-chat-model" to setOf(ComponentKind.STREAMING_CHAT_MODEL),
    "moderation-model" to setOf(ComponentKind.MODERATION_MODEL),
    "chat-memory-provider" to setOf(ComponentKind.CHAT_MEMORY_PROVIDER),
    "content-retriever" to setOf(ComponentKind.CONTENT_RETRIEVER),
    "retrieval-augmentor" to setOf(ComponentKind.RETRIEVAL_AUGMENTOR),
    "tool-provider" to setOf(ComponentKind.TOOL_PROVIDER),
  )

  private val CLASS_VALUED_KEYS = setOf("tools", "input-guardrails", "output-guardrails")

  private val ANNOTATION_KINDS = mapOf(
    ComponentKind.SERVICE to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_SERVICE,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_SERVICE,
    ),
    ComponentKind.AGENT to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_AGENT,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_AGENT,
    ),
    ComponentKind.CHAT_MODEL to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CHAT_MODEL,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MODEL,
    ),
    ComponentKind.STREAMING_CHAT_MODEL to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_STREAMING_CHAT_MODEL,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_STREAMING_CHAT_MODEL,
    ),
    ComponentKind.CHAT_MEMORY_PROVIDER to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CHAT_MEMORY_PROVIDER,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MEMORY_PROVIDER,
    ),
    ComponentKind.MODERATION_MODEL to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_MODERATION_MODEL,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_MODERATION_MODEL,
    ),
    ComponentKind.CONTENT_RETRIEVER to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CONTENT_RETRIEVER,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CONTENT_RETRIEVER,
    ),
    ComponentKind.RETRIEVAL_AUGMENTOR to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_RETRIEVAL_AUGMENTOR,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_RETRIEVAL_AUGMENTOR,
    ),
    ComponentKind.TOOL_PROVIDER to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_TOOL_PROVIDER,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_TOOL_PROVIDER,
    ),
  )

  private val ANNOTATION_CONFIG_SECTIONS = mapOf(
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_SERVICE to SERVICES,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_SERVICE to SERVICES,
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_AGENT to AGENTS,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_AGENT to AGENTS,
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CHAT_MODEL to MODELS,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MODEL to MODELS,
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_STREAMING_CHAT_MODEL to MODELS,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_STREAMING_CHAT_MODEL to MODELS,
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_MODERATION_MODEL to MODELS,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_MODERATION_MODEL to MODELS,
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CONTENT_RETRIEVER to CONTENT_RETRIEVERS,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CONTENT_RETRIEVER to CONTENT_RETRIEVERS,
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_MCP_CLIENTS to MCP_CLIENTS,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_MCP_CLIENTS to MCP_CLIENTS,
  )

  private val ANNOTATION_CONFIG_VALUE_KEYS = mapOf(
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CHAT_MEMORY_PROVIDER to setOf("chat-memory-provider"),
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MEMORY_PROVIDER to setOf("chat-memory-provider"),
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_RETRIEVAL_AUGMENTOR to setOf("retrieval-augmentor"),
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_RETRIEVAL_AUGMENTOR to setOf("retrieval-augmentor"),
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_TOOL_PROVIDER to setOf("tool-provider"),
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_TOOL_PROVIDER to setOf("tool-provider"),
  )

  fun seedFromPsiElement(element: PsiElement): HelidonLangChain4jDiagramElement? {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
    if (!HelidonCoreUtils.hasHelidonLibrary(module)) return null
    val includeTests = includeTestSources(element, module)

    val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
    val component = psiClass?.let(::componentFromClass)
    if (component != null) {
      return component.toDiagramElement(module, includeTests)
    }

    val yamlKeyValue = when (element) {
      is YAMLKeyValue -> element
      else -> PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
    }
    if (yamlKeyValue != null &&
        isInsideApplicationYamlFile(yamlKeyValue) &&
        getQualifiedConfigKeyName(yamlKeyValue).let { it == ROOT || it.startsWith("$ROOT.") }) {
      return rootElement(module, includeTests, yamlKeyValue)
    }

    val yamlFile = element.containingFile as? YAMLFile
    if (yamlFile != null &&
        isInsideApplicationYamlFile(yamlFile) &&
        yamlFile.documents.any { HelidonConfigYamlAccessor(it).findExistingKey(ROOT) != null }) {
      return rootElement(module, includeTests, yamlFile)
    }

    return null
  }

  fun findElement(project: Project, id: String): HelidonLangChain4jDiagramElement? {
    parseDiagramId(id, "root", 2)?.let { parts ->
      val includeTests = includeTestsPart(parts[0]) ?: return null
      val moduleName = parts[1]
      return ModuleManager.getInstance(project).findModuleByName(moduleName)
        ?.let { rootElement(it, includeTests, psiElement = null) }
    }

    parseDiagramId(id, "java", 3)?.let { parts ->
      val includeTests = includeTestsPart(parts[0]) ?: return null
      val kind = ComponentKind.entries.firstOrNull { it.name == parts[1] } ?: return null
      val psiClass = findClass(project, parts[2]) ?: return null
      val module = ModuleUtilCore.findModuleForPsiElement(psiClass)
      return Component(kind, parts[2], psiClass).toDiagramElement(module, includeTests)
    }

    parseDiagramId(id, "java-class", 3)?.let { parts ->
      val includeTests = includeTestsPart(parts[0]) ?: return null
      val module = ModuleManager.getInstance(project).findModuleByName(parts[1]) ?: return null
      val psiClass = findClass(module, includeTests, parts[2]) ?: return null
      return javaClassElement(psiClass, module, includeTests)
    }

    parseDiagramId(id, "endpoint", 2)?.let { parts ->
      val includeTests = includeTestsPart(parts[0]) ?: return null
      val psiClass = findClass(project, parts[1]) ?: return null
      val module = ModuleUtilCore.findModuleForPsiElement(psiClass)
      return endpointElement(psiClass, module, includeTests, inputs = emptyList())
    }

    parseDiagramId(id, "config", 4)?.let { parts ->
      val includeTests = includeTestsPart(parts[0]) ?: return null
      val module = ModuleManager.getInstance(project).findModuleByName(parts[1]) ?: return null
      val section = parts[2]
      val runtimeKey = parts[3]
      val kind = CONFIG_NODE_KINDS[section] ?: return null
      findConfigEntry(module, includeTests, section, runtimeKey)?.let { entry ->
        return configElement(section, runtimeKey, entry.keyText, kind, entry, module, includeTests)
      }
    }

    return findLegacyElement(project, id)
  }

  private fun findLegacyElement(project: Project, id: String): HelidonLangChain4jDiagramElement? {
    if (id.startsWith("root:")) {
      val moduleName = id.substringAfter("root:")
      return ModuleManager.getInstance(project).findModuleByName(moduleName)
        ?.let { rootElement(it, includeTests = false, psiElement = null) }
    }

    if (id.startsWith("java:")) {
      val kindName = id.substringAfter("java:").substringBefore(':')
      val qualifiedName = id.substringAfter("java:$kindName:")
      val kind = ComponentKind.entries.firstOrNull { it.name == kindName } ?: return null
      val psiClass = findClass(project, qualifiedName) ?: return null
      val module = ModuleUtilCore.findModuleForPsiElement(psiClass)
      return Component(kind, qualifiedName, psiClass).toDiagramElement(module, includeTestsFor(psiClass, module))
    }

    if (id.startsWith("java-class:")) {
      val qualifiedName = id.substringAfter("java-class:")
      val psiClass = findClass(project, qualifiedName) ?: return null
      val module = ModuleUtilCore.findModuleForPsiElement(psiClass)
      return javaClassElement(psiClass, module, includeTestsFor(psiClass, module))
    }

    if (id.startsWith("endpoint:")) {
      val qualifiedName = id.substringAfter("endpoint:")
      val psiClass = findClass(project, qualifiedName) ?: return null
      val module = ModuleUtilCore.findModuleForPsiElement(psiClass)
      return endpointElement(psiClass, module, includeTestsFor(psiClass, module), inputs = emptyList())
    }

    if (id.startsWith("config:")) {
      val tokens = id.split(':', limit = 3)
      if (tokens.size != 3) return null
      val section = tokens[1]
      val runtimeKey = tokens[2]
      val kind = CONFIG_NODE_KINDS[section] ?: return null
      for (module in ModuleManager.getInstance(project).modules) {
        findConfigEntry(module, includeTests = true, section, runtimeKey)?.let { entry ->
          return configElement(section, runtimeKey, entry.keyText, kind, entry, module, includeTests = true)
        }
      }
    }

    return null
  }

  fun build(seed: HelidonLangChain4jDiagramElement): HelidonLangChain4jWorkflowGraph {
    val module = seed.module ?: return HelidonLangChain4jWorkflowGraph(listOf(seed), emptyList())
    return Builder(module, seed.includeTests, seed).build()
  }

  private fun rootElement(module: Module, includeTests: Boolean, psiElement: PsiElement?): HelidonLangChain4jDiagramElement {
    return HelidonLangChain4jDiagramElement(
      id = diagramId("root", includeTestsPart(includeTests), module.name),
      name = "langchain4j",
      kind = HelidonLangChain4jDiagramNodeKind.ROOT,
      psiElement = psiElement,
      module = module,
      includeTests = includeTests,
    )
  }

  private class Builder(
    private val module: Module,
    private val includeTests: Boolean,
    private val seed: HelidonLangChain4jDiagramElement,
  ) {
    private val nodes = LinkedHashMap<String, HelidonLangChain4jDiagramElement>()
    private val edges = LinkedHashMap<GraphEdgeKey, GraphEdge>()
    private val configNodes = LinkedHashMap<Pair<String, String>, HelidonLangChain4jDiagramElement>()
    private val componentsByKindAndKey = LinkedHashMap<Pair<ComponentKind, String>, MutableList<Component>>()

    fun build(): HelidonLangChain4jWorkflowGraph {
      addNode(seed)
      val configFiles = applicationYamlFiles(module, includeTests)
      collectConfigNodes(configFiles)
      val components = collectComponents(module, includeTests)
      components.forEach(::addComponentNode)

      if (hasValidAgenticWorkflow(components)) {
        return buildAgenticWorkflow(components)
      }

      linkConfigNodesToComponents()
      collectConfigEdges(configFiles)
      collectAnnotationEdges(components)

      return toGraph()
    }

    private fun toGraph(): HelidonLangChain4jWorkflowGraph {
      return HelidonLangChain4jWorkflowGraph(
        nodes = nodes.values.toList(),
        edges = edges.values.mapNotNull { edge ->
          val source = nodes[edge.sourceId] ?: return@mapNotNull null
          val target = nodes[edge.targetId] ?: return@mapNotNull null
          HelidonLangChain4jWorkflowEdge(source, target, edge.label, edge.navigationElement, edge.kind)
        },
      )
    }

    private fun hasValidAgenticWorkflow(components: List<Component>): Boolean {
      return components.any { component ->
        component.kind == ComponentKind.AGENT &&
          (sequenceWorkflow(component.target) != null || conditionalWorkflow(component.target) != null)
      }
    }

    private fun buildAgenticWorkflow(components: List<Component>): HelidonLangChain4jWorkflowGraph {
      val configNodeSnapshot = LinkedHashMap(configNodes)
      nodes.clear()
      edges.clear()
      configNodes.clear()
      configNodes.putAll(configNodeSnapshot)

      val componentsByClass = components
        .filter { it.kind == ComponentKind.AGENT }
        .distinctBy { it.target }
        .associateBy { it.target }
      val sequenceWorkflows = componentsByClass.keys.mapNotNull(::sequenceWorkflow)
      val conditionalWorkflows = componentsByClass.keys.mapNotNull(::conditionalWorkflow).associateBy { it.agent }

      if (sequenceWorkflows.isEmpty()) {
        addConditionalOnlyWorkflows(componentsByClass, conditionalWorkflows)
      }
      else {
        for (workflow in sequenceWorkflows) {
          val owner = componentsByClass[workflow.agent]
          val sequenceAgents = workflow.subAgents.mapNotNull { componentsByClass[it] }
          val conditionalAgents = sequenceAgents.flatMap { component ->
            conditionalWorkflows[component.target]?.orderedSubAgents.orEmpty().map { it.agent }
          }
          val workflowAgents = (listOfNotNull(owner) + sequenceAgents + conditionalAgents.mapNotNull { componentsByClass[it] })
            .distinctBy { it.target }

          workflowAgents.forEach { addAgentNode(it, conditionalWorkflows) }
          addEndpointEdges(workflow, workflowAgents, owner, sequenceAgents.firstOrNull(), conditionalWorkflows)
          addSequenceEdges(workflow, owner, componentsByClass, conditionalWorkflows)
        }
      }

      if (nodes.isEmpty()) {
        addNode(seed)
      }

      return toGraph()
    }

    private fun addConditionalOnlyWorkflows(componentsByClass: Map<PsiClass, Component>,
                                            conditionalWorkflows: Map<PsiClass, ConditionalWorkflow>) {
      for (workflow in conditionalWorkflows.values) {
        val owner = componentsByClass[workflow.agent] ?: continue
        val branchAgents = workflow.orderedSubAgents.mapNotNull { componentsByClass[it.agent] }
        val workflowAgents = (listOf(owner) + branchAgents).distinctBy { it.target }

        workflowAgents.forEach { addAgentNode(it, conditionalWorkflows) }
        addConditionalEndpointEdges(workflowAgents, owner, conditionalWorkflows)
        addConditionalEdges(addAgentNode(owner, conditionalWorkflows), workflow, 1, componentsByClass, null, conditionalWorkflows)
      }
    }

    private fun addEndpointEdges(workflow: SequenceWorkflow,
                                 workflowAgents: List<Component>,
                                 owner: Component?,
                                 firstComponent: Component?,
                                 conditionalWorkflows: Map<PsiClass, ConditionalWorkflow>) {
      val fallbackComponent = owner ?: firstComponent ?: return
      val workflowAgentClasses = workflowAgents.map { it.target }.ifEmpty { listOf(fallbackComponent.target) }
      val endpointTargets = endpointClassesReferencing(workflowAgentClasses).ifEmpty {
        emptyList()
      }
      for ((endpoint, targetAgent) in endpointTargets) {
        val targetComponent = workflowAgents.firstOrNull { it.target == targetAgent } ?: fallbackComponent
        val targetNode = addAgentNode(targetComponent, conditionalWorkflows)
        val inputs = if (targetAgent == workflow.agent) workflow.inputKeys else agentInputKeys(targetAgent)
        val label = inputs.takeIf { it.isNotEmpty() }?.joinToString(" + ") ?: "input"
        val endpointNode = endpointElement(endpoint, module, includeTests, inputs)
        addNode(endpointNode)
        addEdge(endpointNode, targetNode, label, endpoint)
      }
    }

    private fun addConditionalEndpointEdges(workflowAgents: List<Component>,
                                            owner: Component,
                                            conditionalWorkflows: Map<PsiClass, ConditionalWorkflow>) {
      val workflowAgentClasses = workflowAgents.map { it.target }.ifEmpty { listOf(owner.target) }
      for ((endpoint, targetAgent) in endpointClassesReferencing(workflowAgentClasses)) {
        val targetComponent = workflowAgents.firstOrNull { it.target == targetAgent } ?: owner
        val targetNode = addAgentNode(targetComponent, conditionalWorkflows)
        val inputs = agentInputKeys(targetAgent)
        val label = inputs.takeIf { it.isNotEmpty() }?.joinToString(" + ") ?: "input"
        val endpointNode = endpointElement(endpoint, module, includeTests, inputs)
        addNode(endpointNode)
        addEdge(endpointNode, targetNode, label, endpoint)
      }
    }

    private fun addSequenceEdges(workflow: SequenceWorkflow,
                                 owner: Component?,
                                 componentsByClass: Map<PsiClass, Component>,
                                 conditionalWorkflows: Map<PsiClass, ConditionalWorkflow>) {
      val subAgentComponents = workflow.subAgents.mapNotNull { componentsByClass[it] }
      val first = subAgentComponents.firstOrNull()
      if (owner != null && first != null) {
        addEdge(addAgentNode(owner, conditionalWorkflows), addAgentNode(first, conditionalWorkflows), "sequence", workflow.annotation)
      }
      for ((index, component) in subAgentComponents.withIndex()) {
        val source = addAgentNode(component, conditionalWorkflows)
        val next = subAgentComponents.getOrNull(index + 1)
        val conditional = conditionalWorkflows[component.target]
        if (conditional != null) {
          addConditionalEdges(source, conditional, index + 1, componentsByClass, next, conditionalWorkflows)
        }
        if (next != null) {
          val label = sequenceEdgeLabel(index, component.target, next.target, conditional != null)
          addEdge(source, addAgentNode(next, conditionalWorkflows), label, workflow.annotation)
        }
      }
    }

    private fun addConditionalEdges(source: HelidonLangChain4jDiagramElement,
                                    workflow: ConditionalWorkflow,
                                    sequenceStep: Int,
                                    componentsByClass: Map<PsiClass, Component>,
                                    nextSequenceComponent: Component?,
                                    conditionalWorkflows: Map<PsiClass, ConditionalWorkflow>) {
      for ((index, agentClass) in workflow.orderedSubAgents.withIndex()) {
        val component = componentsByClass[agentClass.agent] ?: continue
        val target = addAgentNode(component, conditionalWorkflows)
        addEdge(source, target, conditionalEdgeLabel(sequenceStep, index, agentClass), workflow.annotation)

        if (nextSequenceComponent != null) {
          val outputKey = agentOutputKey(agentClass.agent) ?: "response"
          addEdge(target, addAgentNode(nextSequenceComponent, conditionalWorkflows), outputKey, agentClass.conditionMethod ?: agentClass.agent)
        }
      }
    }

    private fun addAgentNode(component: Component,
                             conditionalWorkflows: Map<PsiClass, ConditionalWorkflow> = emptyMap()): HelidonLangChain4jDiagramElement {
      return addNode(component.toDiagramElement(module, includeTests, items = agentMetadataItems(component.target, conditionalWorkflows)))
    }

    private fun sequenceWorkflow(agent: PsiClass): SequenceWorkflow? {
      for (method in agent.methods) {
        val annotation = method.annotation(SEQUENCE_AGENT) ?: continue
        val subAgents = classValues(annotation, SUB_AGENTS_ATTRIBUTE)
        if (subAgents.isNotEmpty()) {
          return SequenceWorkflow(agent, annotation, subAgents, inputKeys(method))
        }
      }
      return null
    }

    private fun conditionalWorkflow(agent: PsiClass): ConditionalWorkflow? {
      for (method in agent.methods) {
        val annotation = method.annotation(CONDITIONAL_AGENT) ?: continue
        val subAgents = classValues(annotation, SUB_AGENTS_ATTRIBUTE)
        if (subAgents.isNotEmpty()) {
          val activationOrder = activationConditionBranches(agent)
          val orderedTargets = (activationOrder.map { it.agent } + subAgents).distinct()
            .filter { it in subAgents }
          val branchesByTarget = activationOrder.associateBy { it.agent }
          val ordered = orderedTargets.map { target ->
            branchesByTarget[target] ?: ConditionalBranch(target, null)
          }
          return ConditionalWorkflow(agent, annotation, ordered)
        }
      }
      return null
    }

    private fun endpointClassesReferencing(agents: List<PsiClass>): List<Pair<PsiClass, PsiClass>> {
      val endpointAnnotation = JavaPsiFacade.getInstance(module.project)
        .findClass(HelidonConstants.REST_SERVER_ENDPOINT, module.getModuleWithDependenciesAndLibrariesScope(includeTests))
        ?: return emptyList()
      val agentSet = agents.toSet()
      return AnnotatedElementsSearch.searchPsiClasses(endpointAnnotation, module.getModuleWithDependenciesAndLibrariesScope(includeTests))
        .flatMap { endpoint ->
          val referencedClasses = endpoint.referencedClasses()
          agentSet.asSequence()
            .filter { agent -> agent in referencedClasses }
            .map { agent -> endpoint to agent }
            .asIterable()
        }
        .toList()
    }

    private fun PsiClass.referencedClasses(): Set<PsiClass> {
      val result = LinkedHashSet<PsiClass>()
      fields.forEach { field -> field.type.collectResolvedClasses(result) }
      methods.forEach { method ->
        method.returnType.collectResolvedClasses(result)
        method.parameterList.parameters.forEach { parameter -> parameter.type.collectResolvedClasses(result) }
      }
      constructors.forEach { constructor ->
        constructor.parameterList.parameters.forEach { parameter -> parameter.type.collectResolvedClasses(result) }
      }
      PsiTreeUtil.processElements(this) { element ->
        (element as? PsiJavaCodeReferenceElement)?.resolve()?.let { resolved ->
          if (resolved is PsiClass) {
            result.add(resolved)
          }
        }
        true
      }
      return result
    }

    private fun endpointElement(endpoint: PsiClass, inputs: List<String>): HelidonLangChain4jDiagramElement {
      return endpointElement(endpoint, module, includeTests, inputs)
    }

    private fun sequenceEdgeLabel(index: Int,
                                  sourceAgent: PsiClass,
                                  targetAgent: PsiClass,
                                  sourceHasConditionalBranches: Boolean): String {
      val step = if (sourceHasConditionalBranches) index + 2 else index + 1
      val outputKey = agentOutputKey(sourceAgent)
      if (outputKey != null) {
        return "$step) $outputKey"
      }
      return "$step) ${targetAgent.actionName()}"
    }

    private fun conditionalEdgeLabel(sequenceStep: Int, index: Int, branch: ConditionalBranch): String {
      val suffix = ('a'.code + index).toChar()
      return "$sequenceStep$suffix) if ${branch.conditionLabel()}"
    }

    private fun agentOutputKey(agentClass: PsiClass): String? {
      for (method in agentClass.methods) {
        method.annotation(AGENTIC_AGENT)?.stringAttribute(OUTPUT_KEY_ATTRIBUTE)?.takeIf { it.isNotBlank() }?.let { return it }
      }
      return null
    }

    private fun inputKeys(method: PsiMethod): List<String> {
      return method.parameterList.parameters.mapNotNull { parameter ->
        parameter.annotation(SERVICE_VALUE)?.stringAttribute(VALUE_ATTRIBUTE)?.takeIf { it.isNotBlank() }
      }
    }

    private fun activationConditionBranches(agentClass: PsiClass): List<ConditionalBranch> {
      return agentClass.methods.mapNotNull { method ->
        method.annotation(ACTIVATION_CONDITION)?.let { annotation ->
          classValues(annotation, VALUE_ATTRIBUTE).firstOrNull()?.let { ConditionalBranch(it, method) }
        }
      }
    }

    private fun agentMetadataItems(agentClass: PsiClass,
                                   conditionalWorkflows: Map<PsiClass, ConditionalWorkflow>): List<HelidonLangChain4jDiagramItem> {
      val items = ArrayList<HelidonLangChain4jDiagramItem>()
      val inputs = agentInputKeys(agentClass)
      if (inputs.isNotEmpty()) {
        items.add(HelidonLangChain4jDiagramItem(key = "input", value = inputs.joinToString(", ")))
      }
      val outputs = agentOutputKeys(agentClass, conditionalWorkflows)
      if (outputs.isNotEmpty()) {
        items.add(HelidonLangChain4jDiagramItem(key = "output", value = outputs.joinToString(", ")))
      }
      items.addAll(agentResourceItems(agentClass))
      return items
    }

    private fun agentInputKeys(agentClass: PsiClass): List<String> {
      val inputs = LinkedHashSet<String>()
      for (method in agentClass.methods) {
        inputs.addAll(inputKeys(method))
      }
      return inputs.toList()
    }

    private fun agentOutputKeys(agentClass: PsiClass,
                                conditionalWorkflows: Map<PsiClass, ConditionalWorkflow>): List<String> {
      val outputs = LinkedHashSet<String>()
      agentOutputKey(agentClass)?.let(outputs::add)
      for (method in agentClass.methods) {
        method.annotation(SEQUENCE_AGENT)?.stringAttribute(OUTPUT_KEY_ATTRIBUTE)?.takeIf { it.isNotBlank() }?.let(outputs::add)
      }
      conditionalWorkflows[agentClass]?.orderedSubAgents
        .orEmpty()
        .map { it.agent }
        .mapNotNull(::agentOutputKey)
        .forEach(outputs::add)
      return outputs.toList()
    }

    private fun agentResourceItems(agentClass: PsiClass): List<HelidonLangChain4jDiagramItem> {
      val items = ArrayList<HelidonLangChain4jDiagramItem>()
      for (annotation in agentClass.modifierList?.annotations ?: emptyArray()) {
        when (annotation.qualifiedName) {
          HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CHAT_MODEL,
          HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MODEL,
          HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_STREAMING_CHAT_MODEL,
          HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_STREAMING_CHAT_MODEL,
          HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_MODERATION_MODEL,
          HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_MODERATION_MODEL -> {
            annotationValues(annotation).takeIf { it.isNotEmpty() }?.let { values ->
              items.add(HelidonLangChain4jDiagramItem(key = "model", value = values.joinToString(", ")))
            }
          }
          HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CONTENT_RETRIEVER,
          HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CONTENT_RETRIEVER -> {
            annotationValues(annotation).takeIf { it.isNotEmpty() }?.let { values ->
              items.add(HelidonLangChain4jDiagramItem(key = "retriever", value = values.joinToString(", ")))
            }
          }
          HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_MCP_CLIENTS,
          HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_MCP_CLIENTS -> {
            annotationValues(annotation).takeIf { it.isNotEmpty() }?.let { values ->
              items.add(HelidonLangChain4jDiagramItem(key = "mcp", value = values.joinToString(", ")))
            }
          }
          HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_TOOLS,
          HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_TOOLS -> {
            classValues(annotation, VALUE_ATTRIBUTE)
              .mapNotNull { it.name ?: it.qualifiedName }
              .takeIf { it.isNotEmpty() }
              ?.let { values -> items.add(HelidonLangChain4jDiagramItem(key = "tools", value = values.joinToString(", "))) }
          }
        }
      }
      return items
    }

    private fun collectConfigNodes(files: List<YAMLFile>) {
      for (file in files) {
        for (document in file.documents) {
          val root = HelidonConfigYamlAccessor(document).findExistingKey(ROOT) ?: continue
          val rootNode = rootNode(root)
          addNode(rootNode)
          for ((section, kind) in CONFIG_NODE_KINDS) {
            val sectionKey = (root.value as? YAMLMapping)?.getKeyValueByKey(section) ?: continue
            val entries = (sectionKey.value as? YAMLMapping)?.keyValues ?: continue
            for (entry in entries) {
              val key = entry.keyText.takeIf { it.isNotBlank() } ?: continue
              val runtimeKey = if (section == MCP_CLIENTS) mcpClientRuntimeKey(entry) else key
              val node = configElement(section, runtimeKey, key, kind, entry)
              val graphNode = addNode(node)
              configNodes[section to runtimeKey] = graphNode
              addEdge(rootNode, graphNode, section, entry)
            }
          }
        }
      }
    }

    private fun collectConfigEdges(files: List<YAMLFile>) {
      for (file in files) {
        for (document in file.documents) {
          val root = HelidonConfigYamlAccessor(document).findExistingKey(ROOT) ?: continue
          val rootMapping = root.value as? YAMLMapping ?: continue
          collectServiceOrAgentEdges(rootMapping, SERVICES)
          collectServiceOrAgentEdges(rootMapping, AGENTS)
          collectModelEdges(rootMapping)
          collectContentRetrieverEdges(rootMapping)
        }
      }
    }

    private fun collectServiceOrAgentEdges(rootMapping: YAMLMapping, section: String) {
      val sectionKey = rootMapping.getKeyValueByKey(section) ?: return
      val entries = (sectionKey.value as? YAMLMapping)?.keyValues ?: return
      for (entry in entries) {
        val source = configNodes[section to entry.keyText] ?: continue
        val mapping = entry.value as? YAMLMapping ?: continue
        for (property in mapping.keyValues) {
          val propertyName = property.keyText
          val scalars = scalarValues(property)
          if (propertyName in VALUE_CONFIG_TARGETS) {
            val targetSection = VALUE_CONFIG_TARGETS.getValue(propertyName)
            scalars.forEach { scalar ->
              configNodes[targetSection to scalar.textValue]?.let { target ->
                addEdge(source, target, propertyName, scalar)
              }
            }
          }
          if (propertyName == MCP_CLIENTS) {
            scalars.forEach { scalar ->
              configNodes[MCP_CLIENTS to scalar.textValue]?.let { target ->
                addEdge(source, target, propertyName, scalar)
              }
            }
          }
          if (propertyName in VALUE_COMPONENT_TARGETS) {
            val componentKinds = VALUE_COMPONENT_TARGETS.getValue(propertyName)
            scalars.forEach { scalar ->
              addComponentValueEdges(source, propertyName, componentKinds, scalar)
            }
          }
          if (propertyName in CLASS_VALUED_KEYS) {
            scalars.forEach { scalar ->
              val target = classNode(scalar.textValue, scalar)
              addEdge(source, target, propertyName, scalar)
            }
          }
        }
      }
    }

    private fun collectModelEdges(rootMapping: YAMLMapping) {
      val sectionKey = rootMapping.getKeyValueByKey(MODELS) ?: return
      val entries = (sectionKey.value as? YAMLMapping)?.keyValues ?: return
      for (entry in entries) {
        val source = configNodes[MODELS to entry.keyText] ?: continue
        val mapping = entry.value as? YAMLMapping ?: continue
        val provider = scalarValue(mapping.getKeyValueByKey("provider")) ?: continue
        configNodes[PROVIDERS to provider.textValue]?.let { target ->
          addEdge(source, target, "provider", provider)
        }
      }
    }

    private fun collectContentRetrieverEdges(rootMapping: YAMLMapping) {
      val sectionKey = rootMapping.getKeyValueByKey(CONTENT_RETRIEVERS) ?: return
      val entries = (sectionKey.value as? YAMLMapping)?.keyValues ?: return
      for (entry in entries) {
        val source = configNodes[CONTENT_RETRIEVERS to entry.keyText] ?: continue
        val mapping = entry.value as? YAMLMapping ?: continue
        for (propertyName in listOf("embedding-model", "embedding-store")) {
          val value = scalarValue(mapping.getKeyValueByKey(propertyName)) ?: continue
          val section = VALUE_CONFIG_TARGETS[propertyName] ?: continue
          configNodes[section to value.textValue]?.let { target ->
            addEdge(source, target, propertyName, value)
          }
        }
      }
    }

    private fun collectAnnotationEdges(components: List<Component>) {
      for (component in components) {
        if (component.kind != ComponentKind.SERVICE && component.kind != ComponentKind.AGENT) continue
        val source = nodeForComponent(component) ?: continue
        for (annotation in component.target.modifierList?.annotations ?: emptyArray()) {
          val annotationName = annotation.qualifiedName ?: continue
          if (annotationName in ANNOTATION_CONFIG_SECTIONS) {
            val section = ANNOTATION_CONFIG_SECTIONS.getValue(annotationName)
            configAnnotationValues(component, annotationName, annotation).forEach { value ->
              configNodes[section to value]?.let { target ->
                addEdge(source, target, annotation.shortName(), annotation)
              }
            }
          }
          ANNOTATION_CONFIG_VALUE_KEYS[annotationName]?.let { keyNames ->
            explicitAnnotationValues(annotation).forEach { value ->
              keyNames.forEach { keyName ->
                val kinds = VALUE_COMPONENT_TARGETS[keyName] ?: emptySet()
                addComponentValueEdges(source, annotation.shortName(), kinds, annotation, value)
              }
            }
          }
        }
      }
    }

    private fun addComponentValueEdges(source: HelidonLangChain4jDiagramElement,
                                       label: String,
                                       kinds: Set<ComponentKind>,
                                       navigationElement: PsiElement,
                                       value: String = (navigationElement as? YAMLScalar)?.textValue ?: "") {
      if (value.isBlank()) return
      for (kind in kinds) {
        val components = componentsByKindAndKey[kind to value].orEmpty()
        components.forEach { component ->
          nodeForComponent(component)?.let { target ->
            addEdge(source, target, label, navigationElement)
          }
        }
      }
    }

    private fun addComponentNode(component: Component) {
      componentsByKindAndKey.getOrPut(component.kind to component.key) { ArrayList() }.add(component)
      addNode(component.toDiagramElement(module, includeTests))
    }

    private fun linkConfigNodesToComponents() {
      for ((sectionAndKey, source) in configNodes) {
        val (section, key) = sectionAndKey
        val kinds = CONFIG_TO_COMPONENT_KINDS[section] ?: continue
        for (kind in kinds) {
          componentsByKindAndKey[kind to key].orEmpty().forEach { component ->
            nodeForComponent(component)?.let { target ->
              addEdge(source, target, "declares", component.target)
            }
          }
        }
      }
    }

    private fun classNode(className: String, context: PsiElement): HelidonLangChain4jDiagramElement {
      val scope = module.getModuleWithDependenciesAndLibrariesScope(includeTests)
      val psiClass = if (className.contains('.')) {
        JavaPsiFacade.getInstance(module.project).findClass(className, scope)
      }
      else {
        PsiShortNamesCache.getInstance(module.project).getClassesByName(className, scope).firstOrNull()
      }
      val qualifiedName = psiClass?.qualifiedName ?: className
      val name = psiClass?.name ?: className.substringAfterLast('.')
      val node = HelidonLangChain4jDiagramElement(
        id = diagramId("java-class", includeTestsPart(includeTests), module.name, qualifiedName),
        name = name,
        kind = HelidonLangChain4jDiagramNodeKind.JAVA_CLASS,
        psiElement = psiClass ?: context,
        module = module,
        includeTests = includeTests,
      )
      addNode(node)
      return node
    }

    private fun nodeForComponent(component: Component): HelidonLangChain4jDiagramElement? {
      val id = component.toDiagramElement(module, includeTests).id
      return nodes[id]
    }

    private fun addNode(node: HelidonLangChain4jDiagramElement): HelidonLangChain4jDiagramElement {
      val existing = nodes[node.id]
      if (existing != null) {
        if (shouldReplaceNode(existing, node)) {
          nodes[node.id] = node
          return node
        }
        return existing
      }
      nodes[node.id] = node
      return node
    }

    private fun shouldReplaceNode(existing: HelidonLangChain4jDiagramElement,
                                  candidate: HelidonLangChain4jDiagramElement): Boolean {
      if (candidate.psiElement == null) return false
      if (existing.psiElement == null) return true

      val seedFile = seed.psiElement?.containingFile?.originalFile ?: return false
      val existingFile = existing.psiElement.containingFile?.originalFile
      val candidateFile = candidate.psiElement.containingFile?.originalFile
      return existingFile != seedFile && candidateFile == seedFile
    }

    private fun addEdge(source: HelidonLangChain4jDiagramElement,
                        target: HelidonLangChain4jDiagramElement,
                        label: String,
                        navigationElement: PsiElement?,
                        kind: HelidonLangChain4jWorkflowEdgeKind = HelidonLangChain4jWorkflowEdgeKind.FLOW) {
      val key = GraphEdgeKey(source.id, target.id, label, kind)
      val existing = edges[key]
      if (existing == null || shouldReplaceNavigationElement(existing.navigationElement, navigationElement)) {
        edges[key] = GraphEdge(source.id, target.id, label, navigationElement, kind)
      }
    }

    private fun shouldReplaceNavigationElement(existing: PsiElement?, candidate: PsiElement?): Boolean {
      if (candidate == null) return false
      if (existing == null) return true

      val seedFile = seed.psiElement?.containingFile?.originalFile ?: return false
      val existingFile = existing.containingFile?.originalFile
      val candidateFile = candidate.containingFile?.originalFile
      return existingFile != seedFile && candidateFile == seedFile
    }

    private fun configElement(section: String,
                              runtimeKey: String,
                              sectionKey: String,
                              kind: HelidonLangChain4jDiagramNodeKind,
                              psiElement: YAMLKeyValue): HelidonLangChain4jDiagramElement {
      return configElement(section, runtimeKey, sectionKey, kind, psiElement, module, includeTests)
    }

    private fun rootNode(root: YAMLKeyValue): HelidonLangChain4jDiagramElement {
      return if (seed.kind == HelidonLangChain4jDiagramNodeKind.ROOT) {
        seed.copyWithPsi(root)
      }
      else {
        rootElement(module, includeTests, root)
      }
    }

    private fun HelidonLangChain4jDiagramElement.copyWithPsi(psiElement: PsiElement): HelidonLangChain4jDiagramElement {
      return HelidonLangChain4jDiagramElement(id, name, kind, psiElement, module, includeTests, group, items)
    }
  }

  private fun applicationYamlFiles(module: Module, includeTests: Boolean): List<YAMLFile> {
    if (!HelidonCoreUtils.hasHelidonLibrary(module)) return emptyList()
    val psiManager = PsiManager.getInstance(module.project)
    return HelidonConfigFileContributor.findConfigFiles(module, includeTests)
      .map { it.first }
      .mapNotNull { psiManager.findFile(it) as? YAMLFile }
      .distinct()
  }

  private fun collectComponents(module: Module, includeTests: Boolean): List<Component> {
    val project = module.project
    val scope = module.getModuleWithDependenciesAndLibrariesScope(includeTests)
    val result = LinkedHashSet<Component>()
    val facade = JavaPsiFacade.getInstance(project)

    for ((kind, annotationNames) in ANNOTATION_KINDS) {
      for (annotationName in annotationNames) {
        val annotationClass = facade.findClass(annotationName, scope) ?: continue
        AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
          val annotation = findAnnotation(psiClass, annotationName) ?: return@forEach
          componentKeys(kind, psiClass, annotation).forEach { key ->
            result.add(Component(kind, key, psiClass))
          }
        }
      }
    }

    return result.toList()
  }

  private fun componentFromClass(psiClass: PsiClass): Component? {
    for ((kind, annotationNames) in ANNOTATION_KINDS) {
      for (annotationName in annotationNames) {
        val annotation = findAnnotation(psiClass, annotationName) ?: continue
        val key = componentKeys(kind, psiClass, annotation).firstOrNull() ?: continue
        return Component(kind, key, psiClass)
      }
    }
    return null
  }

  private fun findAnnotation(owner: PsiModifierListOwner, annotationName: String): PsiAnnotation? {
    return owner.modifierList?.annotations?.firstOrNull { it.qualifiedName == annotationName }
  }

  private fun annotationValues(annotation: PsiAnnotation): List<String> {
    val value = annotation.findAttributeValue(VALUE_ATTRIBUTE) ?: return emptyList()
    if (value is PsiArrayInitializerMemberValue) {
      return value.initializers.mapNotNull { constantString(it) }
    }
    return listOfNotNull(constantString(value))
  }

  private fun constantString(element: PsiElement): String? {
    if (element is PsiLiteralExpression) {
      return element.value as? String
    }
    return JavaPsiFacade.getInstance(element.project).constantEvaluationHelper.computeConstantExpression(element) as? String
  }

  private fun componentKeys(kind: ComponentKind, psiClass: PsiClass, annotation: PsiAnnotation): List<String> {
    val values = explicitAnnotationValues(annotation)
    return if (values.isEmpty()) fallbackKeys(kind, psiClass) else values
  }

  private fun configAnnotationValues(component: Component, annotationName: String, annotation: PsiAnnotation): List<String> {
    val annotationKind = annotationComponentKind(annotationName)
    return if (annotationKind == component.kind) {
      componentKeys(component.kind, component.target, annotation)
    }
    else {
      explicitAnnotationValues(annotation)
    }
  }

  private fun annotationComponentKind(annotationName: String): ComponentKind? {
    return ANNOTATION_KINDS.entries.firstOrNull { annotationName in it.value }?.key
  }

  private fun explicitAnnotationValues(annotation: PsiAnnotation): List<String> {
    return annotationValues(annotation).filter { it.isNotBlank() }
  }

  private fun fallbackKeys(kind: ComponentKind, psiClass: PsiClass): List<String> {
    val qualifiedName = psiClass.qualifiedName
    return when (kind) {
      ComponentKind.SERVICE,
      ComponentKind.CHAT_MODEL,
      ComponentKind.STREAMING_CHAT_MODEL,
      ComponentKind.CHAT_MEMORY_PROVIDER,
      ComponentKind.MODERATION_MODEL,
      ComponentKind.CONTENT_RETRIEVER,
      ComponentKind.RETRIEVAL_AUGMENTOR,
      ComponentKind.TOOL_PROVIDER -> listOfNotNull(qualifiedName)
      else -> emptyList()
    }
  }

  private fun scalarValue(keyValue: YAMLKeyValue?): YAMLScalar? {
    return keyValue?.value as? YAMLScalar
  }

  private fun scalarValues(keyValue: YAMLKeyValue): List<YAMLScalar> {
    return when (val value = keyValue.value) {
      is YAMLScalar -> listOf(value)
      is YAMLSequence -> value.items.mapNotNull { it.value as? YAMLScalar }
      else -> emptyList()
    }
  }

  private fun PsiAnnotation.shortName(): String {
    return qualifiedName?.substringAfterLast('.') ?: nameReferenceElement?.referenceName ?: "annotation"
  }

  private fun PsiModifierListOwner.annotation(annotationName: String): PsiAnnotation? {
    return modifierList?.annotations?.firstOrNull { it.qualifiedName == annotationName }
  }

  private fun PsiAnnotation.stringAttribute(attributeName: String): String? {
    return findAttributeValue(attributeName)?.let(::constantString)
  }

  private fun classValues(annotation: PsiAnnotation, attributeName: String): List<PsiClass> {
    val value = annotation.findAttributeValue(attributeName) ?: return emptyList()
    if (value is PsiArrayInitializerMemberValue) {
      return value.initializers.mapNotNull(::classValue)
    }
    return listOfNotNull(classValue(value))
  }

  private fun classValue(element: PsiElement): PsiClass? {
    return ((element as? PsiClassObjectAccessExpression)?.operand?.type as? PsiClassType)?.resolve()
  }

  private fun PsiType?.collectResolvedClasses(result: MutableSet<PsiClass>) {
    when (this) {
      is PsiClassType -> {
        resolve()?.let(result::add)
        parameters.forEach { parameter -> parameter.collectResolvedClasses(result) }
      }
      is PsiWildcardType -> {
        extendsBound.collectResolvedClasses(result)
        superBound.collectResolvedClasses(result)
      }
    }
  }

  private fun PsiClass.actionName(): String {
    val simpleName = name ?: qualifiedName?.substringAfterLast('.') ?: "agent"
    return simpleName
      .removeSuffix("Agent")
      .replaceFirstChar { it.lowercase() }
  }

  private fun ConditionalBranch.conditionLabel(): String {
    return conditionMethod?.name?.let(::conditionMethodLabel)?.takeIf { it.isNotBlank() }
      ?: agent.actionName()
  }

  private fun conditionMethodLabel(methodName: String): String {
    val label = methodName
      .removePrefix("activate")
      .removePrefix("is")
      .removePrefix("should")
      .removePrefix("has")
      .replace(Regex("([a-z])([A-Z])"), "$1 $2")
      .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
      .trim()
    return label.lowercase()
  }

  private fun findClass(project: Project, qualifiedName: String): PsiClass? {
    return JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project))
  }

  private fun findClass(module: Module, includeTests: Boolean, qualifiedName: String): PsiClass? {
    return JavaPsiFacade.getInstance(module.project)
      .findClass(qualifiedName, module.getModuleWithDependenciesAndLibrariesScope(includeTests))
  }

  private fun findConfigEntry(module: Module,
                              includeTests: Boolean,
                              section: String,
                              runtimeKey: String): YAMLKeyValue? {
    for (file in applicationYamlFiles(module, includeTests)) {
      for (document in file.documents) {
        val root = HelidonConfigYamlAccessor(document).findExistingKey(ROOT) ?: continue
        val sectionKey = (root.value as? YAMLMapping)?.getKeyValueByKey(section) ?: continue
        val entries = (sectionKey.value as? YAMLMapping)?.keyValues ?: continue
        for (entry in entries) {
          val key = entry.keyText.takeIf { it.isNotBlank() } ?: continue
          val entryRuntimeKey = if (section == MCP_CLIENTS) mcpClientRuntimeKey(entry) else key
          if (entryRuntimeKey == runtimeKey) {
            return entry
          }
        }
      }
    }
    return null
  }

  private fun javaClassElement(psiClass: PsiClass,
                               module: Module?,
                               includeTests: Boolean): HelidonLangChain4jDiagramElement {
    val qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: "JavaClass"
    return HelidonLangChain4jDiagramElement(
      id = diagramId("java-class", includeTestsPart(includeTests), module?.name ?: "", qualifiedName),
      name = psiClass.name ?: qualifiedName,
      kind = HelidonLangChain4jDiagramNodeKind.JAVA_CLASS,
      psiElement = psiClass,
      module = module,
      includeTests = includeTests,
    )
  }

  private fun endpointElement(endpoint: PsiClass,
                              module: Module?,
                              includeTests: Boolean,
                              inputs: List<String>): HelidonLangChain4jDiagramElement {
    val qualifiedName = endpoint.qualifiedName ?: endpoint.name ?: "Endpoint"
    return HelidonLangChain4jDiagramElement(
      id = diagramId("endpoint", includeTestsPart(includeTests), qualifiedName),
      name = endpoint.name ?: qualifiedName,
      kind = HelidonLangChain4jDiagramNodeKind.ENDPOINT,
      psiElement = endpoint,
      module = module,
      includeTests = includeTests,
      items = inputs.takeIf { it.isNotEmpty() }
        ?.let { listOf(HelidonLangChain4jDiagramItem(key = "input", value = it.joinToString(", "))) }
        ?: emptyList(),
    )
  }

  private fun configElement(section: String,
                            runtimeKey: String,
                            sectionKey: String,
                            kind: HelidonLangChain4jDiagramNodeKind,
                            psiElement: YAMLKeyValue,
                            module: Module,
                            includeTests: Boolean): HelidonLangChain4jDiagramElement {
    val displayName = if (runtimeKey == sectionKey) runtimeKey else "$runtimeKey ($sectionKey)"
    return HelidonLangChain4jDiagramElement(
      id = diagramId("config", includeTestsPart(includeTests), module.name, section, runtimeKey),
      name = displayName,
      kind = kind,
      psiElement = psiElement,
      module = module,
      includeTests = includeTests,
    )
  }

  private fun mcpClientRuntimeKey(entry: YAMLKeyValue): String {
    val explicitKey = ((entry.value as? YAMLMapping)?.getKeyValueByKey("key")?.value as? YAMLScalar)?.textValue
    return explicitKey?.takeIf { it.isNotBlank() } ?: entry.keyText
  }

  private fun includeTestSources(context: PsiElement, module: Module): Boolean {
    val virtualFile = context.containingFile?.originalFile?.virtualFile ?: return false
    return ModuleRootManager.getInstance(module).fileIndex.isInTestSourceContent(virtualFile)
  }

  private fun includeTestsFor(context: PsiElement, module: Module?): Boolean {
    return module != null && includeTestSources(context, module)
  }

  private fun Component.toDiagramElement(module: Module?,
                                         includeTests: Boolean,
                                         group: String? = null,
                                         items: List<HelidonLangChain4jDiagramItem> = emptyList()): HelidonLangChain4jDiagramElement {
    val qualifiedName = target.qualifiedName ?: target.name ?: key
    return HelidonLangChain4jDiagramElement(
      id = diagramId("java", includeTestsPart(includeTests), kind.name, qualifiedName),
      name = target.name ?: qualifiedName,
      kind = kind.nodeKind,
      psiElement = target,
      module = module,
      includeTests = includeTests,
      group = group,
      items = items,
    )
  }

  private fun diagramId(prefix: String, vararg parts: String): String {
    return "$prefix:" + parts.joinToString(":") { encodeIdPart(it) }
  }

  private fun parseDiagramId(id: String, prefix: String, expectedPartCount: Int): List<String>? {
    if (!id.startsWith("$prefix:")) return null
    val tokens = id.removePrefix("$prefix:").split(':')
    if (tokens.size != expectedPartCount) return null
    return tokens.map { decodeIdPart(it) ?: return null }
  }

  private fun encodeIdPart(value: String): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
  }

  private fun decodeIdPart(value: String): String? {
    return try {
      String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }
    catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun includeTestsPart(includeTests: Boolean): String = if (includeTests) "tests" else "main"

  private fun includeTestsPart(part: String): Boolean? {
    return when (part) {
      "main" -> false
      "tests" -> true
      else -> null
    }
  }

  private data class SequenceWorkflow(
    val agent: PsiClass,
    val annotation: PsiAnnotation,
    val subAgents: List<PsiClass>,
    val inputKeys: List<String>,
  )

  private data class ConditionalWorkflow(
    val agent: PsiClass,
    val annotation: PsiAnnotation,
    val orderedSubAgents: List<ConditionalBranch>,
  )

  private data class ConditionalBranch(
    val agent: PsiClass,
    val conditionMethod: PsiMethod?,
  )

  private data class Component(
    val kind: ComponentKind,
    val key: String,
    val target: PsiClass,
  )

  private data class GraphEdge(
    val sourceId: String,
    val targetId: String,
    val label: String,
    val navigationElement: PsiElement?,
    val kind: HelidonLangChain4jWorkflowEdgeKind,
  )

  private data class GraphEdgeKey(
    val sourceId: String,
    val targetId: String,
    val label: String,
    val kind: HelidonLangChain4jWorkflowEdgeKind,
  )

  private enum class ComponentKind(val nodeKind: HelidonLangChain4jDiagramNodeKind) {
    SERVICE(HelidonLangChain4jDiagramNodeKind.JAVA_SERVICE),
    AGENT(HelidonLangChain4jDiagramNodeKind.JAVA_AGENT),
    CHAT_MODEL(HelidonLangChain4jDiagramNodeKind.JAVA_CHAT_MODEL),
    STREAMING_CHAT_MODEL(HelidonLangChain4jDiagramNodeKind.JAVA_STREAMING_CHAT_MODEL),
    CHAT_MEMORY_PROVIDER(HelidonLangChain4jDiagramNodeKind.JAVA_CHAT_MEMORY_PROVIDER),
    MODERATION_MODEL(HelidonLangChain4jDiagramNodeKind.JAVA_MODERATION_MODEL),
    CONTENT_RETRIEVER(HelidonLangChain4jDiagramNodeKind.JAVA_CONTENT_RETRIEVER),
    RETRIEVAL_AUGMENTOR(HelidonLangChain4jDiagramNodeKind.JAVA_RETRIEVAL_AUGMENTOR),
    TOOL_PROVIDER(HelidonLangChain4jDiagramNodeKind.JAVA_TOOL_PROVIDER),
    MCP_CLIENTS(HelidonLangChain4jDiagramNodeKind.JAVA_MCP_CLIENTS),
  }
}
