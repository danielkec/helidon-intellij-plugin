// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers.view;

import com.intellij.helidon.HelidonIcons;
import com.intellij.helidon.providers.HelidonRequestMethods;
import com.intellij.helidon.utils.HelidonBundle;
import com.intellij.helidon.utils.HelidonCommonUtils;
import com.intellij.helidon.utils.HelidonUrlTargetInfo;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.microservices.endpoints.*;
import com.intellij.microservices.endpoints.presentation.HttpMethodPresentation;
import com.intellij.microservices.jvm.cache.SourceTestLibSearcher;
import com.intellij.microservices.oas.*;
import com.intellij.microservices.url.UrlPath;
import com.intellij.microservices.url.UrlTargetInfo;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.uast.UastModificationTracker;
import com.intellij.util.CommonProcessors.CollectProcessor;
import kotlin.text.StringsKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UastContextKt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.intellij.helidon.utils.HelidonCommonUtils.hasHelidonLibrary;
import static com.intellij.microservices.endpoints.EndpointTypes.HTTP_SERVER_TYPE;
import static com.intellij.microservices.jvm.url.UastUrlAttributeUtils.isUastDeclarationDeprecated;
import static com.intellij.util.containers.ContainerUtil.emptyList;

final class HelidonUrlFramework implements EndpointsUrlTargetProvider<HelidonUrlTargetInfo, HelidonUrlTargetInfo> {
  private final FrameworkPresentation myPresentation =
    new FrameworkPresentation(HelidonBundle.HELIDON_LIBRARY, HelidonBundle.HELIDON_LIBRARY, HelidonIcons.Helidon);

  private final SourceTestLibSearcher<HelidonUrlTargetInfo> groupsSearcher =
    new SourceTestLibSearcher<>("HELIDON_GROUPS", HelidonUrlFramework::findEndpointGroups);

  @Override
  public @NotNull EndpointType getEndpointType() {
    return HTTP_SERVER_TYPE;
  }

  @Override
  public @NotNull FrameworkPresentation getPresentation() {
    return myPresentation;
  }

  @Override
  public @NotNull Status getStatus(@NotNull Project project) {
    if (hasHelidonLibrary(project)) return Status.HAS_ENDPOINTS;
    return Status.UNAVAILABLE;
  }

  @Override
  public @NotNull Iterable<HelidonUrlTargetInfo> getEndpointGroups(@NotNull Project project, @NotNull EndpointsFilter filter) {
    if (!(filter instanceof ModuleEndpointsFilter moduleFilter)) return emptyList();

    Module module = moduleFilter.getModule();
    if (!hasHelidonLibrary(module)) return emptyList();

    return groupsSearcher.iterable(moduleFilter.getModule(), moduleFilter.getFromTests(), moduleFilter.getFromLibraries());
  }

  private static @NotNull Collection<HelidonUrlTargetInfo> findEndpointGroups(Module module, GlobalSearchScope filterScope) {
    CollectProcessor<HelidonUrlTargetInfo> collectProcessor = new CollectProcessor<>();
    GlobalSearchScope classReferencesScope = HelidonCommonUtils.getRoutingClassReferencesScope(module)
      .intersectWith(filterScope);

    HelidonCommonUtils.processBuilderRegisterMethodsWithProgress(collectProcessor, classReferencesScope, module);
    HelidonCommonUtils.processBuilderHttpMethods(collectProcessor, classReferencesScope, module);
    HelidonCommonUtils.processRestServerEndpointMethods(collectProcessor, filterScope, module);

    return collectProcessor.getResults();
  }

  @Override
  public @NotNull Iterable<HelidonUrlTargetInfo> getEndpoints(@NotNull HelidonUrlTargetInfo registerEndpoint) {
    return getRegisteredEndpoints(registerEndpoint);
  }

  @Override
  public @NotNull ModificationTracker getModificationTracker(@NotNull Project project) {
    return UastModificationTracker.getInstance(project);
  }

  private static @NotNull Iterable<HelidonUrlTargetInfo> getRegisteredEndpoints(@NotNull HelidonUrlTargetInfo groupEndpoint) {
    PsiElement registerPoint = groupEndpoint.resolveToPsiElement();
    if (registerPoint == null) return Collections.emptyList();
    if (groupEndpoint.getType() != HelidonRequestMethods.REGISTER) return Collections.singletonList(groupEndpoint);
    UCallExpression invocationPoint = UastContextKt.getUastParentOfType(registerPoint, UCallExpression.class);
    if (invocationPoint != null) {
      CollectProcessor<HelidonUrlTargetInfo> collectProcessor = new CollectProcessor<>() {
        @Override
        protected boolean accept(HelidonUrlTargetInfo info) {
          return hasMatchingParentUrl(groupEndpoint, info.getParentUrl());
        }
      };
      for (PsiType serviceType : HelidonCommonUtils.getRegisteredServiceTypes(invocationPoint)) {
        if (!(serviceType instanceof PsiClassType)) continue;
        PsiClass resolve = ((PsiClassType)serviceType).resolve();
        if (resolve != null) {
          HelidonCommonUtils.processRulesHttpMethods(collectProcessor, new LocalSearchScope(resolve),
                                                     ModuleUtilCore.findModuleForPsiElement(registerPoint));
        }
      }
      return collectProcessor.getResults();
    }
    return emptyList();
  }

  private static boolean hasMatchingParentUrl(@NotNull HelidonUrlTargetInfo groupEndpoint, @Nullable String parentUrl) {
    return parentUrl != null && normalizeUrl(getFullUrlDefinition(groupEndpoint)).equals(normalizeUrl(parentUrl));
  }

  private static @NotNull String getFullUrlDefinition(@NotNull HelidonUrlTargetInfo info) {
    String parentUrl = info.getParentUrl();
    return parentUrl != null ? parentUrl + info.getUrlDefinition() : info.getUrlDefinition();
  }

  private static @NotNull String normalizeUrl(@NotNull String url) {
    return StringsKt.removePrefix(url, "/");
  }

  private static @NotNull PresentationData getPresentation(HelidonUrlTargetInfo info, String url) {
    HelidonRequestMethods infoType = info.getType();
    String methodType = infoType == HelidonRequestMethods.REGISTER || infoType == HelidonRequestMethods.UNKNOWN ? "" : infoType.name();
    return new HttpMethodPresentation(url.startsWith("/") ? url : "/" + url, methodType, getEndpointContainerName(info),
                                      HelidonIcons.Helidon,
                                      isUastDeclarationDeprecated(info.resolveToPsiElement()) ? CodeInsightColors.DEPRECATED_ATTRIBUTES : null);
  }

  @Override
  public @NotNull ItemPresentation getEndpointPresentation(@NotNull HelidonUrlTargetInfo group, @NotNull HelidonUrlTargetInfo endpoint) {
    return getPresentation(endpoint, endpoint.getPresentationPath());
  }

  private static String joinSegments(@NotNull UrlPath path) {
    return path.getPresentation(UrlPath.FULL_PATH_VARIABLE_PRESENTATION);
  }

  private static @NotNull String getEndpointContainerName(@NotNull HelidonUrlTargetInfo endpoint) {
    PsiMethod method = PsiTreeUtil.getParentOfType(endpoint.resolveToPsiElement(), PsiMethod.class);
    if (method == null) return "";
    PsiClass aClass = method.getContainingClass();
    if (aClass != null && aClass.getName() != null) {
      return aClass.getName();
    }
    return method.getName();
  }

  @Override
  public @Nullable PsiElement getDocumentationElement(@NotNull HelidonUrlTargetInfo group, @NotNull HelidonUrlTargetInfo endpoint) {
    return endpoint.resolveToPsiElement();
  }

  @Override
  public @NotNull Iterable<UrlTargetInfo> getUrlTargetInfo(@NotNull HelidonUrlTargetInfo group, @NotNull HelidonUrlTargetInfo endpoint) {
    return List.of(endpoint);
  }

  @Override
  public @NotNull OpenApiSpecification getOpenApiSpecification(@NotNull HelidonUrlTargetInfo group,
                                                               @NotNull HelidonUrlTargetInfo endpoint) {
    OpenApiSpecification specification = OasExportUtilsKt.getSpecificationByUrls(List.of(endpoint));
    if (!endpoint.isRestServerEndpoint()) return specification;

    PsiMethod declarationMethod = endpoint.getDeclarationMethod();
    if (declarationMethod == null) return specification;

    Collection<OasParameter> parameters = getRestServerOpenApiParameters(declarationMethod);
    OasRequestBody requestBody = getRestServerRequestBody(declarationMethod);
    if (parameters.isEmpty() && requestBody == null) return specification;

    return withOpenApiDetails(specification, parameters, requestBody);
  }

  private static @NotNull Collection<OasParameter> getRestServerOpenApiParameters(@NotNull PsiMethod declarationMethod) {
    Collection<OasParameter> parameters = new ArrayList<>();
    addParameters(parameters, HelidonCommonUtils.getRestServerHeaderParameters(declarationMethod), OasParameterIn.HEADER);
    addParameters(parameters, HelidonCommonUtils.getRestServerQueryParameters(declarationMethod), OasParameterIn.QUERY);
    return parameters;
  }

  private static void addParameters(@NotNull Collection<? super OasParameter> parameters,
                                    @NotNull Collection<String> names,
                                    @NotNull OasParameterIn inPlace) {
    for (String name : names) {
      parameters.add(new OasParameter(name, inPlace, "", false, false, null, null));
    }
  }

  private static @Nullable OasRequestBody getRestServerRequestBody(@NotNull PsiMethod declarationMethod) {
    PsiType entityType = HelidonCommonUtils.getRestServerEntityParameterType(declarationMethod);
    if (entityType == null) return null;

    Collection<String> mediaTypes = HelidonCommonUtils.getRestServerConsumedMediaTypes(declarationMethod);
    if (mediaTypes.isEmpty()) {
      mediaTypes = Collections.singletonList("application/json");
    }

    OasSchema schema = getOpenApiSchema(entityType);
    Map<String, OasSchema> content = new LinkedHashMap<>();
    for (String mediaType : mediaTypes) {
      content.put(mediaType, schema);
    }
    return new OasRequestBody(content, !isOptionalType(entityType));
  }

  private static @NotNull OasSchema getOpenApiSchema(@NotNull PsiType type) {
    String canonicalText = type.getCanonicalText();
    if (PsiTypes.booleanType().equals(type) || CommonClassNames.JAVA_LANG_BOOLEAN.equals(canonicalText)) {
      return schema(OasSchemaType.BOOLEAN, null, null);
    }
    if (PsiTypes.intType().equals(type) ||
        PsiTypes.shortType().equals(type) ||
        PsiTypes.byteType().equals(type) ||
        CommonClassNames.JAVA_LANG_INTEGER.equals(canonicalText) ||
        CommonClassNames.JAVA_LANG_SHORT.equals(canonicalText) ||
        CommonClassNames.JAVA_LANG_BYTE.equals(canonicalText)) {
      return schema(OasSchemaType.INTEGER, OasSchemaFormat.INT_32, null);
    }
    if (PsiTypes.longType().equals(type) || CommonClassNames.JAVA_LANG_LONG.equals(canonicalText)) {
      return schema(OasSchemaType.INTEGER, OasSchemaFormat.INT_64, null);
    }
    if (PsiTypes.floatType().equals(type) || CommonClassNames.JAVA_LANG_FLOAT.equals(canonicalText)) {
      return schema(OasSchemaType.NUMBER, OasSchemaFormat.FLOAT, null);
    }
    if (PsiTypes.doubleType().equals(type) || CommonClassNames.JAVA_LANG_DOUBLE.equals(canonicalText)) {
      return schema(OasSchemaType.NUMBER, OasSchemaFormat.DOUBLE, null);
    }
    if (CommonClassNames.JAVA_LANG_STRING.equals(canonicalText)) {
      return schema(OasSchemaType.STRING, null, null);
    }
    if (type instanceof PsiArrayType) {
      return schema(OasSchemaType.ARRAY, null, getOpenApiSchema(((PsiArrayType)type).getComponentType()));
    }
    return schema(OasSchemaType.OBJECT, null, null);
  }

  private static boolean isOptionalType(@NotNull PsiType type) {
    return type.getCanonicalText().startsWith(CommonClassNames.JAVA_UTIL_OPTIONAL + "<");
  }

  private static @NotNull OasSchema schema(@NotNull OasSchemaType type,
                                           @Nullable OasSchemaFormat format,
                                           @Nullable OasSchema items) {
    return new OasSchema(type, format, null, items, Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(), false, false);
  }

  private static @NotNull OpenApiSpecification withOpenApiDetails(@NotNull OpenApiSpecification specification,
                                                                  @NotNull Collection<OasParameter> additionalParameters,
                                                                  @Nullable OasRequestBody requestBody) {
    Collection<OasEndpointPath> paths = new ArrayList<>();
    for (OasEndpointPath path : specification.getPaths()) {
      Collection<OasOperation> operations = new ArrayList<>();
      for (OasOperation operation : path.getOperations()) {
        operations.add(withOpenApiDetails(operation, additionalParameters, requestBody));
      }
      paths.add(new OasEndpointPath(path.getPath(), path.getSummary(), operations));
    }
    return new OpenApiSpecification(paths, specification.getComponents(), specification.getTags());
  }

  private static @NotNull OasOperation withOpenApiDetails(@NotNull OasOperation operation,
                                                         @NotNull Collection<OasParameter> additionalParameters,
                                                         @Nullable OasRequestBody requestBody) {
    Collection<OasParameter> parameters = new ArrayList<>(operation.getParameters());
    for (OasParameter additionalParameter : additionalParameters) {
      if (!hasParameter(parameters, additionalParameter.getName(), additionalParameter.getInPlace())) {
        parameters.add(additionalParameter);
      }
    }

    return new OasOperation(operation.getMethod(),
                            operation.getTags(),
                            operation.getDescription(),
                            operation.getSummary(),
                            operation.getOperationId(),
                            operation.isDeprecated(),
                            parameters,
                            operation.getRequestBody() == null ? requestBody : operation.getRequestBody(),
                            operation.getResponses());
  }

  private static boolean hasParameter(@NotNull Collection<OasParameter> parameters,
                                      @NotNull String name,
                                      @NotNull OasParameterIn inPlace) {
    for (OasParameter parameter : parameters) {
      if (name.equals(parameter.getName()) && inPlace == parameter.getInPlace()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isValidEndpoint(@NotNull HelidonUrlTargetInfo group, @NotNull HelidonUrlTargetInfo endpoint) {
    PsiElement psiElement = endpoint.resolveToPsiElement();
    return psiElement != null && psiElement.isValid();
  }
}
