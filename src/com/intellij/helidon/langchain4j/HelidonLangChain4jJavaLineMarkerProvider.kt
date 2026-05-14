// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.langchain4j

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.utils.HelidonBundle
import com.intellij.helidon.utils.HelidonCoreUtils
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.annotations.NonNls
import javax.swing.Icon

internal class HelidonLangChain4jJavaLineMarkerProvider : RelatedItemLineMarkerProvider() {
  override fun collectNavigationMarkers(elements: List<PsiElement>,
                                        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
                                        forNavigation: Boolean) {
    val psiElement = ContainerUtil.getFirstItem(elements) ?: return
    val module = ModuleUtilCore.findModuleForPsiElement(psiElement) ?: return
    if (!HelidonCoreUtils.hasHelidonLibrary(module)) return

    val processed = HashSet<PsiElement>()
    for (element in elements) {
      val target = HelidonLangChain4jConfigResolver.annotationMarkerTargets(element) ?: continue
      if (!processed.add(target.anchor)) continue

      val builder = NavigationGutterIconBuilder.create(HelidonIcons.AiGutter, HelidonBundle.HELIDON_LIBRARY)
        .setTargets(target.targets)
        .setPopupTitle(HelidonBundle.message("gutter.choose.langchain4j.target"))
        .setTooltipText(HelidonBundle.message("gutter.navigate.to.langchain4j.target"))
      result.add(builder.createLineMarkerInfo(target.anchor))
    }
  }

  override fun getName(): @NonNls String = "Helidon LangChain4j Java References"

  override fun getId(): String = "HelidonLangChain4jJavaLineMarkerProvider"

  override fun getIcon(): Icon = HelidonIcons.Helidon
}
