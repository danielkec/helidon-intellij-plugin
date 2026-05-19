// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers;

import com.intellij.helidon.utils.HelidonBundle;
import com.intellij.helidon.utils.HelidonCommonUtils;
import com.intellij.helidon.utils.HelidonUrlTargetInfo;
import com.intellij.java.library.JavaLibraryModificationTracker;
import com.intellij.ide.presentation.Presentation;
import com.intellij.microservices.url.HttpUrlResolver;
import com.intellij.microservices.url.UrlResolveRequest;
import com.intellij.microservices.url.UrlTargetInfo;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider.Result;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.uast.UastModificationTracker;
import com.intellij.util.CommonProcessors.CollectProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

@Presentation(typeName = HelidonBundle.HELIDON_LIBRARY, icon = "com.intellij.helidon.HelidonIcons.Helidon")
public final class HelidonUrlResolver extends HttpUrlResolver {
  private static final Key<CachedValue<Iterable<UrlTargetInfo>>> ENDPOINT_VARIANTS_KEY =
    Key.create("HELIDON_ENDPOINT_VARIANTS_KEY");
  private final @NotNull Project myProject;
  private final @Nullable Iterable<UrlTargetInfo> myVariants;

  public HelidonUrlResolver(@NotNull Project project) {
    this(project, null);
  }

  HelidonUrlResolver(@NotNull Project project, @Nullable Iterable<UrlTargetInfo> variants) {
    myProject = project;
    myVariants = variants;
  }

  @Override
  public @NotNull Iterable<UrlTargetInfo> resolve(@NotNull UrlResolveRequest request) {
    ArrayList<UrlTargetInfo> result = new ArrayList<>();
    for (UrlTargetInfo targetInfo : getVariants()) {
      if (matches(request, targetInfo)) {
        result.add(targetInfo);
      }
    }
    return result;
  }

  private static boolean matches(@NotNull UrlResolveRequest request, @NotNull UrlTargetInfo targetInfo) {
    return matchesScheme(request, targetInfo) &&
           matchesMethod(request, targetInfo) &&
           matchesPath(request, targetInfo);
  }

  private static boolean matchesPath(@NotNull UrlResolveRequest request, @NotNull UrlTargetInfo targetInfo) {
    if (targetInfo instanceof HelidonUrlTargetInfo) {
      return ((HelidonUrlTargetInfo)targetInfo).matchesPath(request.getPath());
    }
    return targetInfo.getPath().isCompatibleWith(request.getPath());
  }

  private static boolean matchesScheme(@NotNull UrlResolveRequest request, @NotNull UrlTargetInfo targetInfo) {
    String schemeHint = request.getSchemeHint();
    if (schemeHint == null || schemeHint.isEmpty()) return true;

    String normalizedScheme = schemeHint.toLowerCase(Locale.ENGLISH);
    if (!normalizedScheme.endsWith("://")) {
      normalizedScheme += "://";
    }
    return targetInfo.getSchemes().contains(normalizedScheme);
  }

  private static boolean matchesMethod(@NotNull UrlResolveRequest request, @NotNull UrlTargetInfo targetInfo) {
    String method = request.getMethod();
    if (method == null || method.isEmpty()) return true;

    Set<String> methods = targetInfo.getMethods();
    return methods.isEmpty() || methods.contains(method.toUpperCase(Locale.ENGLISH));
  }

  @Override
  public @NotNull Iterable<UrlTargetInfo> getVariants() {
    if (myVariants != null) return myVariants;

    Project project = myProject;
    return CachedValuesManager.getManager(project).getCachedValue(project, ENDPOINT_VARIANTS_KEY, () -> {
      return Result.create(collectVariants(project),
                           UastModificationTracker.getInstance(project),
                           JavaLibraryModificationTracker.getInstance(project),
                           ProjectRootModificationTracker.getInstance(project));
    }, false);
  }

  private static @NotNull Iterable<UrlTargetInfo> collectVariants(@NotNull Project project) {
    CollectProcessor<HelidonUrlTargetInfo> collectProcessor = new CollectProcessor<>();
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      if (!HelidonCommonUtils.hasHelidonLibrary(module)) continue;

      GlobalSearchScope scope = HelidonCommonUtils.getRoutingClassReferencesScope(module);
      if (!HelidonCommonUtils.processBuilderRegisterMethods(collectProcessor, scope, module)) break;
      if (!HelidonCommonUtils.processBuilderHttpMethods(collectProcessor, scope, module)) break;
      if (!HelidonCommonUtils.processRulesHttpMethods(collectProcessor, scope, module)) break;
      if (!HelidonCommonUtils.processRestServerEndpointMethods(collectProcessor, module.getModuleWithDependenciesScope(), module)) break;
    }
    return new ArrayList<>(collectProcessor.getResults());
  }
}
