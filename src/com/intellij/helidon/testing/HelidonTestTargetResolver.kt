// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.testing

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.junit.JUnitUtil
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager

internal object HelidonTestTargetResolver {
  fun resolve(context: ConfigurationContext): HelidonMavenTestTarget? {
    val element = context.psiLocation ?: return null
    val module = context.module ?: ModuleUtilCore.findModuleForPsiElement(element)
    return resolve(element, module, requireMaven = true)
  }

  fun resolve(element: PsiElement, module: Module?, requireMaven: Boolean): HelidonMavenTestTarget? {
    if (module == null || !hasHelidonTestLibrary(module)) {
      return null
    }

    val method = methodAt(element)
    if (method != null && !isTestMethod(method)) {
      return null
    }
    val testClass = method?.containingClass ?: testClass(element) ?: return null
    val qualifiedName = testClass.qualifiedName ?: return null
    val virtualFile = testClass.containingFile?.virtualFile ?: return null

    if (!isTestClass(testClass) ||
        !ModuleRootManager.getInstance(module).fileIndex.isInTestSourceContent(virtualFile)) {
      return null
    }

    val mavenProject = if (requireMaven) {
      mavenProject(module) ?: return null
    }
    else {
      null
    }
    val workingDirectory = mavenProject?.directory ?: module.project.basePath ?: ""

    return HelidonMavenTestTarget(
      module = module,
      className = qualifiedName,
      methodName = method?.name,
      workingDirectory = workingDirectory,
      pomFile = mavenProject?.file,
      sourceElement = method?.nameIdentifier ?: testClass.nameIdentifier ?: testClass,
    )
  }

  private fun methodAt(element: PsiElement): PsiMethod? =
    JUnitUtil.getTestMethod(element, false, true)
      ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)

  private fun testClass(element: PsiElement): PsiClass? =
    JUnitUtil.getTestClass(element) ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)

  private fun hasHelidonTestLibrary(module: Module): Boolean =
    HelidonCoreUtils.hasHelidonLibrary(module) || HelidonCoreUtils.hasHelidonMPLibrary(module)

  private fun isTestClass(testClass: PsiClass): Boolean =
    testClass.methods.any(::isTestMethod) ||
      (JUnitUtil.isTestClass(testClass) && testClass.methods.none(::isJunit4TestMethod))

  private fun isTestMethod(method: PsiMethod): Boolean {
    if (isJunit4TestMethod(method) &&
        (!method.hasModifierProperty(PsiModifier.PUBLIC) ||
          method.containingClass?.hasModifierProperty(PsiModifier.PUBLIC) != true)) {
      return false
    }
    return JUnitUtil.isTestAnnotated(method) ||
      method.modifierList.annotations.any { it.qualifiedName == JUnitUtil.TEST5_ANNOTATION }
  }

  private fun isJunit4TestMethod(method: PsiMethod): Boolean =
    method.modifierList.annotations.any { it.qualifiedName == JUnitUtil.TEST_ANNOTATION }

  private fun mavenProject(module: Module): MavenProject? =
    MavenProjectsManager.getInstanceIfCreated(module.project)
      ?.takeIf { it.isMavenizedModule(module) }
      ?.findProject(module)

}
