// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.config.yaml.isInsideApplicationYamlFile
import com.intellij.helidon.utils.HelidonBundle
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.annotations.NonNls
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import javax.swing.Icon

internal class HelidonLangChain4jYamlLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun collectNavigationMarkers(elements: List<PsiElement>,
                                        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
                                        forNavigation: Boolean) {
    val psiElement = ContainerUtil.getFirstItem(elements) ?: return
    val module = ModuleUtilCore.findModuleForPsiElement(psiElement) ?: return
    if (!HelidonCoreUtils.hasHelidonLibrary(module)) return
    if (!isInsideApplicationYamlFile(psiElement)) return

    val processed = HashSet<PsiElement>()
    for (element in elements) {
      val target = langChain4jTarget(element) ?: continue
      if (!processed.add(target.anchor)) continue

      val builder = NavigationGutterIconBuilder.create(gutterIcon(target), HelidonBundle.HELIDON_LIBRARY)
        .setTargets(target.targets)
        .setPopupTitle(HelidonBundle.message("gutter.choose.langchain4j.target"))
        .setTooltipText(HelidonBundle.message("gutter.navigate.to.langchain4j.target"))
      result.add(builder.createLineMarkerInfo(target.anchor))
    }
  }

  private fun gutterIcon(target: HelidonLangChain4jConfigResolver.MarkerTargets): Icon {
    return when (target.gutterKind) {
      HelidonLangChain4jConfigResolver.GutterKind.AI -> HelidonIcons.AiGutter
      HelidonLangChain4jConfigResolver.GutterKind.ROBOT -> HelidonIcons.RobotGutter
      HelidonLangChain4jConfigResolver.GutterKind.DEFAULT -> HelidonIcons.HelidonGutter
    }
  }

  private fun langChain4jTarget(element: PsiElement): HelidonLangChain4jConfigResolver.MarkerTargets? {
    if (element is YAMLKeyValue || element is YAMLScalar) {
      return HelidonLangChain4jConfigResolver.markerTargets(element)
    }

    if (element.node.elementType == YAMLTokenTypes.SCALAR_KEY) {
      return PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)
        ?.let { HelidonLangChain4jConfigResolver.markerTargets(it) }
    }

    val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false)
    return scalar?.let { HelidonLangChain4jConfigResolver.markerTargets(it) }
  }

  override fun getName(): @NonNls String = "Helidon LangChain4j Config"

  override fun getId(): String = "HelidonLangChain4jYamlLineMarkerProvider"

  override fun getIcon(): Icon = HelidonIcons.Helidon
}
