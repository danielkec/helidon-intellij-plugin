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
    if (module == null || !HelidonCoreUtils.hasHelidonLibrary(module)) {
      return null
    }

    val method = testMethod(element)
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

  private fun testMethod(element: PsiElement): PsiMethod? {
    val method = JUnitUtil.getTestMethod(element, false, true)
      ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
    return method?.takeIf(::isTestMethod)
  }

  private fun testClass(element: PsiElement): PsiClass? =
    JUnitUtil.getTestClass(element) ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)

  private fun isTestClass(testClass: PsiClass): Boolean =
    JUnitUtil.isTestClass(testClass) || testClass.methods.any(::isTestMethod)

  private fun isTestMethod(method: PsiMethod): Boolean =
    JUnitUtil.isTestAnnotated(method) ||
      method.modifierList.annotations.any { it.qualifiedName in JUNIT_TEST_ANNOTATIONS }

  private fun mavenProject(module: Module): MavenProject? =
    MavenProjectsManager.getInstanceIfCreated(module.project)
      ?.takeIf { it.isMavenizedModule(module) }
      ?.findProject(module)

  private val JUNIT_TEST_ANNOTATIONS = setOf(
    JUnitUtil.TEST_ANNOTATION,
    JUnitUtil.TEST5_ANNOTATION,
  )
}
