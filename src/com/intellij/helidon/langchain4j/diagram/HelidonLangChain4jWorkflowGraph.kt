// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j.diagram

import com.intellij.helidon.constants.HelidonConstants
import com.intellij.helidon.config.yaml.HelidonConfigYamlAccessor
import com.intellij.helidon.config.yaml.getQualifiedConfigKeyName
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

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

internal class HelidonLangChain4jDiagramElement(
  val id: String,
  val name: String,
  val kind: HelidonLangChain4jDiagramNodeKind,
  val psiElement: PsiElement?,
  val module: Module?,
  val includeTests: Boolean,
) {
  override fun equals(other: Any?): Boolean = other is HelidonLangChain4jDiagramElement && id == other.id

  override fun hashCode(): Int = id.hashCode()

  override fun toString(): String = "$name (${kind.presentableName})"
}

internal enum class HelidonLangChain4jDiagramNodeKind(val presentableName: String) {
  ROOT("LangChain4j config"),
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
)

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
    MCP_CLIENTS to setOf(ComponentKind.MCP_CLIENTS),
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
    MCP_CLIENTS to setOf(ComponentKind.MCP_CLIENTS),
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
    ComponentKind.MCP_CLIENTS to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_MCP_CLIENTS,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_MCP_CLIENTS,
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

  private val MCP_CLIENT_ANNOTATIONS = setOf(
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_MCP_CLIENTS,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_MCP_CLIENTS,
  )

  fun seedFromPsiElement(element: PsiElement): HelidonLangChain4jDiagramElement? {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
    val includeTests = includeTestSources(element, module)

    val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
    val component = psiClass?.let { componentFromClass(it, module, includeTests) }
    if (component != null) {
      return component.toDiagramElement(module, includeTests)
    }

    val yamlKeyValue = when (element) {
      is YAMLKeyValue -> element
      else -> PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false)
    }
    if (yamlKeyValue != null && getQualifiedConfigKeyName(yamlKeyValue).let { it == ROOT || it.startsWith("$ROOT.") }) {
      return rootElement(module, includeTests, yamlKeyValue)
    }

    val yamlFile = element.containingFile as? YAMLFile
    if (yamlFile != null && yamlFile.documents.any { HelidonConfigYamlAccessor(it).findExistingKey(ROOT) != null }) {
      return rootElement(module, includeTests, yamlFile)
    }

    return null
  }

  fun findElement(project: Project, id: String): HelidonLangChain4jDiagramElement? {
    for (module in ModuleManager.getInstance(project).modules) {
      val root = rootElement(module, includeTests = true, psiElement = null)
      val graph = build(root)
      graph.nodes.firstOrNull { it.id == id }?.let { return it }
    }
    return null
  }

  fun build(seed: HelidonLangChain4jDiagramElement): HelidonLangChain4jWorkflowGraph {
    val module = seed.module ?: return HelidonLangChain4jWorkflowGraph(listOf(seed), emptyList())
    return Builder(module, seed.includeTests, seed).build()
  }

  private fun rootElement(module: Module, includeTests: Boolean, psiElement: PsiElement?): HelidonLangChain4jDiagramElement {
    return HelidonLangChain4jDiagramElement(
      id = "root:${module.name}",
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
    private val edges = LinkedHashSet<GraphEdge>()
    private val configNodes = LinkedHashMap<Pair<String, String>, HelidonLangChain4jDiagramElement>()
    private val componentsByKindAndKey = LinkedHashMap<Pair<ComponentKind, String>, MutableList<Component>>()

    fun build(): HelidonLangChain4jWorkflowGraph {
      addNode(seed)
      val configFiles = applicationYamlFiles(module, includeTests)
      collectConfigNodes(configFiles)
      val components = collectComponents(module, includeTests)
      components.forEach(::addComponentNode)
      linkConfigNodesToComponents()
      collectConfigEdges(configFiles)
      collectAnnotationEdges(components)

      return HelidonLangChain4jWorkflowGraph(
        nodes = nodes.values.toList(),
        edges = edges.mapNotNull { edge ->
          val source = nodes[edge.sourceId] ?: return@mapNotNull null
          val target = nodes[edge.targetId] ?: return@mapNotNull null
          HelidonLangChain4jWorkflowEdge(source, target, edge.label, edge.navigationElement)
        },
      )
    }

    private fun collectConfigNodes(files: List<YAMLFile>) {
      for (file in files) {
        for (document in file.documents) {
          val root = HelidonConfigYamlAccessor(document).findExistingKey(ROOT) ?: continue
          val rootNode = seed.copyWithPsi(root)
          addNode(rootNode)
          for ((section, kind) in CONFIG_NODE_KINDS) {
            val sectionKey = (root.value as? YAMLMapping)?.getKeyValueByKey(section) ?: continue
            val entries = (sectionKey.value as? YAMLMapping)?.keyValues ?: continue
            for (entry in entries) {
              val key = entry.keyText.takeIf { it.isNotBlank() } ?: continue
              val runtimeKey = if (section == MCP_CLIENTS) mcpClientRuntimeKey(entry) else key
              val node = configElement(section, runtimeKey, key, kind, entry)
              addNode(node)
              configNodes[section to runtimeKey] = node
              addEdge(rootNode, node, section, entry)
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
          val values = annotationValues(annotation).filter { it.isNotBlank() }
          if (annotationName in ANNOTATION_CONFIG_SECTIONS) {
            val section = ANNOTATION_CONFIG_SECTIONS.getValue(annotationName)
            values.forEach { value ->
              configNodes[section to value]?.let { target ->
                addEdge(source, target, annotation.shortName(), annotation)
              }
            }
          }
          if (annotationName in MCP_CLIENT_ANNOTATIONS) {
            values.forEach { value ->
              configNodes[MCP_CLIENTS to value]?.let { target ->
                addEdge(source, target, annotation.shortName(), annotation)
              }
            }
          }
          ANNOTATION_CONFIG_VALUE_KEYS[annotationName]?.let { keyNames ->
            values.forEach { value ->
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
      val name = psiClass?.qualifiedName ?: className
      val node = HelidonLangChain4jDiagramElement(
        id = "java-class:$name",
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
        if (existing.psiElement == null && node.psiElement != null) {
          nodes[node.id] = node
          return node
        }
        return existing
      }
      nodes[node.id] = node
      return node
    }

    private fun addEdge(source: HelidonLangChain4jDiagramElement,
                        target: HelidonLangChain4jDiagramElement,
                        label: String,
                        navigationElement: PsiElement?) {
      edges.add(GraphEdge(source.id, target.id, label, navigationElement))
    }

    private fun configElement(section: String,
                              runtimeKey: String,
                              sectionKey: String,
                              kind: HelidonLangChain4jDiagramNodeKind,
                              psiElement: YAMLKeyValue): HelidonLangChain4jDiagramElement {
      val displayName = if (runtimeKey == sectionKey) runtimeKey else "$runtimeKey ($sectionKey)"
      return HelidonLangChain4jDiagramElement(
        id = "config:$section:$runtimeKey",
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

    private fun HelidonLangChain4jDiagramElement.copyWithPsi(psiElement: PsiElement): HelidonLangChain4jDiagramElement {
      return HelidonLangChain4jDiagramElement(id, name, kind, psiElement, module, includeTests)
    }
  }

  private fun applicationYamlFiles(module: Module, includeTests: Boolean): List<YAMLFile> {
    val scope = module.getModuleWithDependenciesAndLibrariesScope(includeTests)
    val psiManager = PsiManager.getInstance(module.project)
    return listOf("application.yaml", "application.yml")
      .flatMap { fileName -> FilenameIndex.getVirtualFilesByName(fileName, scope) }
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
          val values = annotationValues(annotation).filter { it.isNotBlank() }
          val keys = if (values.isEmpty()) fallbackKeys(kind, psiClass) else values
          keys.filter { it.isNotBlank() }.forEach { key ->
            result.add(Component(kind, key, psiClass))
          }
        }
      }
    }

    return result.toList()
  }

  private fun componentFromClass(psiClass: PsiClass,
                                 module: Module,
                                 includeTests: Boolean): Component? {
    return collectComponents(module, includeTests).firstOrNull { it.target == psiClass }
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

  private fun includeTestSources(context: PsiElement, module: Module): Boolean {
    val virtualFile = context.containingFile?.originalFile?.virtualFile ?: return false
    return ModuleRootManager.getInstance(module).fileIndex.isInTestSourceContent(virtualFile)
  }

  private fun Component.toDiagramElement(module: Module,
                                         includeTests: Boolean): HelidonLangChain4jDiagramElement {
    val qualifiedName = target.qualifiedName ?: target.name ?: key
    return HelidonLangChain4jDiagramElement(
      id = "java:${kind.name}:$qualifiedName",
      name = qualifiedName,
      kind = kind.nodeKind,
      psiElement = target,
      module = module,
      includeTests = includeTests,
    )
  }

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
