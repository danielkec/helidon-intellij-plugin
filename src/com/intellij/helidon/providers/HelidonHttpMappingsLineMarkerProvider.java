// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.helidon.utils.HelidonBundle;
import com.intellij.helidon.utils.HelidonCommonUtils;
import com.intellij.helidon.utils.HelidonUrlTargetInfo;
import com.intellij.java.ultimate.icons.JavaUltimateIcons;
import com.intellij.microservices.endpoints.EndpointsViewOpener;
import com.intellij.navigation.GotoRelatedItem;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class HelidonHttpMappingsLineMarkerProvider extends RelatedItemLineMarkerProvider {
  private static final Icon HTTP_MAPPING_ICON = JavaUltimateIcons.Web.Gutter.RequestMapping;

  @Override
  public void collectNavigationMarkers(@NotNull List<? extends PsiElement> elements,
                                       @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result,
                                       boolean forNavigation) {
    PsiElement psiElement = ContainerUtil.getFirstItem(elements);
    if (psiElement == null) return;

    Module module = ModuleUtilCore.findModuleForPsiElement(psiElement);
    if (!HelidonCommonUtils.hasHelidonLibrary(module)) return;

    for (PsiElement element : elements) {
      collectNavigationMarkers(element, module, result);
    }
  }

  private static void collectNavigationMarkers(@NotNull PsiElement element,
                                               @NotNull Module module,
                                               @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {
    PsiAnnotation annotation = getHttpMethodAnnotation(element);
    if (annotation == null) return;

    Collection<HelidonUrlTargetInfo> endpoints = HelidonCommonUtils.getRestServerEndpointTargets(annotation, module);
    if (endpoints.isEmpty()) return;

    String tooltip = HelidonBundle.message("gutter.navigate.to.http.mapping");
    result.add(new RelatedItemLineMarkerInfo<PsiElement>(element,
                                                         element.getTextRange(),
                                                         HTTP_MAPPING_ICON,
                                                         ignored -> tooltip,
                                                         createNavigationHandler(module, endpoints),
                                                         GutterIconRenderer.Alignment.LEFT,
                                                         () -> createRelatedItems(endpoints)));
  }

  private static @Nullable PsiAnnotation getHttpMethodAnnotation(@NotNull PsiElement element) {
    if (!(element instanceof PsiIdentifier)) return null;

    PsiAnnotation annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation.class, false);
    PsiJavaCodeReferenceElement nameReference = annotation == null ? null : annotation.getNameReferenceElement();
    return nameReference != null && nameReference.getReferenceNameElement() == element ? annotation : null;
  }

  private static @NotNull GutterIconNavigationHandler<PsiElement> createNavigationHandler(@NotNull Module module,
                                                                                          @NotNull Collection<HelidonUrlTargetInfo> endpoints) {
    String searchText = endpoints.stream()
      .findFirst()
      .map(HelidonHttpMappingsLineMarkerProvider::getEndpointSearchText)
      .orElse("");
    return (MouseEvent event, PsiElement element) ->
      EndpointsViewOpener.showEndpointsWithFilter(element.getProject(), module.getName(), HelidonBundle.HELIDON_LIBRARY, searchText);
  }

  private static @NotNull Collection<GotoRelatedItem> createRelatedItems(@NotNull Collection<HelidonUrlTargetInfo> endpoints) {
    return endpoints.stream()
      .map(HelidonUrlTargetInfo::resolveToPsiElement)
      .filter(Objects::nonNull)
      .map(element -> new GotoRelatedItem(element, HelidonBundle.HELIDON_LIBRARY))
      .collect(Collectors.toList());
  }

  private static @NotNull String getEndpointSearchText(@NotNull HelidonUrlTargetInfo endpoint) {
    String methods = StringUtil.join(endpoint.getMethods(), " ");
    if (methods.isEmpty()) {
      return endpoint.getPresentationPath();
    }
    return methods + " " + endpoint.getPresentationPath();
  }

  @Override
  public @NonNls String getName() {
    return "Helidon HTTP Mappings";
  }

  @Override
  public String getId() {
    return "HelidonHttpMappingsLineMarkerProvider";
  }

  @Override
  public Icon getIcon() {
    return HTTP_MAPPING_ICON;
  }
}
