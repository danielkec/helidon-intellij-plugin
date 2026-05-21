// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.services

import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.constants.HelidonConstants
import com.intellij.helidon.langchain4j.HelidonLangChain4jConfigResolver
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
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
  val status: HelidonServicesResolutionStatus = HelidonServicesResolutionStatus.RESOLVED,
  val navigation: SmartPsiElementPointer<PsiElement>? = null,
  val parentId: String? = null,
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

  fun collect(project: Project, filter: HelidonServicesFilter = HelidonServicesFilter()): HelidonServicesSnapshot {
    val modules = ModuleManager.getInstance(project).modules
      .asSequence()
      .filter { !it.isDisposed }
      .filter { filter.moduleName == null || it.name == filter.moduleName }
      .filter { HelidonCoreUtils.hasHelidonLibrary(it) }
      .sortedBy { it.name }
      .toList()
    if (modules.isEmpty()) {
      return HelidonServicesSnapshot(emptyList(), emptyList())
    }

    val nodes = ArrayList<HelidonServicesNode>()
    for (module in modules) {
      nodes.addAll(collectServiceRegistryNodes(module, filter))
      nodes.addAll(collectLangChain4jNodes(module, filter))
      for (contributor in HelidonServicesViewContributor.EP_NAME.extensionList) {
        nodes.addAll(contributor.collect(module, filter))
      }
    }

    return HelidonServicesSnapshot(
      modules = modules.map { it.name },
      nodes = nodes
        .asSequence()
        .filter { accepts(it, filter) }
        .distinctBy { it.id }
        .sortedWith(compareBy<HelidonServicesNode> { it.moduleName }
                      .thenBy { it.kind.ordinal }
                      .thenBy { it.name.lowercase(Locale.ENGLISH) }
                      .thenBy { it.details.orEmpty() })
        .toList(),
    )
  }

  fun searchScope(module: Module, filter: HelidonServicesFilter): GlobalSearchScope =
    module.getModuleWithDependenciesAndLibrariesScope(filter.includeTests)

  fun sourceSet(module: Module, element: PsiElement): HelidonServicesSourceSet {
    val virtualFile = element.containingFile?.originalFile?.virtualFile ?: return HelidonServicesSourceSet.MAIN
    val fileIndex = ModuleRootManager.getInstance(module).fileIndex
    return when {
      fileIndex.isInTestSourceContent(virtualFile) -> HelidonServicesSourceSet.TEST
      !fileIndex.isInContent(virtualFile) -> HelidonServicesSourceSet.LIBRARY
      else -> HelidonServicesSourceSet.MAIN
    }
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

  private fun collectServiceRegistryNodes(module: Module, filter: HelidonServicesFilter): List<HelidonServicesNode> {
    val scope = searchScope(module, filter)
    val services = collectServiceClasses(module, scope)
    if (services.isEmpty()) return emptyList()

    val serviceInfos = services.map { psiClass ->
      val contracts = HelidonCoreUtils.getHelidonServiceContracts(psiClass).toList()
      ServiceInfo(serviceNodeId(module, psiClass), psiClass, contracts)
    }
    val nodes = ArrayList<HelidonServicesNode>()

    for (service in serviceInfos) {
      nodes.add(serviceNode(module, service))
      nodes.addAll(contractNodes(module, service))
    }
    nodes.addAll(injectionPointNodes(module, scope, serviceInfos))
    nodes.addAll(serviceLookupNodes(module, serviceInfos))
    return nodes
  }

  private fun collectServiceClasses(module: Module, scope: GlobalSearchScope): List<PsiClass> {
    val facade = JavaPsiFacade.getInstance(module.project)
    val services = LinkedHashSet<PsiClass>()
    for (annotationName in SERVICE_SCOPE_ANNOTATIONS) {
      val annotationClass = facade.findClass(annotationName, scope) ?: continue
      AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).forEach { psiClass ->
        if (!isGenerated(psiClass) && HelidonCoreUtils.isHelidonServiceRegistryClass(psiClass)) {
          services.add(psiClass)
        }
      }
    }
    return services.toList()
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
      name = qualifiedName ?: psiClass.name ?: "Service",
      details = details,
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
          name = contract.qualifiedName ?: contract.name ?: "Contract",
          details = "implemented by ${service.psiClass.name ?: service.psiClass.qualifiedName ?: "service"}",
          parentId = service.nodeId,
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
      val matches = if (contract == null) emptyList() else matchingServices(contract, services)
      val status = when (matches.size) {
        0 -> HelidonServicesResolutionStatus.UNRESOLVED
        1 -> HelidonServicesResolutionStatus.RESOLVED
        else -> HelidonServicesResolutionStatus.AMBIGUOUS
      }
      val detail = point.contractName
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
    when (owner) {
      is PsiField -> addInjectionPoint(owner.nameIdentifier, owner.type, result)
      is PsiParameter -> addInjectionPoint(owner.nameIdentifier ?: owner, owner.type, result)
      is PsiMethod -> owner.parameterList.parameters.forEach { parameter ->
        addInjectionPoint(parameter.nameIdentifier ?: parameter, parameter.type, result)
      }
    }
  }

  private fun addInjectionPoint(anchor: PsiElement, type: PsiType, result: MutableMap<String, InjectionPoint>) {
    if (PsiTypes.voidType() == type) return
    val contract = (type as? PsiClassType)?.resolve()
    val contractName = contract?.qualifiedName ?: type.presentableText
    result.putIfAbsent(elementKey(anchor), InjectionPoint(anchor, contract, contractName))
  }

  private fun matchingServices(contract: PsiClass, services: List<ServiceInfo>): List<ServiceInfo> =
    services.filter { service ->
      service.contracts.any { candidate -> contract.manager.areElementsEquivalent(candidate, contract) }
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
    )

  private fun serviceLookupNodes(module: Module, services: List<ServiceInfo>): List<HelidonServicesNode> {
    val lookupsByService = services.associateWith { service ->
      HelidonCoreUtils.getHelidonServiceUsageTargets(module, service.psiClass)
        .filter { PsiTreeUtil.getParentOfType(it, PsiClassObjectAccessExpression::class.java, false) != null ||
                  it is PsiClassObjectAccessExpression }
        .filterNot { isGenerated(it) }
    }
    val lookupCounts = lookupsByService.values.flatten().groupingBy(::elementKey).eachCount()
    return lookupsByService.flatMap { (service, lookups) ->
      lookups.map { lookup ->
        val status = if ((lookupCounts[elementKey(lookup)] ?: 0) > 1) {
          HelidonServicesResolutionStatus.AMBIGUOUS
        }
        else {
          HelidonServicesResolutionStatus.RESOLVED
        }
        node(
          id = "lookup:${service.nodeId}:${elementKey(lookup)}",
          kind = HelidonServicesNodeKind.SERVICE_LOOKUP,
          module = module,
          element = lookup,
          name = lookup.text,
          status = status,
          parentId = service.nodeId,
        )
      }
    }
  }

  private fun collectLangChain4jNodes(module: Module, filter: HelidonServicesFilter): List<HelidonServicesNode> {
    val nodes = ArrayList<HelidonServicesNode>()
    for (component in HelidonLangChain4jConfigResolver.components(module, filter.includeTests)) {
      nodes.add(node(
        id = "langchain4j-component:${module.name}:${component.kind.name}:${component.key}:${elementKey(component.target)}",
        kind = HelidonServicesNodeKind.LANGCHAIN4J_COMPONENT,
        module = module,
        element = component.target,
        name = component.componentTargetClassName() ?: component.key,
        details = "${component.kind.presentableName} | key: ${component.key}",
      ))
    }
    for (entry in HelidonLangChain4jConfigResolver.configEntries(module, filter.includeTests)) {
      nodes.add(node(
        id = "langchain4j-config:${module.name}:${entry.section}:${entry.key}:${elementKey(entry.target)}",
        kind = HelidonServicesNodeKind.LANGCHAIN4J_CONFIG,
        module = module,
        element = entry.target,
        name = entry.key,
        details = "langchain4j.${entry.section}",
      ))
    }
    return nodes
  }

  private fun HelidonLangChain4jConfigResolver.LangChain4jComponent.componentTargetClass(): PsiClass? =
    target as? PsiClass ?: PsiTreeUtil.getParentOfType(target, PsiClass::class.java, false)

  private fun HelidonLangChain4jConfigResolver.LangChain4jComponent.componentTargetClassName(): String? =
    componentTargetClass()?.qualifiedName ?: componentTargetClass()?.name

  private fun node(id: String,
                   kind: HelidonServicesNodeKind,
                   module: Module,
                   element: PsiElement,
                   name: String,
                   details: String? = null,
                   status: HelidonServicesResolutionStatus = HelidonServicesResolutionStatus.RESOLVED,
                   parentId: String? = null): HelidonServicesNode =
    HelidonServicesNode(
      id = id,
      kind = kind,
      moduleName = module.name,
      sourceSet = sourceSet(module, element),
      name = name,
      details = details,
      status = status,
      navigation = SmartPointerManager.getInstance(module.project).createSmartPsiElementPointer(element),
      parentId = parentId,
    )

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

  private fun ServiceInfo.contractNames(): List<String> =
    contracts.mapNotNull { it.qualifiedName ?: it.name }.distinct()

  private data class ServiceInfo(
    val nodeId: String,
    val psiClass: PsiClass,
    val contracts: List<PsiClass>,
  )

  private data class InjectionPoint(
    val anchor: PsiElement,
    val contractClass: PsiClass?,
    val contractName: String,
  )
}
