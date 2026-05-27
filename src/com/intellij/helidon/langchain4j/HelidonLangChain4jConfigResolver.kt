// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j

import com.intellij.helidon.constants.HelidonConstants
import com.intellij.helidon.config.HelidonConfigFileContributor
import com.intellij.helidon.config.properties.HelidonPropertiesUtils
import com.intellij.helidon.config.yaml.HelidonConfigYamlAccessor
import com.intellij.java.library.JavaLibraryModificationTracker
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.impl.PropertyImpl
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

internal object HelidonLangChain4jConfigResolver {
  private const val ROOT = "langchain4j"
  private const val SERVICES = "services"
  private const val AGENTS = "agents"
  private const val MODELS = "models"
  private const val PROVIDERS = "providers"
  private const val EMBEDDING_STORES = "embedding-stores"
  private const val CONTENT_RETRIEVERS = "content-retrievers"
  private const val MCP_CLIENTS = "mcp-clients"

  private const val VALUE_ATTRIBUTE = "value"

  private val MAIN_COMPONENTS_KEY: Key<CachedValue<List<LangChain4jComponent>>> =
    Key.create("HELIDON_LANGCHAIN4J_MAIN_COMPONENTS")

  private val TEST_COMPONENTS_KEY: Key<CachedValue<List<LangChain4jComponent>>> =
    Key.create("HELIDON_LANGCHAIN4J_TEST_COMPONENTS")

  private val MAIN_PROJECT_COMPONENTS_KEY: Key<CachedValue<List<LangChain4jComponent>>> =
    Key.create("HELIDON_LANGCHAIN4J_MAIN_PROJECT_COMPONENTS")

  private val TEST_PROJECT_COMPONENTS_KEY: Key<CachedValue<List<LangChain4jComponent>>> =
    Key.create("HELIDON_LANGCHAIN4J_TEST_PROJECT_COMPONENTS")

  private val MAIN_CONFIG_FILES_KEY: Key<CachedValue<List<ConfigFile>>> =
    Key.create("HELIDON_LANGCHAIN4J_MAIN_CONFIG_FILES")

  private val TEST_CONFIG_FILES_KEY: Key<CachedValue<List<ConfigFile>>> =
    Key.create("HELIDON_LANGCHAIN4J_TEST_CONFIG_FILES")

  private val CONFIG_SECTION_KINDS: Map<String, Set<LangChain4jComponentKind>> = mapOf(
    SERVICES to setOf(LangChain4jComponentKind.SERVICE),
    AGENTS to setOf(LangChain4jComponentKind.AGENT),
    MODELS to setOf(LangChain4jComponentKind.CHAT_MODEL,
                    LangChain4jComponentKind.STREAMING_CHAT_MODEL,
                    LangChain4jComponentKind.MODERATION_MODEL),
    CONTENT_RETRIEVERS to setOf(LangChain4jComponentKind.CONTENT_RETRIEVER),
    MCP_CLIENTS to setOf(LangChain4jComponentKind.MCP_CLIENTS),
  )

  private val CONFIG_SECTIONS: Set<String> = setOf(
    SERVICES,
    AGENTS,
    MODELS,
    PROVIDERS,
    EMBEDDING_STORES,
    CONTENT_RETRIEVERS,
    MCP_CLIENTS,
  )

  private val VALUE_CONFIG_TARGETS: Map<String, String> = mapOf(
    "provider" to PROVIDERS,
    "chat-model" to MODELS,
    "streaming-chat-model" to MODELS,
    "moderation-model" to MODELS,
    "embedding-model" to MODELS,
    "embedding-store" to EMBEDDING_STORES,
    "content-retriever" to CONTENT_RETRIEVERS,
  )

  private val VALUE_COMPONENT_KINDS: Map<String, Set<LangChain4jComponentKind>> = mapOf(
    "chat-model" to setOf(LangChain4jComponentKind.CHAT_MODEL),
    "streaming-chat-model" to setOf(LangChain4jComponentKind.STREAMING_CHAT_MODEL),
    "moderation-model" to setOf(LangChain4jComponentKind.MODERATION_MODEL),
    "chat-memory-provider" to setOf(LangChain4jComponentKind.CHAT_MEMORY_PROVIDER),
    "content-retriever" to setOf(LangChain4jComponentKind.CONTENT_RETRIEVER),
    "retrieval-augmentor" to setOf(LangChain4jComponentKind.RETRIEVAL_AUGMENTOR),
    "tool-provider" to setOf(LangChain4jComponentKind.TOOL_PROVIDER),
    MCP_CLIENTS to setOf(LangChain4jComponentKind.MCP_CLIENTS),
    "key" to setOf(LangChain4jComponentKind.MCP_CLIENTS),
  )

  private val CLASS_VALUED_KEYS: Set<String> = setOf("tools", "input-guardrails", "output-guardrails")

  private val ROBOT_KEY_SECTIONS: Set<String> = setOf(SERVICES, AGENTS, MODELS, CONTENT_RETRIEVERS)

  private val ROBOT_VALUE_KEYS: Set<String> = setOf("chat-model", "streaming-chat-model", "moderation-model", "content-retriever")

  private val AI_VALUE_KEYS: Set<String> = setOf("embedding-model")

  private val ANNOTATION_CONFIG_SECTIONS: Map<String, String> = mapOf(
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

  private val MCP_CLIENT_ANNOTATIONS: Set<String> = setOf(
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_MCP_CLIENTS,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_MCP_CLIENTS,
  )

  private val AI_ANNOTATION_REFERENCES: Set<String> = setOf(
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CHAT_MODEL,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MODEL,
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_STREAMING_CHAT_MODEL,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_STREAMING_CHAT_MODEL,
  )

  private val ANNOTATION_CONFIG_VALUE_KEYS: Map<String, Set<String>> = mapOf(
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CHAT_MEMORY_PROVIDER to setOf("chat-memory-provider"),
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MEMORY_PROVIDER to setOf("chat-memory-provider"),
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_RETRIEVAL_AUGMENTOR to setOf("retrieval-augmentor"),
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_RETRIEVAL_AUGMENTOR to setOf("retrieval-augmentor"),
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_TOOL_PROVIDER to setOf("tool-provider"),
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_TOOL_PROVIDER to setOf("tool-provider"),
  )

  private val ANNOTATION_KINDS: Map<LangChain4jComponentKind, Set<String>> = mapOf(
    LangChain4jComponentKind.SERVICE to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_SERVICE,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_SERVICE,
    ),
    LangChain4jComponentKind.AGENT to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_AGENT,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_AGENT,
    ),
    LangChain4jComponentKind.CHAT_MODEL to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CHAT_MODEL,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MODEL,
    ),
    LangChain4jComponentKind.STREAMING_CHAT_MODEL to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_STREAMING_CHAT_MODEL,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_STREAMING_CHAT_MODEL,
    ),
    LangChain4jComponentKind.CHAT_MEMORY_PROVIDER to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CHAT_MEMORY_PROVIDER,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CHAT_MEMORY_PROVIDER,
    ),
    LangChain4jComponentKind.MODERATION_MODEL to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_MODERATION_MODEL,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_MODERATION_MODEL,
    ),
    LangChain4jComponentKind.CONTENT_RETRIEVER to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_CONTENT_RETRIEVER,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_CONTENT_RETRIEVER,
    ),
    LangChain4jComponentKind.RETRIEVAL_AUGMENTOR to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_RETRIEVAL_AUGMENTOR,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_RETRIEVAL_AUGMENTOR,
    ),
    LangChain4jComponentKind.TOOL_PROVIDER to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_TOOL_PROVIDER,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_TOOL_PROVIDER,
    ),
    LangChain4jComponentKind.MCP_CLIENTS to setOf(
      HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_MCP_CLIENTS,
      HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_MCP_CLIENTS,
    ),
  )

  fun keyReferences(yamlKeyValue: YAMLKeyValue): Array<PsiReference> {
    if (yamlKeyValue.key == null) return PsiReference.EMPTY_ARRAY
    if (!isConfigSectionEntry(yamlKeyValue)) return PsiReference.EMPTY_ARRAY

    val range = TextRange.allOf(yamlKeyValue.keyText).shiftRight(ElementManipulators.getOffsetInElement(yamlKeyValue))
    val reference = HelidonLangChain4jConfigReference(yamlKeyValue, range) {
      keyTargets(yamlKeyValue)
    }
    return arrayOf(reference)
  }

  fun valueReferences(yamlScalar: YAMLScalar): Array<PsiReference> {
    val yamlKeyValue = PsiTreeUtil.getParentOfType(yamlScalar, YAMLKeyValue::class.java) ?: return PsiReference.EMPTY_ARRAY
    val path = qualifiedConfigKeyName(yamlKeyValue)
    if (!isSupportedValueReference(path, yamlKeyValue)) return PsiReference.EMPTY_ARRAY

    val value = yamlScalar.textValue.trim()
    if (value.isEmpty()) return PsiReference.EMPTY_ARRAY

    val reference = HelidonLangChain4jConfigReference(yamlScalar, ElementManipulators.getValueTextRange(yamlScalar)) {
      valueTargets(yamlScalar, yamlKeyValue, path, value)
    }
    return arrayOf(reference)
  }

  fun valueCompletionVariants(element: PsiElement): List<String> {
    val yamlKeyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java) ?: return emptyList()
    val path = qualifiedConfigKeyName(yamlKeyValue)
    if (!path.startsWith("$ROOT.")) return emptyList()

    val lastKey = path.substringAfterLast('.')
    if (lastKey != "provider") return emptyList()

    val section = VALUE_CONFIG_TARGETS[lastKey] ?: return emptyList()
    val yamlFile = yamlKeyValue.containingFile as? YAMLFile ?: return emptyList()

    return findYamlSectionEntryKeys(yamlFile, "$ROOT.$section")
      .mapNotNull { it.keyText.takeIf { key -> key.isNotBlank() } }
  }

  fun components(module: Module, includeTests: Boolean): List<LangChain4jComponent> =
    getComponents(module, includeTests, includeLibraries = true)

  fun components(module: Module, includeTests: Boolean, includeLibraries: Boolean): List<LangChain4jComponent> =
    getComponents(module, includeTests, includeLibraries)

  fun configEntries(module: Module, includeTests: Boolean): List<LangChain4jConfigEntry> {
    val entries = LinkedHashSet<LangChain4jConfigEntry>()
    for (configFile in getConfigFiles(module, includeTests)) {
      when (val psiFile = configFile.psiFile) {
        is YAMLFile -> collectYamlConfigEntries(psiFile, entries)
        is PropertiesFile -> collectPropertiesConfigEntries(psiFile, entries)
      }
    }
    return entries.toList()
  }

  fun markerTargets(element: PsiElement): MarkerTargets? {
    if (element is YAMLKeyValue) {
      val anchor = leafAnchor(element.key ?: element)
      val targets = keyTargets(element)
      return targets.takeIf { it.isNotEmpty() }
        ?.let { MarkerTargets(anchor, it, keyGutterKind(element)) }
    }

    if (element is YAMLScalar) {
      val yamlKeyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java) ?: return null
      val path = qualifiedConfigKeyName(yamlKeyValue)
      if (!isSupportedValueReference(path, yamlKeyValue)) return null
      val gutterKind = valueGutterKind(path) ?: return null
      val targets = valueTargets(element, yamlKeyValue, path, element.textValue.trim())
      return targets.takeIf { it.isNotEmpty() }
        ?.let { MarkerTargets(leafAnchor(element), it, gutterKind) }
    }

    return null
  }

  fun annotationValueReferences(element: PsiElement, range: TextRange): Array<PsiReference> {
    val valueElement = annotationValueExpression(element) ?: return PsiReference.EMPTY_ARRAY

    val annotationName = annotationName(valueElement) ?: return PsiReference.EMPTY_ARRAY
    if (!isSupportedAnnotationReference(annotationName)) return PsiReference.EMPTY_ARRAY

    val value = constantString(valueElement)?.trim() ?: return PsiReference.EMPTY_ARRAY
    if (value.isEmpty()) return PsiReference.EMPTY_ARRAY

    val reference = HelidonLangChain4jConfigReference(element, range) {
      annotationValueTargets(valueElement, annotationName, value)
    }
    return arrayOf(reference)
  }

  fun annotationMarkerTargets(element: PsiElement): MarkerTargets? {
    val valueElement = annotationValueExpression(element) ?: return null
    val annotationName = annotationName(valueElement) ?: return null
    val gutterKind = annotationValueGutterKind(annotationName) ?: return null
    val value = constantString(valueElement)?.trim() ?: return null
    if (value.isEmpty()) return null

    val targets = annotationValueTargets(valueElement, annotationName, value)
    return targets.takeIf { it.isNotEmpty() }
      ?.let { MarkerTargets(leafAnchor(valueElement), it, gutterKind) }
  }

  private fun keyTargets(yamlKeyValue: YAMLKeyValue): List<PsiElement> {
    val module = ModuleUtilCore.findModuleForPsiElement(yamlKeyValue) ?: return emptyList()
    val parent = PsiTreeUtil.getParentOfType(yamlKeyValue, YAMLKeyValue::class.java) ?: return emptyList()
    val parentPath = qualifiedConfigKeyName(parent)
    return CONFIG_SECTION_KINDS.flatMap { (section, kinds) ->
      val key = configSectionComponentKey(yamlKeyValue, section) ?: return@flatMap emptyList()
      getComponents(module, includeTestSources(yamlKeyValue, module), includeLibraries = true).filter { component ->
        component.kind in kinds &&
        component.key == key &&
        parentPath == "$ROOT.$section"
      }.map { it.target }
    }
  }

  private fun configSectionComponentKey(yamlKeyValue: YAMLKeyValue, section: String): String? {
    val key = yamlKeyValue.keyText.takeIf { it.isNotBlank() } ?: return null
    if (section != MCP_CLIENTS) return key

    val explicitKey = ((yamlKeyValue.value as? YAMLMapping)?.getKeyValueByKey("key")?.value as? YAMLScalar)?.textValue
    return key.takeIf { explicitKey == null || explicitKey == key }
  }

  private fun isConfigSectionEntry(yamlKeyValue: YAMLKeyValue): Boolean {
    val parent = PsiTreeUtil.getParentOfType(yamlKeyValue, YAMLKeyValue::class.java) ?: return false
    val parentPath = qualifiedConfigKeyName(parent)
    return CONFIG_SECTION_KINDS.keys.any { section -> parentPath == "$ROOT.$section" }
  }

  private fun keyGutterKind(yamlKeyValue: YAMLKeyValue): GutterKind {
    val parent = PsiTreeUtil.getParentOfType(yamlKeyValue, YAMLKeyValue::class.java) ?: return GutterKind.DEFAULT
    val parentPath = qualifiedConfigKeyName(parent)
    return if (ROBOT_KEY_SECTIONS.any { section -> parentPath == "$ROOT.$section" }) GutterKind.ROBOT else GutterKind.DEFAULT
  }

  private fun valueGutterKind(path: String): GutterKind? {
    return when (path.substringAfterLast('.')) {
      in AI_VALUE_KEYS -> GutterKind.AI
      in ROBOT_VALUE_KEYS -> GutterKind.ROBOT
      "provider", "embedding-store" -> null
      else -> GutterKind.DEFAULT
    }
  }

  private fun isSupportedValueReference(path: String, yamlKeyValue: YAMLKeyValue): Boolean {
    if (!path.startsWith("$ROOT.")) return false

    val lastKey = path.substringAfterLast('.')
    if (lastKey in VALUE_CONFIG_TARGETS || lastKey in CLASS_VALUED_KEYS) return true
    if (isMcpClientKeyValue(yamlKeyValue)) return true
    return lastKey == MCP_CLIENTS && yamlKeyValue.value !is YAMLScalar
  }

  private fun isMcpClientKeyValue(yamlKeyValue: YAMLKeyValue): Boolean {
    if (yamlKeyValue.keyText != "key") return false

    val client = PsiTreeUtil.getParentOfType(yamlKeyValue, YAMLKeyValue::class.java) ?: return false
    val section = PsiTreeUtil.getParentOfType(client, YAMLKeyValue::class.java) ?: return false
    return qualifiedConfigKeyName(section) == "$ROOT.$MCP_CLIENTS"
  }

  private fun valueTargets(yamlScalar: YAMLScalar, yamlKeyValue: YAMLKeyValue, path: String, value: String): List<PsiElement> {
    if (value.isEmpty()) return emptyList()
    val module = ModuleUtilCore.findModuleForPsiElement(yamlScalar) ?: return emptyList()
    val lastKey = path.substringAfterLast('.')
    val targets = LinkedHashSet<PsiElement>()

    VALUE_CONFIG_TARGETS[lastKey]
      ?.let { section -> findYamlSectionEntryKey(yamlScalar.containingFile as? YAMLFile, section, value) }
      ?.let { targets.add(it) }

    VALUE_COMPONENT_KINDS[lastKey]?.let { kinds ->
      targets.addAll(componentTargets(yamlScalar, module, kinds, value))
    }

    if (lastKey in CLASS_VALUED_KEYS) {
      targets.addAll(classTargets(yamlScalar, module, value))
    }

    if (lastKey == MCP_CLIENTS && yamlKeyValue.value != yamlScalar) {
      val yamlFile = yamlScalar.containingFile as? YAMLFile
      targets.addAll(findYamlMcpClientKeyValues(yamlFile, value))
      findYamlMcpClientSectionFallback(yamlFile, value)?.let { targets.add(it) }
    }

    return targets.toList()
  }

  private fun annotationName(element: PsiElement): String? {
    val attribute = PsiTreeUtil.getParentOfType(element, PsiNameValuePair::class.java) ?: return null
    return annotationName(attribute)
  }

  private fun annotationName(attribute: PsiNameValuePair): String? {
    if (attribute.name != null && attribute.name != VALUE_ATTRIBUTE) return null

    val annotation = PsiTreeUtil.getParentOfType(attribute, PsiAnnotation::class.java) ?: return null
    return annotation.qualifiedName
  }

  private fun isSupportedAnnotationReference(annotationName: String): Boolean {
    return annotationName in ANNOTATION_CONFIG_SECTIONS || annotationName in ANNOTATION_CONFIG_VALUE_KEYS
  }

  private fun annotationValueExpression(element: PsiElement): PsiElement? {
    val attribute = PsiTreeUtil.getParentOfType(element, PsiNameValuePair::class.java) ?: return null
    val value = attribute.value ?: return null
    if (value is PsiArrayInitializerMemberValue) {
      return value.initializers.firstOrNull { initializer -> PsiTreeUtil.isAncestor(initializer, element, false) }
    }
    return value.takeIf { PsiTreeUtil.isAncestor(it, element, false) }
  }

  private fun annotationValueGutterKind(annotationName: String): GutterKind? {
    return when (annotationName) {
      in AI_ANNOTATION_REFERENCES -> GutterKind.AI
      else -> null
    }
  }

  private fun componentTargets(context: PsiElement,
                               module: Module,
                               kinds: Set<LangChain4jComponentKind>,
                               key: String? = null): List<PsiElement> {
    return getComponents(module, includeTestSources(context, module), includeLibraries = true)
      .filter { it.kind in kinds && (key == null || it.key == key) }
      .map { it.target }
  }

  private fun getComponents(module: Module, includeTests: Boolean, includeLibraries: Boolean): List<LangChain4jComponent> {
    val key = componentCacheKey(includeTests, includeLibraries)
    return CachedValuesManager.getManager(module.project).getCachedValue(module, key, {
      CachedValueProvider.Result.create(calculateComponents(module, includeTests, includeLibraries),
                                        PsiModificationTracker.MODIFICATION_COUNT,
                                        JavaLibraryModificationTracker.getInstance(module.project))
    }, false)
  }

  private fun componentCacheKey(includeTests: Boolean, includeLibraries: Boolean): Key<CachedValue<List<LangChain4jComponent>>> =
    when {
      includeTests && includeLibraries -> TEST_COMPONENTS_KEY
      includeLibraries -> MAIN_COMPONENTS_KEY
      includeTests -> TEST_PROJECT_COMPONENTS_KEY
      else -> MAIN_PROJECT_COMPONENTS_KEY
    }

  private fun calculateComponents(module: Module, includeTests: Boolean, includeLibraries: Boolean): List<LangChain4jComponent> {
    val project = module.project
    val scope = if (includeLibraries) {
      module.getModuleWithDependenciesAndLibrariesScope(includeTests)
    }
    else {
      module.getModuleWithDependenciesScope()
    }
    val annotationScope = module.getModuleWithDependenciesAndLibrariesScope(includeTests)
    val result = LinkedHashSet<LangChain4jComponent>()
    val facade = JavaPsiFacade.getInstance(project)

    for ((kind, annotationNames) in ANNOTATION_KINDS) {
      for (annotationName in annotationNames) {
        val annotationClass = facade.findClass(annotationName, annotationScope) ?: continue
        AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
          val annotation = findAnnotation(psiClass, annotationName) ?: return@forEach
          val values = annotationValues(annotation).filter { it.isNotBlank() }
          val keys = if (values.isEmpty()) {
            fallbackKeys(kind, psiClass)
          }
          else {
            values
          }
          keys.filter { it.isNotBlank() }.forEach { key ->
            result.add(LangChain4jComponent(kind, key, psiClass))
          }
        }
      }
    }

    return result.toList()
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

  private fun fallbackKeys(kind: LangChain4jComponentKind, psiClass: PsiClass): List<String> {
    val qualifiedName = psiClass.qualifiedName
    return when (kind) {
      LangChain4jComponentKind.SERVICE,
      LangChain4jComponentKind.CHAT_MODEL,
      LangChain4jComponentKind.STREAMING_CHAT_MODEL,
      LangChain4jComponentKind.CHAT_MEMORY_PROVIDER,
      LangChain4jComponentKind.MODERATION_MODEL,
      LangChain4jComponentKind.CONTENT_RETRIEVER,
      LangChain4jComponentKind.RETRIEVAL_AUGMENTOR,
      LangChain4jComponentKind.TOOL_PROVIDER -> listOfNotNull(qualifiedName)
      else -> emptyList()
    }
  }

  private fun classTargets(context: PsiElement, module: Module, value: String): List<PsiElement> {
    val scope = module.getModuleWithDependenciesAndLibrariesScope(includeTestSources(context, module))
    val classes = if (value.contains('.')) {
      listOfNotNull(JavaPsiFacade.getInstance(module.project).findClass(value, scope))
    }
    else {
      PsiShortNamesCache.getInstance(module.project).getClassesByName(value, scope).toList()
    }
    return classes
  }

  private fun findYamlKey(file: YAMLFile?, qualifiedName: String): YAMLKeyValue? {
    return findYamlKeys(file, qualifiedName).firstOrNull()
  }

  private fun findYamlKeys(file: YAMLFile?, qualifiedName: String): List<YAMLKeyValue> {
    if (file == null) return emptyList()
    return file.documents
      .mapNotNull { HelidonConfigYamlAccessor(it).findExistingKey(qualifiedName) }
  }

  private fun findYamlSectionEntryKeys(file: YAMLFile, qualifiedSectionName: String): List<YAMLKeyValue> {
    return findYamlKeys(file, qualifiedSectionName)
      .flatMap { section -> (section.value as? YAMLMapping)?.keyValues?.toList() ?: emptyList() }
  }

  private fun collectYamlConfigEntries(file: YAMLFile, result: MutableSet<LangChain4jConfigEntry>) {
    for (section in CONFIG_SECTIONS) {
      for (entry in findYamlSectionEntryKeys(file, "$ROOT.$section")) {
        val key = entry.keyText.takeIf { it.isNotBlank() } ?: continue
        result.add(LangChain4jConfigEntry(section, key, entry))
      }
    }
  }

  private fun collectPropertiesConfigEntries(file: PropertiesFile, result: MutableSet<LangChain4jConfigEntry>) {
    for (property in file.properties) {
      val propertyImpl = property.psiElement as? PropertyImpl ?: continue
      val key = property.key ?: continue
      val parts = key.split('.')
      if (parts.size < 3 || parts[0] != ROOT) continue
      val section = parts[1]
      if (section !in CONFIG_SECTIONS) continue
      val runtimeKey = parts[2].takeIf { it.isNotBlank() } ?: continue
      result.add(LangChain4jConfigEntry(section, runtimeKey, propertyImpl))
    }
  }

  private fun findYamlSectionEntryKey(file: YAMLFile?, section: String, key: String): YAMLKeyValue? {
    if (file == null) return null
    return findYamlKeys(file, "$ROOT.$section")
      .asSequence()
      .mapNotNull { sectionKey -> (sectionKey.value as? YAMLMapping)?.getKeyValueByKey(key) }
      .firstOrNull()
  }

  private fun findYamlMcpClientKeyValues(file: YAMLFile?, value: String): List<YAMLScalar> {
    val clients = findYamlSectionEntryKeys(file ?: return emptyList(), "$ROOT.$MCP_CLIENTS")
    return clients.mapNotNull { client ->
      val mapping = client.value as? YAMLMapping ?: return@mapNotNull null
      val keyValue = mapping.getKeyValueByKey("key") ?: return@mapNotNull null
      (keyValue.value as? YAMLScalar)?.takeIf { it.textValue == value }
    }
  }

  private fun findYamlMcpClientSectionFallback(file: YAMLFile?, value: String): YAMLKeyValue? {
    val client = findYamlSectionEntryKey(file, MCP_CLIENTS, value) ?: return null
    val keyScalar = ((client.value as? YAMLMapping)?.getKeyValueByKey("key")?.value as? YAMLScalar)
    return client.takeIf { keyScalar == null || keyScalar.textValue == value }
  }

  private fun annotationValueTargets(context: PsiElement, annotationName: String, value: String): List<PsiElement> {
    val targets = LinkedHashSet<PsiElement>()
    if (annotationName in MCP_CLIENT_ANNOTATIONS) {
      targets.addAll(findMcpClientKeyValueUsages(context, value))
      targets.addAll(findMcpClientSectionFallbackUsages(context, value))
    }
    else {
      ANNOTATION_CONFIG_SECTIONS[annotationName]
        ?.let { section -> targets.addAll(findConfigSectionEntryKeys(context, section, value)) }
    }
    ANNOTATION_CONFIG_VALUE_KEYS[annotationName]
      ?.let { keyNames -> targets.addAll(findConfigValueUsages(context, keyNames, value)) }
    return targets.toList()
  }

  private fun findMcpClientSectionFallbackUsages(context: PsiElement, value: String): List<PsiElement> {
    return processConfigFiles(context) { psiFile, contributor ->
      if (psiFile is YAMLFile) {
        return@processConfigFiles findYamlMcpClientSectionFallback(psiFile, value)?.let(::listOf) ?: emptyList()
      }
      if (!mcpClientSectionFallbackAllowed(psiFile, value)) return@processConfigFiles emptyList()
      contributor.findKey(psiFile, "$ROOT.$MCP_CLIENTS.$value")?.let(::listOf) ?: emptyList()
    }
  }

  private fun findConfigSectionEntryKeys(context: PsiElement, section: String, key: String): List<PsiElement> {
    return processConfigFiles(context) { psiFile, contributor ->
      if (psiFile is YAMLFile) {
        return@processConfigFiles findYamlSectionEntryKey(psiFile, section, key)?.let(::listOf) ?: emptyList()
      }
      contributor.findKey(psiFile, "$ROOT.$section.$key")?.let(::listOf) ?: emptyList()
    }
  }

  private fun findMcpClientKeyValueUsages(context: PsiElement, value: String): List<PsiElement> {
    return processConfigFiles(context) { psiFile, _ ->
      when (psiFile) {
        is YAMLFile -> findYamlMcpClientKeyValues(psiFile, value)
        is PropertiesFile -> findPropertiesMcpClientKeyValueUsages(psiFile, value)
        else -> emptyList()
      }
    }
  }

  private fun findConfigValueUsages(context: PsiElement, keyNames: Set<String>, value: String): List<PsiElement> {
    return processConfigFiles(context) { psiFile, _ ->
      when (psiFile) {
        is YAMLFile -> findYamlValueUsages(psiFile, keyNames, value)
        is PropertiesFile -> findPropertiesValueUsages(psiFile, keyNames, value)
        else -> emptyList()
      }
    }
  }

  private fun processConfigFiles(context: PsiElement,
                                 processor: (PsiFile, HelidonConfigFileContributor) -> List<PsiElement>): List<PsiElement> {
    val module = ModuleUtilCore.findModuleForPsiElement(context) ?: return emptyList()
    val containingFile = context.containingFile?.originalFile?.virtualFile
    val includeTests = containingFile != null &&
                       ModuleRootManager.getInstance(module).fileIndex.isInTestSourceContent(containingFile)
    return getConfigFiles(module, includeTests).flatMap { (psiFile, contributor) -> processor(psiFile, contributor) }
  }

  private fun getConfigFiles(module: Module, includeTests: Boolean): List<ConfigFile> {
    val key = if (includeTests) TEST_CONFIG_FILES_KEY else MAIN_CONFIG_FILES_KEY
    return CachedValuesManager.getManager(module.project).getCachedValue(module, key, {
      val psiManager = PsiManager.getInstance(module.project)
      val files = HelidonConfigFileContributor.findConfigFiles(module, includeTests)
        .mapNotNull { (configFile, contributor) ->
          psiManager.findFile(configFile)?.let { ConfigFile(it, contributor) }
        }

      val dependencies = ArrayList<Any>(files.size + 2)
      dependencies.addAll(files.map { it.psiFile })
      dependencies.add(ProjectRootModificationTracker.getInstance(module.project))
      dependencies.add(VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)

      CachedValueProvider.Result.create(files, dependencies)
    }, false)
  }

  private fun findYamlValueUsages(file: YAMLFile, keyNames: Set<String>, value: String): List<PsiElement> {
    val result = ArrayList<PsiElement>()
    for (keyValue in PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)) {
      val qualifiedName = qualifiedConfigKeyName(keyValue)
      if (!qualifiedName.startsWith("$ROOT.") || qualifiedName.substringAfterLast('.') !in keyNames) continue

      when (val yamlValue = keyValue.value) {
        is YAMLScalar -> if (yamlValue.textValue == value) result.add(yamlValue)
        is YAMLSequence -> yamlValue.items.mapNotNull { it.value as? YAMLScalar }
          .filterTo(result) { it.textValue == value }
      }
    }
    return result
  }

  private fun findPropertiesValueUsages(file: PropertiesFile, keyNames: Set<String>, value: String): List<PsiElement> {
    return file.properties.mapNotNull { property ->
      val propertyImpl = property.psiElement as? PropertyImpl ?: return@mapNotNull null
      val key = propertyImpl.key ?: return@mapNotNull null
      if (key.startsWith("$ROOT.") &&
          key.substringAfterLast('.') in keyNames &&
          propertyImpl.value == value) {
        HelidonPropertiesUtils.getPropertyValue(propertyImpl) ?: propertyImpl
      }
      else {
        null
      }
    }
  }

  private fun findPropertiesMcpClientKeyValueUsages(file: PropertiesFile, value: String): List<PsiElement> {
    return file.properties.mapNotNull { property ->
      val propertyImpl = property.psiElement as? PropertyImpl ?: return@mapNotNull null
      val key = propertyImpl.key ?: return@mapNotNull null
      if (key.startsWith("$ROOT.$MCP_CLIENTS.") &&
          key.endsWith(".key") &&
          propertyImpl.value == value) {
        HelidonPropertiesUtils.getPropertyValue(propertyImpl) ?: propertyImpl
      }
      else {
        null
      }
    }
  }

  private fun mcpClientSectionFallbackAllowed(psiFile: PsiFile, value: String): Boolean {
    return when (psiFile) {
      is YAMLFile -> findYamlMcpClientSectionFallback(psiFile, value) != null
      is PropertiesFile -> explicitPropertiesMcpClientKey(psiFile, value)?.let { it == value } ?: true
      else -> true
    }
  }

  private fun explicitPropertiesMcpClientKey(file: PropertiesFile, sectionName: String): String? {
    val keyName = "$ROOT.$MCP_CLIENTS.$sectionName.key"
    return file.properties
      .mapNotNull { it.psiElement as? PropertyImpl }
      .firstOrNull { it.key == keyName }
      ?.value
  }

  private fun includeTestSources(context: PsiElement, module: Module): Boolean {
    val virtualFile = context.containingFile?.originalFile?.virtualFile ?: return false
    return ModuleRootManager.getInstance(module).fileIndex.isInTestSourceContent(virtualFile)
  }

  private fun leafAnchor(element: PsiElement): PsiElement {
    var anchor = element
    while (anchor.firstChild != null) {
      anchor = anchor.firstChild
    }
    return anchor
  }

  private fun qualifiedConfigKeyName(yamlKeyValue: YAMLKeyValue): String {
    return com.intellij.helidon.config.yaml.getQualifiedConfigKeyName(yamlKeyValue)
  }

  data class LangChain4jComponent(
    val kind: LangChain4jComponentKind,
    val key: String,
    val target: PsiElement,
  )

  data class LangChain4jConfigEntry(
    val section: String,
    val key: String,
    val target: PsiElement,
  )

  private data class ConfigFile(
    val psiFile: PsiFile,
    val contributor: HelidonConfigFileContributor,
  )

  data class MarkerTargets(
    val anchor: PsiElement,
    val targets: List<PsiElement>,
    val gutterKind: GutterKind,
  )

  enum class GutterKind {
    DEFAULT,
    ROBOT,
    AI,
  }

  enum class LangChain4jComponentKind(val presentableName: String) {
    SERVICE("@Ai.Service"),
    AGENT("@Ai.Agent"),
    CHAT_MODEL("@Ai.ChatModel"),
    STREAMING_CHAT_MODEL("@Ai.StreamingChatModel"),
    CHAT_MEMORY_PROVIDER("@Ai.ChatMemoryProvider"),
    MODERATION_MODEL("@Ai.ModerationModel"),
    CONTENT_RETRIEVER("@Ai.ContentRetriever"),
    RETRIEVAL_AUGMENTOR("@Ai.RetrievalAugmentor"),
    TOOL_PROVIDER("@Ai.ToolProvider"),
    MCP_CLIENTS("@Ai.McpClients"),
  }
}
