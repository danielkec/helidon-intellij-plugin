// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer;
import com.intellij.helidon.HelidonIcons;
import com.intellij.helidon.utils.HelidonBundle;
import com.intellij.helidon.utils.HelidonCommonUtils;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.presentation.java.SymbolPresentationUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.uast.UastSmartPointer;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UExpression;

import javax.swing.*;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class HelidonHttpServiceLineMarkerProvider extends RelatedItemLineMarkerProvider {
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
    if (HelidonCommonUtils.hasHelidonLibrary(module)) {
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
        if (HelidonCommonUtils.isHelidonHttpServiceClass(psiClass)) {
          Set<UExpression> calls =
            getServiceRegisterExpressions(module, JavaPsiFacade.getInstance(module.getProject()).getElementFactory()
              .createType(psiClass));

          Set<PsiElement> targets =
            calls.stream().map(UExpression::getSourcePsi).filter(Objects::nonNull).collect(Collectors.toSet());
          if (!targets.isEmpty()) {
            NavigationGutterIconBuilder<PsiElement> builder =
              NavigationGutterIconBuilder.create(HelidonIcons.HelidonGutter, HelidonBundle.HELIDON_LIBRARY).
                setTargets(targets).
                setPopupTitle(HelidonBundle.message("gutter.choose.service.registration")).
                setTooltipText(HelidonBundle.message("gutter.navigate.to.service.registration")).
                setTargetRenderer(HelidonHttpServiceLineMarkerProvider::getMethodCallRenderer);
            result.add(builder.createLineMarkerInfo(psiElement));
          }
        }
      }
    }
  }

  private static @NotNull Set<UExpression> getServiceRegisterExpressions(@NotNull Module module, @NotNull PsiClassType serviceType) {
    Set<UExpression> expressions = new HashSet<>();
    for (Pair<UastSmartPointer<UExpression>, PsiType> entry : HelidonCommonUtils.getServiceRegisterPathExpressions(module)) {
      if (entry.second.isAssignableFrom(serviceType)) ContainerUtil.addIfNotNull(expressions, entry.first.getElement());
    }
    return expressions;
  }

  @Override
  public @NonNls String getName() {
    return "Helidon HTTP Services";
  }

  @Override
  public String getId() {
    return "HelidonHttpServiceLineMarkerProvider";
  }

  @Override
  public Icon getIcon() {
    return HelidonIcons.Helidon;
  }
}
