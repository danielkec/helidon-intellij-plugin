// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j

import com.intellij.helidon.constants.HelidonConstants
import com.intellij.helidon.config.HelidonConfigFileContributor
import com.intellij.helidon.config.properties.HelidonPropertiesUtils
import com.intellij.java.library.JavaLibraryModificationTracker
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.impl.PropertyImpl
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
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
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
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

  private val COMPONENTS_KEY: Key<CachedValue<List<LangChain4jComponent>>> =
    Key.create("HELIDON_LANGCHAIN4J_COMPONENTS")

  private val CONFIG_SECTION_KINDS: Map<String, Set<LangChain4jComponentKind>> = mapOf(
    SERVICES to setOf(LangChain4jComponentKind.SERVICE),
    AGENTS to setOf(LangChain4jComponentKind.AGENT),
    MODELS to setOf(LangChain4jComponentKind.CHAT_MODEL, LangChain4jComponentKind.STREAMING_CHAT_MODEL),
    CONTENT_RETRIEVERS to setOf(LangChain4jComponentKind.CONTENT_RETRIEVER),
    MCP_CLIENTS to setOf(LangChain4jComponentKind.MCP_CLIENTS),
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
    "chat-model" to setOf(LangChain4jComponentKind.CHAT_MODEL, LangChain4jComponentKind.STREAMING_CHAT_MODEL),
    "streaming-chat-model" to setOf(LangChain4jComponentKind.STREAMING_CHAT_MODEL),
    "moderation-model" to setOf(LangChain4jComponentKind.MODERATION_MODEL),
    "embedding-model" to setOf(LangChain4jComponentKind.CHAT_MODEL),
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

  private val GEAR_VALUE_KEYS: Set<String> = setOf("provider", "embedding-store")

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

  fun markerTargets(element: PsiElement): MarkerTargets? {
    if (element is YAMLKeyValue) {
      val anchor = element.key ?: element
      val targets = keyTargets(element)
      return targets.takeIf { it.isNotEmpty() }
        ?.let { MarkerTargets(anchor, it, keyGutterKind(element)) }
    }

    if (element is YAMLScalar) {
      val yamlKeyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java) ?: return null
      val path = qualifiedConfigKeyName(yamlKeyValue)
      val targets = valueTargets(element, yamlKeyValue, path, element.textValue.trim())
      return targets.takeIf { it.isNotEmpty() }
        ?.let { MarkerTargets(element.firstChild ?: element, it, valueGutterKind(path)) }
    }

    return null
  }

  fun annotationValueReferences(element: PsiElement, range: TextRange): Array<PsiReference> {
    val annotationName = annotationName(element) ?: return PsiReference.EMPTY_ARRAY
    if (!isSupportedAnnotationReference(annotationName)) return PsiReference.EMPTY_ARRAY

    val value = ElementManipulators.getValueText(element).trim()
    if (value.isEmpty()) return PsiReference.EMPTY_ARRAY

    val reference = HelidonLangChain4jConfigReference(element, range) {
      annotationValueTargets(element, annotationName, value)
    }
    return arrayOf(reference)
  }

  fun annotationMarkerTargets(element: PsiElement): MarkerTargets? {
    val literal = PsiTreeUtil.getParentOfType(element, PsiLiteralExpression::class.java, false) ?: return null
    if (literal.value !is String) return null

    val annotationName = annotationName(literal) ?: return null
    val gutterKind = annotationValueGutterKind(annotationName) ?: return null
    val value = ElementManipulators.getValueText(literal).trim()
    if (value.isEmpty()) return null

    val targets = annotationValueTargets(literal, annotationName, value)
    return targets.takeIf { it.isNotEmpty() }
      ?.let { MarkerTargets(literal.firstChild ?: literal, it, gutterKind) }
  }

  private fun keyTargets(yamlKeyValue: YAMLKeyValue): List<PsiElement> {
    val module = ModuleUtilCore.findModuleForPsiElement(yamlKeyValue) ?: return emptyList()
    val path = qualifiedConfigKeyName(yamlKeyValue)
    return CONFIG_SECTION_KINDS.flatMap { (section, kinds) ->
      getComponents(module).filter { component ->
        component.kind in kinds &&
        path == "$ROOT.$section.${component.key}"
      }.map { it.target }
    }
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

  private fun valueGutterKind(path: String): GutterKind {
    return when (path.substringAfterLast('.')) {
      in GEAR_VALUE_KEYS -> GutterKind.GEAR
      in AI_VALUE_KEYS -> GutterKind.AI
      in ROBOT_VALUE_KEYS -> GutterKind.ROBOT
      else -> GutterKind.DEFAULT
    }
  }

  private fun isSupportedValueReference(path: String, yamlKeyValue: YAMLKeyValue): Boolean {
    if (!path.startsWith("$ROOT.")) return false

    val lastKey = path.substringAfterLast('.')
    if (lastKey in VALUE_CONFIG_TARGETS || lastKey in CLASS_VALUED_KEYS) return true
    if (lastKey == "key" && path.startsWith("$ROOT.$MCP_CLIENTS.")) return true
    return lastKey == MCP_CLIENTS && yamlKeyValue.value !is YAMLScalar
  }

  private fun valueTargets(yamlScalar: YAMLScalar, yamlKeyValue: YAMLKeyValue, path: String, value: String): List<PsiElement> {
    if (value.isEmpty()) return emptyList()
    val module = ModuleUtilCore.findModuleForPsiElement(yamlScalar) ?: return emptyList()
    val lastKey = path.substringAfterLast('.')
    val targets = LinkedHashSet<PsiElement>()

    VALUE_CONFIG_TARGETS[lastKey]
      ?.let { section -> findYamlKey(yamlScalar.containingFile as? YAMLFile, "$ROOT.$section.$value") }
      ?.let { targets.add(it) }

    if (lastKey == "key" && path.startsWith("$ROOT.$MCP_CLIENTS.")) {
      targets.addAll(componentTargets(module, setOf(LangChain4jComponentKind.MCP_CLIENTS), value))
    }
    else {
      VALUE_COMPONENT_KINDS[lastKey]?.let { kinds ->
        targets.addAll(componentTargets(module, kinds, value))
      }
    }

    if (lastKey in CLASS_VALUED_KEYS) {
      targets.addAll(classTargets(module, value))
    }

    if (lastKey == MCP_CLIENTS && yamlKeyValue.value != yamlScalar) {
      findYamlKey(yamlScalar.containingFile as? YAMLFile, "$ROOT.$MCP_CLIENTS.$value")?.let { targets.add(it) }
    }

    return targets.toList()
  }

  private fun annotationName(element: PsiElement): String? {
    val attribute = PsiTreeUtil.getParentOfType(element, PsiNameValuePair::class.java) ?: return null
    if (attribute.name != null && attribute.name != VALUE_ATTRIBUTE) return null

    val annotation = PsiTreeUtil.getParentOfType(attribute, PsiAnnotation::class.java) ?: return null
    return annotation.qualifiedName
  }

  private fun isSupportedAnnotationReference(annotationName: String): Boolean {
    return annotationName in ANNOTATION_CONFIG_SECTIONS || annotationName in ANNOTATION_CONFIG_VALUE_KEYS
  }

  private fun annotationValueGutterKind(annotationName: String): GutterKind? {
    return when (annotationName) {
      in AI_ANNOTATION_REFERENCES -> GutterKind.AI
      else -> null
    }
  }

  private fun componentTargets(module: Module,
                               kinds: Set<LangChain4jComponentKind>,
                               key: String? = null): List<PsiElement> {
    return getComponents(module)
      .filter { it.kind in kinds && (key == null || it.key == key) }
      .map { it.target }
  }

  private fun getComponents(module: Module): List<LangChain4jComponent> {
    return CachedValuesManager.getManager(module.project).getCachedValue(module, COMPONENTS_KEY, {
      CachedValueProvider.Result.create(calculateComponents(module),
                                        PsiModificationTracker.MODIFICATION_COUNT,
                                        JavaLibraryModificationTracker.getInstance(module.project))
    }, false)
  }

  private fun calculateComponents(module: Module): List<LangChain4jComponent> {
    val project = module.project
    val scope = module.getModuleWithDependenciesAndLibrariesScope(true)
    val result = LinkedHashSet<LangChain4jComponent>()
    val facade = JavaPsiFacade.getInstance(project)

    for ((kind, annotationNames) in ANNOTATION_KINDS) {
      for (annotationName in annotationNames) {
        val annotationClass = facade.findClass(annotationName, scope) ?: continue
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
    if (element is PsiReferenceExpression) {
      return JavaPsiFacade.getInstance(element.project).constantEvaluationHelper.computeConstantExpression(element) as? String
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

  private fun classTargets(module: Module, value: String): List<PsiElement> {
    val scope = module.getModuleWithDependenciesAndLibrariesScope(true)
    val classes = if (value.contains('.')) {
      listOfNotNull(JavaPsiFacade.getInstance(module.project).findClass(value, scope))
    }
    else {
      PsiShortNamesCache.getInstance(module.project).getClassesByName(value, scope).toList()
    }
    return classes
  }

  private fun findYamlKey(file: YAMLFile?, qualifiedName: String): YAMLKeyValue? {
    if (file == null) return null
    return PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
      .firstOrNull { qualifiedConfigKeyName(it) == qualifiedName }
  }

  private fun findConfigKeys(context: PsiElement, qualifiedName: String): List<PsiElement> {
    return processConfigFiles(context) { psiFile, contributor ->
      contributor.findKey(psiFile, qualifiedName)?.let(::listOf) ?: emptyList()
    }
  }

  private fun annotationValueTargets(context: PsiElement, annotationName: String, value: String): List<PsiElement> {
    val targets = LinkedHashSet<PsiElement>()
    ANNOTATION_CONFIG_SECTIONS[annotationName]
      ?.let { section -> targets.addAll(findConfigKeys(context, "$ROOT.$section.$value")) }
    ANNOTATION_CONFIG_VALUE_KEYS[annotationName]
      ?.let { keyNames -> targets.addAll(findConfigValueUsages(context, keyNames, value)) }
    return targets.toList()
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
    val psiManager = PsiManager.getInstance(module.project)
    return HelidonConfigFileContributor.findConfigFiles(module, includeTests)
      .flatMap { (configFile, contributor) ->
        val psiFile = psiManager.findFile(configFile) ?: return@flatMap emptyList()
        processor(psiFile, contributor)
      }
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

  private fun qualifiedConfigKeyName(yamlKeyValue: YAMLKeyValue): String {
    return com.intellij.helidon.config.yaml.getQualifiedConfigKeyName(yamlKeyValue)
  }

  private data class LangChain4jComponent(
    val kind: LangChain4jComponentKind,
    val key: String,
    val target: PsiElement,
  )

  data class MarkerTargets(
    val anchor: PsiElement,
    val targets: List<PsiElement>,
    val gutterKind: GutterKind,
  )

  enum class GutterKind {
    DEFAULT,
    ROBOT,
    GEAR,
    AI,
  }

  private enum class LangChain4jComponentKind {
    SERVICE,
    AGENT,
    CHAT_MODEL,
    STREAMING_CHAT_MODEL,
    CHAT_MEMORY_PROVIDER,
    MODERATION_MODEL,
    CONTENT_RETRIEVER,
    RETRIEVAL_AUGMENTOR,
    TOOL_PROVIDER,
    MCP_CLIENTS,
  }
}
