// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.services

import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.constants.HelidonConstants
import com.intellij.helidon.langchain4j.HelidonLangChain4jConfigResolver
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import java.util.Locale
import javax.swing.Icon

data class HelidonServicesFilter(
  val moduleName: String? = null,
  val includeTests: Boolean = false,
  val includeLibraries: Boolean = false,
  val kind: HelidonServicesNodeKind? = null,
  val showOnlyProblems: Boolean = false,
)

data class HelidonServicesSnapshot(
  val modules: List<String>,
  val nodes: List<HelidonServicesNode>,
) {
  val isEmpty: Boolean
    get() = nodes.isEmpty()
}

data class HelidonServicesNode(
  val id: String,
  val kind: HelidonServicesNodeKind,
  val moduleName: String,
  val sourceSet: HelidonServicesSourceSet,
  val name: String,
  val details: String? = null,
  val groupName: String? = null,
  val groupSortOrder: Int = Int.MAX_VALUE,
  val status: HelidonServicesResolutionStatus = HelidonServicesResolutionStatus.RESOLVED,
  val navigation: SmartPsiElementPointer<PsiElement>? = null,
  val navigationFile: VirtualFile? = null,
  val navigationOffset: Int = 0,
  val parentId: String? = null,
  val packageName: String? = null,
  val ownerClassName: String? = null,
  val ownerClassQualifiedName: String? = null,
) {
  val navigationElement: PsiElement?
    get() = navigation?.element
}

enum class HelidonServicesNodeKind(val presentableName: String) {
  SERVICE("Services"),
  CONTRACT("Contracts"),
  INJECTION_POINT("Injection points"),
  SERVICE_LOOKUP("Service lookups"),
  HTTP_ENDPOINT("HTTP endpoints"),
  LANGCHAIN4J_COMPONENT("LangChain4j components"),
  LANGCHAIN4J_CONFIG("LangChain4j config"),
}

enum class HelidonServicesSourceSet(val presentableName: String) {
  MAIN("main"),
  TEST("test"),
  LIBRARY("library"),
}

enum class HelidonServicesResolutionStatus(val presentableName: String) {
  RESOLVED("Resolved"),
  AMBIGUOUS("Ambiguous"),
  UNRESOLVED("Unresolved"),
}

interface HelidonServicesViewContributor {
  fun collect(module: Module, filter: HelidonServicesFilter): List<HelidonServicesNode>

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<HelidonServicesViewContributor> =
      ExtensionPointName.create("com.intellij.helidon.servicesViewContributor")
  }
}

object HelidonServicesModel {
  private const val HELIDON_COMMON_GENERATED = "io.helidon.common.Generated"

  private val LANGCHAIN4J_CONFIG_SECTION_ORDER = listOf(
    "services",
    "agents",
    "models",
    "providers",
    "embedding-stores",
    "content-retrievers",
    "mcp-clients",
  ).withIndex().associate { (index, section) -> section to index }

  private val LANGCHAIN4J_AI_CONFIG_SECTIONS = setOf("models", "providers")

  private const val LANGCHAIN4J_CONTENT_RETRIEVERS_SECTION = "content-retrievers"

  private const val LANGCHAIN4J_EMBEDDING_STORES_SECTION = "embedding-stores"

  private val SERVICE_REGISTRY_KINDS = setOf(
    HelidonServicesNodeKind.SERVICE,
    HelidonServicesNodeKind.CONTRACT,
    HelidonServicesNodeKind.INJECTION_POINT,
    HelidonServicesNodeKind.SERVICE_LOOKUP,
  )

  private val SERVICE_SCOPE_ANNOTATIONS = listOf(
    HelidonConstants.SERVICE_SINGLETON,
    HelidonConstants.SERVICE_PROVIDER,
    HelidonConstants.SERVICE_PER_LOOKUP,
    HelidonConstants.SERVICE_PER_REQUEST,
    HelidonConstants.REST_SERVER_ENDPOINT,
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_SERVICE,
    HelidonConstants.LANGCHAIN4J_EXTENSIONS_AI_AGENT,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_SERVICE,
    HelidonConstants.LANGCHAIN4J_INTEGRATIONS_AI_AGENT,
  )

  private val SERVICE_INJECTION_WRAPPER_TYPES = setOf(
    "java.lang.Iterable",
    "java.util.Collection",
    "java.util.List",
    "java.util.Optional",
    "java.util.Set",
    "java.util.function.Supplier",
    "java.util.stream.Stream",
    "javax.inject.Provider",
    "jakarta.inject.Provider",
  )

  fun collect(project: Project, filter: HelidonServicesFilter = HelidonServicesFilter()): HelidonServicesSnapshot {
    val allHelidonModules = ModuleManager.getInstance(project).modules
      .asSequence()
      .filter { !it.isDisposed }
      .filter { HelidonCoreUtils.hasHelidonLibrary(it) }
      .sortedBy { it.name }
      .toList()
    if (allHelidonModules.isEmpty()) {
      return HelidonServicesSnapshot(emptyList(), emptyList())
    }

    val modules = allHelidonModules
      .filter { filter.moduleName == null || it.name == filter.moduleName }

    val nodes = ArrayList<HelidonServicesNode>()
    for (module in modules) {
      if (shouldCollectServiceRegistry(filter)) {
        nodes.addAll(collectServiceRegistryNodes(module, filter))
      }
      if (shouldCollectLangChain4j(filter)) {
        nodes.addAll(collectLangChain4jNodes(module, filter))
      }
      if (shouldCollectContributors(filter)) {
        for (contributor in HelidonServicesViewContributor.EP_NAME.extensionList) {
          nodes.addAll(contributor.collect(module, filter))
        }
      }
    }

    return HelidonServicesSnapshot(
      modules = allHelidonModules.map { it.name },
      nodes = nodes
        .asSequence()
        .filter { accepts(it, filter) }
        .distinctBy { it.id }
        .sortedWith(compareBy<HelidonServicesNode> { it.moduleName }
                      .thenBy { it.kind.ordinal }
                      .thenBy { it.packageName.orEmpty() }
                      .thenBy { it.ownerClassQualifiedName.orEmpty() }
                      .thenBy { it.groupSortOrder }
                      .thenBy { it.groupName.orEmpty() }
                      .thenBy { it.name.lowercase(Locale.ENGLISH) }
                      .thenBy { it.details.orEmpty() })
        .toList(),
    )
  }

  fun searchScope(module: Module, filter: HelidonServicesFilter): GlobalSearchScope =
    if (filter.includeLibraries) {
      module.getModuleWithDependenciesAndLibrariesScope(filter.includeTests)
    }
    else {
      module.getModuleWithDependenciesScope()
    }

  fun sourceSet(module: Module, element: PsiElement): HelidonServicesSourceSet {
    val virtualFile = element.containingFile?.originalFile?.virtualFile ?: return HelidonServicesSourceSet.MAIN
    val fileIndex = ModuleRootManager.getInstance(module).fileIndex
    return when {
      fileIndex.isInTestSourceContent(virtualFile) -> HelidonServicesSourceSet.TEST
      !fileIndex.isInContent(virtualFile) -> HelidonServicesSourceSet.LIBRARY
      else -> HelidonServicesSourceSet.MAIN
    }
  }

  fun icon(node: HelidonServicesNode): Icon =
    when {
      node.kind == HelidonServicesNodeKind.LANGCHAIN4J_CONFIG &&
      node.groupName in LANGCHAIN4J_AI_CONFIG_SECTIONS -> HelidonIcons.AiGutter
      node.kind == HelidonServicesNodeKind.LANGCHAIN4J_CONFIG &&
      node.groupName == LANGCHAIN4J_CONTENT_RETRIEVERS_SECTION -> HelidonIcons.GearGutter
      node.kind == HelidonServicesNodeKind.LANGCHAIN4J_CONFIG &&
      node.groupName == LANGCHAIN4J_EMBEDDING_STORES_SECTION -> HelidonIcons.DataSourceGutter
      else -> icon(node.kind)
    }

  fun icon(kind: HelidonServicesNodeKind): Icon =
    when (kind) {
      HelidonServicesNodeKind.SERVICE,
      HelidonServicesNodeKind.CONTRACT -> HelidonIcons.HelidonBeanGutter
      HelidonServicesNodeKind.LANGCHAIN4J_COMPONENT,
      HelidonServicesNodeKind.LANGCHAIN4J_CONFIG -> HelidonIcons.RobotGutter
      HelidonServicesNodeKind.INJECTION_POINT,
      HelidonServicesNodeKind.SERVICE_LOOKUP,
      HelidonServicesNodeKind.HTTP_ENDPOINT -> HelidonIcons.HelidonGutter
    }

  private fun accepts(node: HelidonServicesNode, filter: HelidonServicesFilter): Boolean {
    if (filter.kind != null && node.kind != filter.kind) return false
    if (!filter.includeTests && node.sourceSet == HelidonServicesSourceSet.TEST) return false
    if (!filter.includeLibraries && node.sourceSet == HelidonServicesSourceSet.LIBRARY) return false
    if (filter.showOnlyProblems && node.status == HelidonServicesResolutionStatus.RESOLVED) return false
    return true
  }

  private fun shouldCollectServiceRegistry(filter: HelidonServicesFilter): Boolean =
    filter.kind == null || filter.kind in SERVICE_REGISTRY_KINDS

  private fun shouldCollectLangChain4j(filter: HelidonServicesFilter): Boolean =
    !filter.showOnlyProblems &&
    (filter.kind == null ||
     filter.kind == HelidonServicesNodeKind.LANGCHAIN4J_COMPONENT ||
     filter.kind == HelidonServicesNodeKind.LANGCHAIN4J_CONFIG)

  private fun shouldCollectContributors(filter: HelidonServicesFilter): Boolean =
    !filter.showOnlyProblems && (filter.kind == null || filter.kind == HelidonServicesNodeKind.HTTP_ENDPOINT)

  private fun collectServiceRegistryNodes(module: Module, filter: HelidonServicesFilter): List<HelidonServicesNode> {
    val scope = searchScope(module, filter)
    val services = collectServiceClasses(module, scope)
    if (services.isEmpty()) return emptyList()

    val serviceInfos = services.map { psiClass ->
      val contracts = HelidonCoreUtils.getHelidonServiceContracts(psiClass).toList()
      val names = HelidonCoreUtils.getHelidonServiceNames(psiClass)
      val serviceModule = ModuleUtilCore.findModuleForPsiElement(psiClass) ?: module
      ServiceInfo(serviceNodeId(serviceModule, psiClass), psiClass, contracts, names)
    }
    val nodes = ArrayList<HelidonServicesNode>()

    if (!filter.showOnlyProblems && (filter.kind == null || filter.kind == HelidonServicesNodeKind.SERVICE)) {
      for (service in serviceInfos) {
        nodes.add(serviceNode(module, service))
      }
    }
    if (!filter.showOnlyProblems && (filter.kind == null || filter.kind == HelidonServicesNodeKind.CONTRACT)) {
      for (service in serviceInfos) {
        nodes.addAll(contractNodes(module, service))
      }
    }
    if (filter.kind == null || filter.kind == HelidonServicesNodeKind.INJECTION_POINT) {
      nodes.addAll(injectionPointNodes(module, scope, serviceInfos))
    }
    if (filter.kind == null || filter.kind == HelidonServicesNodeKind.SERVICE_LOOKUP) {
      nodes.addAll(serviceLookupNodes(module, filter, scope, serviceInfos))
    }
    return nodes
  }

  private fun collectServiceClasses(module: Module, scope: GlobalSearchScope): List<PsiClass> {
    val facade = JavaPsiFacade.getInstance(module.project)
    val services = LinkedHashSet<PsiClass>()
    val visitedAnnotations = HashSet<String>()
    for (annotationName in SERVICE_SCOPE_ANNOTATIONS) {
      collectServiceClassesAnnotatedWith(facade, scope, annotationName, services, visitedAnnotations)
    }
    return services.toList()
  }

  private fun collectServiceClassesAnnotatedWith(facade: JavaPsiFacade,
                                                 scope: GlobalSearchScope,
                                                 annotationName: String,
                                                 services: MutableSet<PsiClass>,
                                                 visitedAnnotations: MutableSet<String>) {
    val annotationClass = facade.findClass(annotationName, scope) ?: return
    val annotationKey = annotationClass.qualifiedName ?: elementKey(annotationClass)
    if (!visitedAnnotations.add(annotationKey)) return

    AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
      if (psiClass.isAnnotationType) {
        psiClass.qualifiedName?.let { collectServiceClassesAnnotatedWith(facade, scope, it, services, visitedAnnotations) }
      }
      else if (!isGenerated(psiClass) && HelidonCoreUtils.isHelidonServiceRegistryClass(psiClass)) {
        services.add(psiClass)
      }
    }
  }

  private fun serviceNode(module: Module, service: ServiceInfo): HelidonServicesNode {
    val psiClass = service.psiClass
    val qualifiedName = psiClass.qualifiedName
    val details = listOfNotNull(
      HelidonCoreUtils.getHelidonServiceScopeAnnotationName(psiClass)?.substringAfterLast('.'),
      HelidonCoreUtils.getHelidonServiceNames(psiClass).takeIf { it.isNotEmpty() }?.joinToString(prefix = "names: "),
      service.contractNames()
        .filter { it != qualifiedName }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "contracts: "),
    ).joinToString(" | ").ifBlank { null }
    return node(
      id = service.nodeId,
      kind = HelidonServicesNodeKind.SERVICE,
      module = module,
      element = psiClass,
      name = psiClass.name ?: qualifiedName ?: "Service",
      details = details,
      ownerClass = psiClass,
    )
  }

  private fun contractNodes(module: Module, service: ServiceInfo): List<HelidonServicesNode> =
    service.contracts
      .filter { !module.project.isDisposed }
      .filterNot { service.psiClass.manager.areElementsEquivalent(it, service.psiClass) }
      .map { contract ->
        node(
          id = "contract:${service.nodeId}:${elementKey(contract)}",
          kind = HelidonServicesNodeKind.CONTRACT,
          module = module,
          element = contract,
          name = contract.name ?: contract.qualifiedName ?: "Contract",
          details = "implemented by ${service.psiClass.name ?: service.psiClass.qualifiedName ?: "service"}",
          parentId = service.nodeId,
          ownerClass = contract,
        )
      }

  private fun injectionPointNodes(module: Module,
                                  scope: GlobalSearchScope,
                                  services: List<ServiceInfo>): List<HelidonServicesNode> {
    val injectAnnotation = JavaPsiFacade.getInstance(module.project).findClass(HelidonConstants.SERVICE_INJECT, scope)
                         ?: return emptyList()
    val points = LinkedHashMap<String, InjectionPoint>()
    ReferencesSearch.search(injectAnnotation, scope).forEach(Processor { reference ->
      val annotation = PsiTreeUtil.getParentOfType(reference.element, PsiAnnotation::class.java, false)
      if (annotation?.qualifiedName != HelidonConstants.SERVICE_INJECT) return@Processor true
      if (isGenerated(annotation)) return@Processor true
      collectInjectionPoints(annotation, points)
      true
    })

    val result = ArrayList<HelidonServicesNode>()
    for (point in points.values) {
      val contract = point.contractClass
      val matches = if (contract == null) emptyList() else matchingServices(contract, services, point.name)
      val status = when (matches.size) {
        0 -> HelidonServicesResolutionStatus.UNRESOLVED
        1 -> HelidonServicesResolutionStatus.RESOLVED
        else -> HelidonServicesResolutionStatus.AMBIGUOUS
      }
      val detail = listOfNotNull(point.contractName, point.name?.let { "name: $it" }).joinToString(" | ")
      if (matches.isEmpty()) {
        result.add(injectionNode(module, point, status, detail, parentId = null))
      }
      else {
        for (service in matches) {
          result.add(injectionNode(module, point, status, detail, parentId = service.nodeId))
        }
      }
    }
    return result
  }

  private fun collectInjectionPoints(annotation: PsiAnnotation, result: MutableMap<String, InjectionPoint>) {
    val owner = PsiTreeUtil.getParentOfType(annotation, PsiModifierListOwner::class.java) ?: return
    val name = injectionName(annotation)
    when (owner) {
      is PsiField -> addInjectionPoint(owner.nameIdentifier, owner.type, name, result)
      is PsiParameter -> addInjectionPoint(owner.nameIdentifier ?: owner, owner.type, name, result)
      is PsiMethod -> owner.parameterList.parameters.forEach { parameter ->
        addInjectionPoint(parameter.nameIdentifier ?: parameter, parameter.type, name, result)
      }
    }
  }

  private fun addInjectionPoint(anchor: PsiElement,
                                type: PsiType,
                                name: String?,
                                result: MutableMap<String, InjectionPoint>) {
    if (PsiTypes.voidType() == type) return
    val contractType = unwrapInjectionType(type)
    val contract = (contractType as? PsiClassType)?.resolve()
    val contractName = contract?.qualifiedName ?: contractType.presentableText
    result.putIfAbsent(elementKey(anchor), InjectionPoint(anchor, contract, contractName, name))
  }

  private fun unwrapInjectionType(type: PsiType): PsiType {
    val classType = type as? PsiClassType ?: return type
    val resolved = classType.resolve() ?: return type
    if (resolved.qualifiedName !in SERVICE_INJECTION_WRAPPER_TYPES) return type
    return classType.parameters.firstOrNull()?.let(::unwrapInjectionType) ?: type
  }

  private fun injectionName(annotation: PsiAnnotation): String? =
    constantString(annotation.findDeclaredAttributeValue("value"))
      ?: constantString(annotation.findDeclaredAttributeValue("name"))

  private fun matchingServices(contract: PsiClass, services: List<ServiceInfo>, name: String? = null): List<ServiceInfo> =
    services.filter { service ->
      service.contracts.any { candidate -> contract.manager.areElementsEquivalent(candidate, contract) } &&
      (name == null || service.names.contains(name))
    }

  private fun injectionNode(module: Module,
                            point: InjectionPoint,
                            status: HelidonServicesResolutionStatus,
                            details: String?,
                            parentId: String?): HelidonServicesNode =
    node(
      id = "injection:${parentId.orEmpty()}:${elementKey(point.anchor)}",
      kind = HelidonServicesNodeKind.INJECTION_POINT,
      module = module,
      element = point.anchor,
      name = point.anchor.text,
      details = details,
      status = status,
      parentId = parentId,
      ownerClass = ownerClass(point.anchor),
    )

  private fun serviceLookupNodes(module: Module,
                                 filter: HelidonServicesFilter,
                                 scope: GlobalSearchScope,
                                 services: List<ServiceInfo>): List<HelidonServicesNode> {
    val lookups = LinkedHashMap<String, PsiElement>()
    for (service in services) {
      val usageTargets = if (filter.includeLibraries) {
        HelidonCoreUtils.getHelidonServiceUsageTargets(module, service.psiClass, scope)
      }
      else {
        HelidonCoreUtils.getHelidonServiceUsageTargets(module, service.psiClass)
      }
      usageTargets
        .filter { PsiTreeUtil.getParentOfType(it, PsiClassObjectAccessExpression::class.java, false) != null ||
                  it is PsiClassObjectAccessExpression }
        .filterNot { isGenerated(it) }
        .forEach { lookup -> lookups.putIfAbsent(elementKey(lookup), lookup) }
    }

    val result = ArrayList<HelidonServicesNode>()
    for (lookup in lookups.values) {
      val lookupInfo = serviceLookupInfo(lookup) ?: continue
      val matches = lookupInfo.contractClass?.let { matchingServices(it, services, lookupInfo.name) }.orEmpty()
      val status = when (matches.size) {
        0 -> HelidonServicesResolutionStatus.UNRESOLVED
        1 -> HelidonServicesResolutionStatus.RESOLVED
        else -> HelidonServicesResolutionStatus.AMBIGUOUS
      }
      val parentIds = matches.map { it.nodeId }.ifEmpty { listOf(null) }
      for (parentId in parentIds) {
        result.add(node(
          id = "lookup:${parentId.orEmpty()}:${elementKey(lookup)}",
          kind = HelidonServicesNodeKind.SERVICE_LOOKUP,
          module = module,
          element = lookup,
          name = lookup.text,
          details = lookupInfo.name?.let { "name: $it" },
          status = status,
          parentId = parentId,
          ownerClass = ownerClass(lookup),
        ))
      }
    }
    return result
  }

  private fun collectLangChain4jNodes(module: Module, filter: HelidonServicesFilter): List<HelidonServicesNode> {
    val nodes = ArrayList<HelidonServicesNode>()
    if (filter.kind == null || filter.kind == HelidonServicesNodeKind.LANGCHAIN4J_COMPONENT) {
      for (component in HelidonLangChain4jConfigResolver.components(module, filter.includeTests, filter.includeLibraries)) {
        val componentClass = component.componentTargetClass()
        nodes.add(node(
          id = "langchain4j-component:${module.name}:${component.kind.name}:${component.key}:${elementKey(component.target)}",
          kind = HelidonServicesNodeKind.LANGCHAIN4J_COMPONENT,
          module = module,
          element = component.target,
          name = component.kind.presentableName,
          details = "key: ${component.key}",
          ownerClass = componentClass,
        ))
      }
    }
    if (filter.kind == null || filter.kind == HelidonServicesNodeKind.LANGCHAIN4J_CONFIG) {
      for (entry in HelidonLangChain4jConfigResolver.configEntries(module, filter.includeTests)) {
        nodes.add(node(
          id = "langchain4j-config:${module.name}:${entry.section}:${entry.key}:${elementKey(entry.target)}",
          kind = HelidonServicesNodeKind.LANGCHAIN4J_CONFIG,
          module = module,
          element = entry.target,
          name = entry.key,
          details = "langchain4j.${entry.section}",
          groupName = entry.section,
          groupSortOrder = LANGCHAIN4J_CONFIG_SECTION_ORDER[entry.section] ?: Int.MAX_VALUE,
        ))
      }
    }
    return nodes
  }

  private fun HelidonLangChain4jConfigResolver.LangChain4jComponent.componentTargetClass(): PsiClass? =
    target as? PsiClass ?: PsiTreeUtil.getParentOfType(target, PsiClass::class.java, false)

  private fun node(id: String,
                   kind: HelidonServicesNodeKind,
                   module: Module,
                   element: PsiElement,
                   name: String,
                   details: String? = null,
                   groupName: String? = null,
                   groupSortOrder: Int = Int.MAX_VALUE,
                   status: HelidonServicesResolutionStatus = HelidonServicesResolutionStatus.RESOLVED,
                   parentId: String? = null,
                   packageName: String? = null,
                   ownerClass: PsiClass? = null): HelidonServicesNode {
    val nodeModule = ModuleUtilCore.findModuleForPsiElement(element) ?: module
    return HelidonServicesNode(
      id = id,
      kind = kind,
      moduleName = nodeModule.name,
      sourceSet = sourceSet(nodeModule, element),
      name = name,
      details = details,
      groupName = groupName,
      groupSortOrder = groupSortOrder,
      status = status,
      navigation = SmartPointerManager.getInstance(module.project).createSmartPsiElementPointer(element),
      navigationFile = element.containingFile?.originalFile?.virtualFile,
      navigationOffset = element.textRange.startOffset,
      parentId = parentId,
      packageName = packageName ?: ownerClass?.let(::packageName),
      ownerClassName = ownerClass?.name ?: ownerClass?.qualifiedName,
      ownerClassQualifiedName = ownerClass?.qualifiedName,
    )
  }

  private fun serviceNodeId(module: Module, psiClass: PsiClass): String =
    "service:${module.name}:${psiClass.qualifiedName ?: elementKey(psiClass)}"

  private fun elementKey(element: PsiElement): String {
    val containingFile = element.containingFile?.originalFile
    val virtualFile = containingFile?.virtualFile
    val file = virtualFile?.path ?: containingFile?.name ?: "<unknown>"
    val range = element.textRange
    return "$file:${range.startOffset}:${range.endOffset}"
  }

  private fun isGenerated(element: PsiElement): Boolean {
    val virtualFile = element.containingFile?.originalFile?.virtualFile
    if (virtualFile != null && GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(virtualFile, element.project)) {
      return true
    }
    val containingClass = element as? PsiClass ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
    return containingClass?.modifierList?.findAnnotation(HELIDON_COMMON_GENERATED) != null
  }

  private fun ownerClass(element: PsiElement): PsiClass? =
    element as? PsiClass ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)

  private fun packageName(psiClass: PsiClass): String? {
    val qualifiedName = psiClass.qualifiedName ?: return null
    val name = psiClass.name ?: return null
    return qualifiedName.removeSuffix(".$name").takeIf { it != qualifiedName }
  }

  private fun ServiceInfo.contractNames(): List<String> =
    contracts.mapNotNull { it.qualifiedName ?: it.name }.distinct()

  private fun serviceLookupInfo(anchor: PsiElement): ServiceLookup? {
    val classObjectAccess = anchor as? PsiClassObjectAccessExpression
                            ?: PsiTreeUtil.getParentOfType(anchor, PsiClassObjectAccessExpression::class.java, false)
                            ?: return null
    val contractClass = (classObjectAccess.operand.type as? PsiClassType)?.resolve()
    val expressionList = PsiTreeUtil.getParentOfType(classObjectAccess, PsiExpressionList::class.java) ?: return null
    val methodCall = PsiTreeUtil.getParentOfType(expressionList, PsiMethodCallExpression::class.java) ?: return null
    if (expressionList.parent != methodCall) return null
    val expressions = expressionList.expressions
    val methodName = methodCall.methodExpression.referenceName
    val name = if (methodName?.contains("Named") == true) {
      expressions.getOrNull(1)?.let(::constantString)
    }
    else {
      null
    }
    return ServiceLookup(contractClass, name)
  }

  private fun constantString(value: PsiElement?): String? {
    if (value == null) return null
    if (value is PsiLiteralExpression) {
      return (value.value as? String)?.takeIf { it.isNotBlank() }
    }
    if (value is PsiAnnotationMemberValue) {
      val constant = JavaPsiFacade.getInstance(value.project).constantEvaluationHelper.computeConstantExpression(value)
      return (constant as? String)?.takeIf { it.isNotBlank() }
    }
    val constant = JavaPsiFacade.getInstance(value.project).constantEvaluationHelper.computeConstantExpression(value)
    return (constant as? String)?.takeIf { it.isNotBlank() }
  }

  private data class ServiceInfo(
    val nodeId: String,
    val psiClass: PsiClass,
    val contracts: List<PsiClass>,
    val names: Set<String>,
  )

  private data class InjectionPoint(
    val anchor: PsiElement,
    val contractClass: PsiClass?,
    val contractName: String,
    val name: String?,
  )

  private data class ServiceLookup(
    val contractClass: PsiClass?,
    val name: String?,
  )
}
