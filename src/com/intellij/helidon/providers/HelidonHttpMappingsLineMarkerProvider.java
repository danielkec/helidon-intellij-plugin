// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.helidon.utils.HelidonBundle;
import com.intellij.helidon.utils.HelidonCommonUtils;
import com.intellij.helidon.utils.HelidonCommonUtils.RestServerEndpointTarget;
import com.intellij.helidon.utils.HelidonUrlTargetInfo;
import com.intellij.java.ultimate.icons.JavaUltimateIcons;
import com.intellij.microservices.endpoints.EndpointsViewOpener;
import com.intellij.navigation.GotoRelatedItem;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.module.Module;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class HelidonHttpMappingsLineMarkerProvider extends RelatedItemLineMarkerProvider {
  private static final Icon HTTP_MAPPING_ICON = JavaUltimateIcons.Web.Gutter.RequestMapping;

  @Override
  public void collectNavigationMarkers(@NotNull List<? extends PsiElement> elements,
                                       @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result,
                                       boolean forNavigation) {
    PsiElement psiElement = ContainerUtil.getFirstItem(elements);
    if (psiElement == null) return;

    if (!HelidonCommonUtils.hasHelidonLibrary(psiElement.getProject())) return;

    for (PsiElement element : elements) {
      collectNavigationMarker(element, result);
    }
  }

  private static void collectNavigationMarker(@NotNull PsiElement element,
                                              @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {
    PsiAnnotation annotation = getHttpMethodAnnotation(element);
    if (annotation == null) return;

    Collection<RestServerEndpointTarget> targets = HelidonCommonUtils.getRestServerEndpointTargets(annotation);
    if (targets.isEmpty()) return;

    String tooltip = HelidonBundle.message("gutter.navigate.to.http.mapping");
    result.add(new RelatedItemLineMarkerInfo<PsiElement>(element,
                                                         element.getTextRange(),
                                                         HTTP_MAPPING_ICON,
                                                         ignored -> tooltip,
                                                         createNavigationHandler(targets),
                                                         GutterIconRenderer.Alignment.LEFT,
                                                         () -> createRelatedItems(targets)));
  }

  private static @Nullable PsiAnnotation getHttpMethodAnnotation(@NotNull PsiElement element) {
    if (!(element instanceof PsiIdentifier)) return null;

    PsiAnnotation annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation.class, false);
    PsiJavaCodeReferenceElement nameReference = annotation == null ? null : annotation.getNameReferenceElement();
    return nameReference != null && nameReference.getReferenceNameElement() == element ? annotation : null;
  }

  private static @NotNull GutterIconNavigationHandler<PsiElement> createNavigationHandler(@NotNull Collection<RestServerEndpointTarget> targets) {
    String moduleName = getNavigationModuleName(targets);
    String searchText = getEndpointSearchText(targets);
    return (MouseEvent event, PsiElement element) ->
      EndpointsViewOpener.showEndpointsWithFilter(element.getProject(), moduleName, HelidonBundle.HELIDON_LIBRARY, searchText);
  }

  private static @NotNull Collection<GotoRelatedItem> createRelatedItems(@NotNull Collection<RestServerEndpointTarget> targets) {
    return targets.stream()
      .map(RestServerEndpointTarget::getEndpoint)
      .map(HelidonUrlTargetInfo::resolveToPsiElement)
      .filter(Objects::nonNull)
      .map(element -> new GotoRelatedItem(element, HelidonBundle.HELIDON_LIBRARY))
      .collect(Collectors.toList());
  }

  private static @Nullable String getNavigationModuleName(@NotNull Collection<RestServerEndpointTarget> targets) {
    Set<String> moduleNames = targets.stream()
      .map(RestServerEndpointTarget::getModule)
      .filter(Objects::nonNull)
      .map(Module::getName)
      .collect(Collectors.toCollection(LinkedHashSet::new));
    return moduleNames.size() == 1 ? moduleNames.iterator().next() : null;
  }

  static @NotNull String getEndpointSearchText(@NotNull Collection<RestServerEndpointTarget> targets) {
    if (targets.size() == 1) {
      return getEndpointSearchText(targets.iterator().next().getEndpoint());
    }

    Set<String> methods = targets.stream()
      .map(RestServerEndpointTarget::getEndpoint)
      .map(HelidonUrlTargetInfo::getMethods)
      .flatMap(Collection::stream)
      .collect(Collectors.toCollection(LinkedHashSet::new));
    return getHttpMethodSearchText(methods);
  }

  private static @NotNull String getEndpointSearchText(@NotNull HelidonUrlTargetInfo endpoint) {
    String methods = getHttpMethodSearchText(endpoint.getMethods());
    if (methods.isEmpty()) {
      return endpoint.getPresentationPath();
    }
    return methods + " " + endpoint.getPresentationPath();
  }

  private static @NotNull String getHttpMethodSearchText(@NotNull Collection<String> methods) {
    return StringUtil.join(methods, method -> "http-method: " + method, " ");
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
