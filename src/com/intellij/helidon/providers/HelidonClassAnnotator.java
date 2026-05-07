// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer;
import com.intellij.helidon.HelidonIcons;
import com.intellij.helidon.utils.HelidonBundle;
import com.intellij.helidon.utils.HelidonCoreUtils;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.psi.*;
import com.intellij.psi.presentation.java.SymbolPresentationUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.*;

public final class HelidonClassAnnotator extends RelatedItemLineMarkerProvider {

  private static PsiTargetPresentationRenderer<PsiElement> getMethodCallRenderer() {
    return new PsiTargetPresentationRenderer<>() {
      @Override
      protected Icon getIcon(@NotNull PsiElement element) {
        return HelidonIcons.HelidonGutter;
      }

      @Override
      public String getContainerText(@NotNull PsiElement element) {
        PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
        if (psiClass != null) {
          return SymbolPresentationUtil.getSymbolPresentableText(psiClass);
        }
        return SymbolPresentationUtil.getSymbolContainerText(element);
      }
    };
  }

  @Override
  public void collectNavigationMarkers(@NotNull List<? extends PsiElement> elements,
                                       @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result,
                                       boolean forNavigation) {
    final PsiElement psiElement = ContainerUtil.getFirstItem(elements);
    if (psiElement == null) return;
    Module module = ModuleUtilCore.findModuleForPsiElement(psiElement);
    if (module == null) return;
    if (HelidonCoreUtils.hasHelidonLibrary(module)) {
      for (PsiElement element : elements) {
        collectNavigationMarkers(element, module, result);
      }
    }
  }

  private static void collectNavigationMarkers(@NotNull PsiElement psiElement,
                                               @NotNull Module module,
                                               @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {
    if (psiElement instanceof PsiIdentifier) {
      final PsiElement parent = psiElement.getParent();
      if (parent instanceof PsiClass) {
        PsiClass psiClass = (PsiClass)parent;
        if (HelidonCoreUtils.isHelidonServiceRegistryClass(psiClass)) {
          Set<PsiElement> targets = HelidonCoreUtils.getHelidonServiceUsageTargets(module, psiClass);
          if (targets.isEmpty()) {
            targets = Collections.singleton(psiElement);
          }
          NavigationGutterIconBuilder<PsiElement> builder =
            NavigationGutterIconBuilder.create(HelidonIcons.HelidonBeanGutter, HelidonBundle.HELIDON_LIBRARY).
              setTargets(targets).
              setPopupTitle(HelidonBundle.message("gutter.choose.service.usage")).
              setTooltipText(HelidonBundle.message("gutter.navigate.to.service.usage")).
              setTargetRenderer(HelidonClassAnnotator::getMethodCallRenderer);
          result.add(builder.createLineMarkerInfo(psiElement));
        }
      }
    }
  }

  @Override
  public @NonNls String getName() {
    return "Helidon Declarative Services";
  }

  @Override
  public String getId() {
    return "HelidonClassAnnotator";
  }

  @Override
  public Icon getIcon() {
    return HelidonIcons.Helidon;
  }
}
