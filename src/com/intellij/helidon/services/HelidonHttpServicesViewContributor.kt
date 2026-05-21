// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.services

import com.intellij.helidon.providers.HelidonRequestMethods
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.helidon.utils.HelidonUrlTargetInfo
import com.intellij.openapi.module.Module
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiClass
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.CommonProcessors.CollectProcessor

class HelidonHttpServicesViewContributor : HelidonServicesViewContributor {
  override fun collect(module: Module, filter: HelidonServicesFilter): List<HelidonServicesNode> {
    val scope = HelidonServicesModel.searchScope(module, filter)
    val endpoints = CollectProcessor<HelidonUrlTargetInfo>()
    val routingScope = HelidonCommonUtils.getRoutingClassReferencesScope(module).intersectWith(scope)

    HelidonCommonUtils.processBuilderRegisterMethods(endpoints, routingScope, module)
    HelidonCommonUtils.processBuilderHttpMethods(endpoints, routingScope, module)
    HelidonCommonUtils.processRestServerEndpointMethods(endpoints, scope, module)

    return endpoints.results
      .mapNotNull { endpoint -> endpointNode(module, endpoint) }
      .filter { filter.includeTests || it.sourceSet != HelidonServicesSourceSet.TEST }
      .filter { filter.includeLibraries || it.sourceSet != HelidonServicesSourceSet.LIBRARY }
  }

  private fun endpointNode(module: Module, endpoint: HelidonUrlTargetInfo): HelidonServicesNode? {
    val target = endpoint.resolveToPsiElement() ?: return null
    val path = endpoint.presentationPath.let { if (it.startsWith("/")) it else "/$it" }
    val methods = endpoint.methods.takeIf { it.isNotEmpty() }?.joinToString(", ")
                  ?: endpoint.type.takeIf { it != HelidonRequestMethods.UNKNOWN }?.name
    val container = PsiTreeUtil.getParentOfType(target, PsiClass::class.java)
    val details = methods
    return HelidonServicesNode(
      id = "http:${module.name}:$path:${elementKey(target)}",
      kind = HelidonServicesNodeKind.HTTP_ENDPOINT,
      moduleName = module.name,
      sourceSet = HelidonServicesModel.sourceSet(module, target),
      name = path,
      details = details,
      navigation = SmartPointerManager.getInstance(module.project).createSmartPsiElementPointer(target),
      navigationFile = target.containingFile?.originalFile?.virtualFile,
      navigationOffset = target.textRange.startOffset,
      packageName = container?.let(::packageName),
      ownerClassName = container?.name ?: container?.qualifiedName,
      ownerClassQualifiedName = container?.qualifiedName,
    )
  }

  private fun packageName(psiClass: PsiClass): String? {
    val qualifiedName = psiClass.qualifiedName ?: return null
    val name = psiClass.name ?: return null
    return qualifiedName.removeSuffix(".$name").takeIf { it != qualifiedName }
  }

  private fun elementKey(element: PsiElement): String {
    val file = element.containingFile?.originalFile?.virtualFile?.path ?: element.containingFile?.name ?: "<unknown>"
    val range = element.textRange
    return "$file:${range.startOffset}:${range.endOffset}"
  }
}
