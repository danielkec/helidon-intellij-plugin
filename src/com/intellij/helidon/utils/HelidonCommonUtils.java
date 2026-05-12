// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.utils;

import com.intellij.codeInspection.dataFlow.StringExpressionHelper;
import com.intellij.helidon.constants.HelidonConstants;
import com.intellij.helidon.providers.HelidonRequestMethods;
import com.intellij.java.library.JavaLibraryModificationTracker;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider.Result;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.uast.UastModificationTracker;
import com.intellij.uast.UastSmartPointer;
import com.intellij.util.Processor;
import com.intellij.util.containers.ConcurrentFactoryMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.*;

import java.util.*;
import java.util.stream.Collectors;

import static com.intellij.helidon.providers.HelidonReferenceContributorKt.*;

public final class HelidonCommonUtils {
  private static final String JAVA_UTIL_ARRAYS = "java.util.Arrays";
  private static final String JAVA_UTIL_COLLECTIONS = "java.util.Collections";
  private static final String JAVA_UTIL_ENUM_SET = "java.util.EnumSet";
  private static final String JAVA_UTIL_FUNCTION_SUPPLIER = "java.util.function.Supplier";
  private static final String JAVA_LANG_ITERABLE = "java.lang.Iterable";
  private static final String JAVA_UTIL_LIST = "java.util.List";
  private static final String JAVA_UTIL_SET = "java.util.Set";
  private static final String HELIDON_COMMON_HTTP_REQUEST_METHOD = "io.helidon.common.http.Http.RequestMethod";
  private static final String HELIDON_HTTP_METHOD = "io.helidon.http.Method";
  private static final String HELIDON_HTTP_METHODS = "io.helidon.http.Methods";
  private static final Map<String, String> HTTP_METHOD_ANNOTATIONS = Map.of(HelidonConstants.HTTP_GET, "GET",
                                                                            HelidonConstants.HTTP_HEAD, "HEAD",
                                                                            HelidonConstants.HTTP_POST, "POST",
                                                                            HelidonConstants.HTTP_PUT, "PUT",
                                                                            HelidonConstants.HTTP_PATCH, "PATCH",
                                                                            HelidonConstants.HTTP_DELETE, "DELETE",
                                                                            HelidonConstants.HTTP_OPTIONS, "OPTIONS");
  private static final Key<CachedValue<Map<SearchScope, Set<UCallExpression>>>> METHOD_INVOCATIONS_KEY =
    Key.create("METHOD_INVOCATIONS_KEY");
  private static final Key<CachedValue<List<ServiceRegistration>>> SERVICE_REGISTRATIONS_KEY =
    Key.create("SERVICE_REGISTRATIONS_KEY");

  private HelidonCommonUtils() {
  }

  public static boolean hasHelidonLibrary(Project project) {
    return HelidonCoreUtils.hasHelidonLibrary(project);
  }

  public static boolean hasHelidonLibrary(@Nullable Module module) {
    return HelidonCoreUtils.hasHelidonLibrary(module);
  }

  public static boolean hasHelidonMPLibrary(@Nullable Module module) {
    return HelidonCoreUtils.hasHelidonMPLibrary(module);
  }

  public static @NotNull Set<String> getParentUrlPaths(@Nullable PsiElement host) {
    if (host == null) return Collections.emptySet();
    Set<String> paths = RecursionManager.doPreventingRecursion(host, true, () -> calculateParentUrls(host));
    return paths != null ? paths : Collections.emptySet();
  }

  public static @NotNull Set<UExpression> getParentUrlPathExpressions(@Nullable PsiElement host) {
    if (host == null) return Collections.emptySet();
    Set<UExpression> paths = RecursionManager.doPreventingRecursion(host, true, () -> calculateParentUrlPathExpressions(host));
    return paths != null ? paths : Collections.emptySet();
  }

  private static @NotNull Set<String> calculateParentUrls(@NotNull PsiElement host) {
    Set<String> allParentPaths = new HashSet<>();
    UClass definedInUClass = UastContextKt.getUastParentOfType(host, UClass.class);
    if (definedInUClass == null) return Collections.emptySet();
    //PsiClass baseClass = JavaPsiFacade.getInstance(host.getProject()).findClass(HelidonConstants.SERVICE, definedInUxClass.getResolveScope());
    //if (baseClass == null || definedInClass.isInheritor(baseClass, false)) return Collections.emptySet();
    Module module = ModuleUtilCore.findModuleForPsiElement(host);
    if (module == null) return Collections.emptySet();
    PsiClassType psiClassType = JavaPsiFacade.getElementFactory(host.getProject()).createType(definedInUClass.getJavaPsi());
    for (ServiceRegistration registration : getServiceRegistrations(module)) {
      if (registration.accepts(psiClassType)) {
        Set<String> parentUrlPaths = getParentUrlPaths(registration.serviceExpression.getSourcePsi());
        if (parentUrlPaths.isEmpty()) {
          allParentPaths.add(registration.urlDefinition);
        }
        else {
          for (String path : parentUrlPaths) {
            if (StringUtil.isNotEmpty(path)) {
              allParentPaths.add(path + registration.urlDefinition);
            }
          }
        }
      }
    }
    return allParentPaths;
  }

  private static @NotNull Set<UExpression> calculateParentUrlPathExpressions(@NotNull PsiElement host) {
    Set<UExpression> allParentPaths = new HashSet<>();
    UClass definedInUClass = UastContextKt.getUastParentOfType(host, UClass.class);
    if (definedInUClass == null) return Collections.emptySet();
    Module module = ModuleUtilCore.findModuleForPsiElement(host);
    if (module == null) return Collections.emptySet();
    PsiClassType psiClassType = JavaPsiFacade.getElementFactory(host.getProject()).createType(definedInUClass.getJavaPsi());
    for (ServiceRegistration registration : getServiceRegistrations(module)) {
      if (registration.accepts(psiClassType)) {
        allParentPaths.add(registration.urlExpression);
        allParentPaths.addAll(getParentUrlPathExpressions(registration.serviceExpression.getSourcePsi()));
      }
    }
    return allParentPaths;
  }

  private static @NotNull List<ServiceRegistration> getServiceRegistrations(@NotNull Module module) {
    return CachedValuesManager.getManager(module.getProject())
      .getCachedValue(module, SERVICE_REGISTRATIONS_KEY, () -> {
        return Result.create(calculateServiceRegistrations(module),
                             UastModificationTracker.getInstance(module.getProject()),
                             JavaLibraryModificationTracker.getInstance(module.getProject()));
      }, false);
  }

  private static @NotNull List<ServiceRegistration> calculateServiceRegistrations(@NotNull Module module) {
    List<ServiceRegistration> result = new ArrayList<>();
    for (PsiMethod registerMethod : getBuilderRegisterMethod(module)) {
      result.addAll(calculateServiceRegistrationsForMethod(module, registerMethod));
    }
    return Collections.unmodifiableList(result);
  }

  private static @NotNull List<ServiceRegistration> calculateServiceRegistrationsForMethod(@NotNull Module module,
                                                                                           @NotNull PsiMethod registerMethod) {
    List<ServiceRegistration> result = new ArrayList<>();
    for (UCallExpression uCallExpression : getUCallExpressions(getRoutingClassReferencesScope(module), registerMethod)) {
      List<UExpression> valueArguments = uCallExpression.getValueArguments();
      if (valueArguments.size() < 2) continue;
      UExpression pathExpression = valueArguments.get(0);
      String expressionText = getUExpressionText(pathExpression);
      if (expressionText == null) continue;
      for (int i = 1; i < valueArguments.size(); i++) {
        UExpression serviceExpression = valueArguments.get(i);
        Collection<PsiType> serviceTypes = getRegisteredServiceTypes(serviceExpression);
        if (!serviceTypes.isEmpty()) {
          result.add(new ServiceRegistration(expressionText, pathExpression, serviceExpression, serviceTypes));
        }
      }
    }
    return result;
  }

  public static @NotNull Collection<Pair<UastSmartPointer<UExpression>, PsiType>> getServiceRegisterPathExpressions(@NotNull Module module) {
    List<Pair<UastSmartPointer<UExpression>, PsiType>> result = new ArrayList<>();
    for (ServiceRegistration registration : getServiceRegistrations(module)) {
      for (PsiType serviceType : registration.serviceTypes) {
        result.add(Pair.create(new UastSmartPointer<>(registration.urlExpression, UExpression.class), serviceType));
      }
    }
    return result;
  }

  private static @NotNull Set<UCallExpression> getUCallExpressions(@NotNull SearchScope scope, @NotNull PsiMethod psiMethod) {
    if (!psiMethod.isValid()) return Collections.emptySet();
    Map<SearchScope, Set<UCallExpression>> value = CachedValuesManager.getManager(psiMethod.getProject())
      .getCachedValue(psiMethod, METHOD_INVOCATIONS_KEY, () -> {
        return Result.create(createMethodsInScopeMap(psiMethod),
                             UastModificationTracker.getInstance(psiMethod.getProject()),
                             JavaLibraryModificationTracker.getInstance(psiMethod.getProject()));
      }, false);
    return value.get(scope);
  }

  private static @NotNull Map<SearchScope, Set<UCallExpression>> createMethodsInScopeMap(@NotNull PsiMethod psiMethod) {
    return ConcurrentFactoryMap.createMap(forScope -> {
            Set<UCallExpression> expressions = MethodReferencesSearch.search(psiMethod, forScope, true).findAll().stream()
              .map(reference -> UastContextKt.getUastParentOfType(reference.getElement(), UCallExpression.class))
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
            return expressions;
          });
  }

  public static @Nullable PsiType getRegisteredServiceType(@NotNull UCallExpression callExpression) {
    Collection<PsiType> serviceTypes = getRegisteredServiceTypes(callExpression);
    return serviceTypes.isEmpty() ? null : serviceTypes.iterator().next();
  }

  public static @NotNull Collection<PsiType> getRegisteredServiceTypes(@NotNull UCallExpression callExpression) {
    List<PsiType> serviceTypes = new ArrayList<>();
    List<UExpression> arguments = callExpression.getValueArguments();
    if (arguments.size() < 2) return Collections.emptyList();
    for (int i = 1; i < arguments.size(); i++) {
      serviceTypes.addAll(getRegisteredServiceTypes(arguments.get(i)));
    }
    return serviceTypes;
  }

  private static @NotNull Collection<PsiType> getRegisteredServiceTypes(@NotNull UExpression expression) {
    Project project = expression.getSourcePsi() != null ? expression.getSourcePsi().getProject() : null;
    if (project == null) return Collections.emptyList();

    Set<PsiType> sourceTypes = new HashSet<>();
    collectSourceServiceTypes(expression.getSourcePsi(), project, sourceTypes);
    if (!sourceTypes.isEmpty()) return sourceTypes;

    Set<PsiType> expressionTypes = new HashSet<>();
    collectServiceTypes(expression.getExpressionType(), project, expressionTypes);
    return expressionTypes;
  }

  private static void collectSourceServiceTypes(@Nullable PsiElement sourcePsi,
                                                @NotNull Project project,
                                                @NotNull Collection<? super PsiType> result) {
    if (sourcePsi == null) return;
    if (sourcePsi instanceof PsiExpression) {
      collectTopLevelServiceTypes((PsiExpression)sourcePsi, project, result);
    }
  }

  private static void collectTopLevelServiceTypes(@Nullable PsiExpression expression,
                                                  @NotNull Project project,
                                                  @NotNull Collection<? super PsiType> result) {
    PsiExpression unwrappedExpression = unwrapServiceExpression(expression);
    if (unwrappedExpression == null) return;

    if (unwrappedExpression instanceof PsiNewExpression) {
      collectServiceTypes(((PsiNewExpression)unwrappedExpression).getType(), project, result);
      return;
    }

    if (unwrappedExpression instanceof PsiMethodReferenceExpression) {
      collectMethodReferenceServiceType((PsiMethodReferenceExpression)unwrappedExpression, project, result);
      return;
    }

    if (unwrappedExpression instanceof PsiLambdaExpression) {
      collectLambdaServiceTypes((PsiLambdaExpression)unwrappedExpression, project, result);
      return;
    }

    if (unwrappedExpression instanceof PsiMethodCallExpression &&
        isIterableType(unwrappedExpression.getType())) {
      for (PsiExpression argument : ((PsiMethodCallExpression)unwrappedExpression).getArgumentList().getExpressions()) {
        collectTopLevelServiceTypes(argument, project, result);
      }
      return;
    }

    if (unwrappedExpression instanceof PsiConditionalExpression) {
      PsiConditionalExpression conditionalExpression = (PsiConditionalExpression)unwrappedExpression;
      collectTopLevelServiceTypes(conditionalExpression.getThenExpression(), project, result);
      collectTopLevelServiceTypes(conditionalExpression.getElseExpression(), project, result);
    }
  }

  private static @Nullable PsiExpression unwrapServiceExpression(@Nullable PsiExpression expression) {
    PsiExpression current = expression;
    while (current instanceof PsiParenthesizedExpression || current instanceof PsiTypeCastExpression) {
      if (current instanceof PsiParenthesizedExpression) {
        current = ((PsiParenthesizedExpression)current).getExpression();
      }
      else {
        current = ((PsiTypeCastExpression)current).getOperand();
      }
    }
    return current;
  }

  private static void collectLambdaServiceTypes(@NotNull PsiLambdaExpression lambdaExpression,
                                                @NotNull Project project,
                                                @NotNull Collection<? super PsiType> result) {
    PsiElement body = lambdaExpression.getBody();
    if (body instanceof PsiExpression) {
      collectTopLevelServiceTypes((PsiExpression)body, project, result);
      return;
    }

    if (body instanceof PsiCodeBlock) {
      for (PsiStatement statement : ((PsiCodeBlock)body).getStatements()) {
        if (statement instanceof PsiReturnStatement) {
          collectTopLevelServiceTypes(((PsiReturnStatement)statement).getReturnValue(), project, result);
        }
      }
    }
  }

  private static void collectMethodReferenceServiceType(@NotNull PsiMethodReferenceExpression methodReference,
                                                        @NotNull Project project,
                                                        @NotNull Collection<? super PsiType> result) {
    PsiElement resolved = methodReference.resolve();
    if (resolved instanceof PsiClass) {
      collectServiceTypes(JavaPsiFacade.getElementFactory(project).createType((PsiClass)resolved), project, result);
      return;
    }
    if (!(resolved instanceof PsiMethod)) {
      PsiTypeElement qualifierType = methodReference.getQualifierType();
      collectServiceTypes(qualifierType != null ? qualifierType.getType() : null, project, result);
      return;
    }
    PsiMethod method = (PsiMethod)resolved;
    if (method.isConstructor()) {
      PsiClass containingClass = method.getContainingClass();
      if (containingClass != null) {
        collectServiceTypes(JavaPsiFacade.getElementFactory(project).createType(containingClass), project, result);
      }
    }
    else {
      collectServiceTypes(method.getReturnType(), project, result);
    }
  }

  private static void collectServiceTypes(@Nullable PsiType type,
                                          @NotNull Project project,
                                          @NotNull Collection<? super PsiType> result) {
    if (type == null) return;
    if (type instanceof PsiWildcardType) {
      collectServiceTypes(((PsiWildcardType)type).getExtendsBound(), project, result);
      return;
    }
    if (isAssignableToAny(type, project, HelidonConstants.HTTP_SERVICE, HelidonConstants.SERVICE)) {
      result.add(type);
      return;
    }
    if (!(type instanceof PsiClassType)) return;

    PsiClassType classType = (PsiClassType)type;
    PsiClass resolved = classType.resolve();
    if (resolved == null) return;
    if (isIterableType(classType)) {
      for (PsiType parameter : classType.getParameters()) {
        collectServiceTypes(parameter, project, result);
      }
      return;
    }
    if (!JAVA_UTIL_FUNCTION_SUPPLIER.equals(resolved.getQualifiedName())) return;
    for (PsiType parameter : classType.getParameters()) {
      collectServiceTypes(parameter, project, result);
    }
  }

  private static boolean isIterableType(@Nullable PsiType type) {
    if (!(type instanceof PsiClassType)) return false;
    PsiClass resolved = ((PsiClassType)type).resolve();
    return resolved != null &&
           (JAVA_LANG_ITERABLE.equals(resolved.getQualifiedName()) || InheritanceUtil.isInheritor(resolved, JAVA_LANG_ITERABLE));
  }

  private static boolean isAssignableToAny(@NotNull PsiType type, @NotNull Project project, @NotNull String... classNames) {
    for (String className : classNames) {
      PsiClassType serviceType = PsiType.getTypeByName(className, project, GlobalSearchScope.allScope(project));
      if (serviceType.isAssignableFrom(type)) {
        return true;
      }
    }
    return false;
  }

  public static boolean processBuilderRegisterMethodsWithProgress(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                                  @NotNull GlobalSearchScope scope,
                                                                  @NotNull Module module) {
    return processBuilderRegisterMethods(processor, scope, module);
  }

  public static boolean processBuilderRegisterMethods(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                      @NotNull GlobalSearchScope scope, @NotNull Module module) {
    Processor<HelidonUrlTargetInfo> uniqueProcessor = createUniqueTargetProcessor(processor);
    for (PsiMethod registerMethod : getBuilderRegisterMethod(module)) {
      if (!findAndProcessTargets(uniqueProcessor, scope, registerMethod, HelidonRequestMethods.REGISTER, 0)) {
        return false;
      }
    }
    return true;
  }

  private static @NotNull Processor<HelidonUrlTargetInfo> createUniqueTargetProcessor(@NotNull Processor<? super HelidonUrlTargetInfo> processor) {
    Set<String> processed = new HashSet<>();
    return target -> {
      String key = target.getType() + "\n" +
                   target.getParentUrl() + "\n" +
                   target.getUrlDefinition() + "\n" +
                   target.getMethods() + "\n" +
                   getTargetSourceKey(target);
      return !processed.add(key) || processor.process(target);
    };
  }

  private static @NotNull String getTargetSourceKey(@NotNull HelidonUrlTargetInfo target) {
    PsiElement element = target.resolveToPsiElement();
    if (element == null) return "<unresolved>";

    PsiFile containingFile = element.getContainingFile();
    VirtualFile virtualFile = containingFile == null ? null : containingFile.getVirtualFile();
    String fileKey = virtualFile == null ? String.valueOf(containingFile) : virtualFile.getPath();
    TextRange textRange = element.getTextRange();
    return fileKey + "\n" + textRange.getStartOffset() + "\n" + textRange.getEndOffset();
  }

  public static boolean processRulesHttpMethods(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                @NotNull SearchScope scope,
                                                @Nullable Module module) {
    if (module == null) return true;
    Processor<HelidonUrlTargetInfo> uniqueProcessor = createUniqueTargetProcessor(processor);
    for (RouteMethod rulesMethod : getRulesHttpMethods(module)) {
      if (!findAndProcessTargets(uniqueProcessor, scope, rulesMethod)) {
        return false;
      }
    }
    return true;
  }

  public static boolean processBuilderHttpMethods(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                  @NotNull SearchScope scope,
                                                  @Nullable Module module) {
    if (module == null) return true;
    Processor<HelidonUrlTargetInfo> uniqueProcessor = createUniqueTargetProcessor(processor);
    for (RouteMethod rulesMethod : getBuilderHttpMethods(module)) {
      if (!findAndProcessTargets(uniqueProcessor, scope, rulesMethod, true)) {
        return false;
      }
    }
    return true;
  }

  public static boolean processRestServerEndpointMethods(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                         @NotNull SearchScope scope,
                                                         @Nullable Module module) {
    if (module == null) return true;

    PsiClass endpointAnnotation = findClass(module, HelidonConstants.REST_SERVER_ENDPOINT);
    if (endpointAnnotation == null) return true;

    for (PsiClass endpointClass : AnnotatedElementsSearch.searchPsiClasses(endpointAnnotation, scope)) {
      if (!processRestServerEndpointClass(processor, endpointClass)) {
        return false;
      }
    }
    return true;
  }

  public static boolean isHelidonHttpServiceClass(@NotNull PsiClass psiClass) {
    return InheritanceUtil.isInheritor(psiClass, HelidonConstants.HTTP_SERVICE) ||
           InheritanceUtil.isInheritor(psiClass, HelidonConstants.SERVICE);
  }

  public static boolean isHelidonServiceRegistryClass(@NotNull PsiClass psiClass) {
    return HelidonCoreUtils.isHelidonServiceRegistryClass(psiClass);
  }

  public static @NotNull Set<PsiElement> getHelidonServiceUsageTargets(@NotNull Module module, @NotNull PsiClass serviceClass) {
    return HelidonCoreUtils.getHelidonServiceUsageTargets(module, serviceClass);
  }

  private static boolean processRestServerEndpointClass(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                        @NotNull PsiClass endpointClass) {
    List<PathDefinition> parentPaths = getEndpointTypePaths(endpointClass);
    Set<String> processed = new HashSet<>();

    for (PsiMethod method : endpointClass.getAllMethods()) {
      if (shouldSkipRestServerEndpointMethod(method)) continue;

      List<PsiMethod> methodHierarchy = getMethodHierarchy(method);
      Set<String> httpMethods = getHttpMethods(methodHierarchy);
      if (httpMethods.isEmpty()) continue;

      List<PathDefinition> methodPaths = getMethodPaths(method, methodHierarchy);
      for (PathDefinition methodPath : methodPaths) {
        if (parentPaths.isEmpty()) {
          if (!processRestServerEndpoint(processor, processed, method, methodPath, null, httpMethods)) {
            return false;
          }
          continue;
        }
        for (PathDefinition parentPath : parentPaths) {
          if (!processRestServerEndpoint(processor, processed, method, methodPath, parentPath, httpMethods)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static boolean processRestServerEndpoint(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                   @NotNull Set<String> processed,
                                                   @NotNull PsiMethod method,
                                                   @NotNull PathDefinition methodPath,
                                                   @Nullable PathDefinition parentPath,
                                                   @NotNull Set<String> httpMethods) {
    String signature = method.getName() + "#" + method.getParameterList().getParametersCount();
    String parentUrl = parentPath == null ? "" : parentPath.path;
    String key = parentUrl + "\n" + methodPath.path + "\n" + signature + "\n" + StringUtil.join(httpMethods, ",");
    if (!processed.add(key)) return true;

    HelidonRequestMethods requestMethod = httpMethods.size() == 1
                                          ? HelidonRequestMethods.getTypeByMethodName(httpMethods.iterator().next().toLowerCase(Locale.ENGLISH))
                                          : HelidonRequestMethods.ANY_OF;
    PsiElement target = methodPath.source != null ? methodPath.source : method.getNameIdentifier();
    if (target == null) target = method;
    HelidonUrlTargetInfo targetInfo = createTargetInfo(methodPath.path,
                                                       target,
                                                       requestMethod,
                                                       requestMethod == HelidonRequestMethods.UNKNOWN || httpMethods.size() > 1
                                                       ? httpMethods
                                                       : null);
    if (parentPath != null) {
      targetInfo.withParentUrl(parentPath.path);
    }
    return processor.process(targetInfo);
  }

  private static boolean shouldSkipRestServerEndpointMethod(@NotNull PsiMethod method) {
    PsiClass containingClass = method.getContainingClass();
    if (containingClass == null || CommonClassNames.JAVA_LANG_OBJECT.equals(containingClass.getQualifiedName())) return true;
    return method.isConstructor() ||
           method.hasModifierProperty(PsiModifier.PRIVATE) ||
           method.hasModifierProperty(PsiModifier.STATIC);
  }

  private static @NotNull List<PsiMethod> getMethodHierarchy(@NotNull PsiMethod method) {
    List<PsiMethod> result = new ArrayList<>();
    collectMethodHierarchy(method, result, new HashSet<>());
    return result;
  }

  private static void collectMethodHierarchy(@NotNull PsiMethod method,
                                             @NotNull List<? super PsiMethod> result,
                                             @NotNull Set<? super PsiMethod> visited) {
    if (!visited.add(method)) return;
    result.add(method);
    for (PsiMethod superMethod : method.findSuperMethods()) {
      collectMethodHierarchy(superMethod, result, visited);
    }
  }

  private static @NotNull Set<String> getHttpMethods(@NotNull List<PsiMethod> methodHierarchy) {
    Set<String> result = new LinkedHashSet<>();
    for (PsiMethod method : methodHierarchy) {
      for (PsiAnnotation annotation : getAnnotations(method)) {
        String httpMethod = getHttpMethod(annotation);
        if (httpMethod != null) {
          result.add(httpMethod);
        }
      }
    }
    return result;
  }

  private static @Nullable String getHttpMethod(@NotNull PsiAnnotation annotation) {
    String qualifiedName = annotation.getQualifiedName();
    String directMethod = qualifiedName == null ? null : HTTP_METHOD_ANNOTATIONS.get(qualifiedName);
    if (directMethod != null) return directMethod;
    if (HelidonConstants.HTTP_HTTP_METHOD.equals(qualifiedName)) {
      return getAnnotationStringValue(annotation);
    }

    PsiClass annotationClass = annotation.resolveAnnotationType();
    PsiAnnotation metaAnnotation = annotationClass == null ? null : findAnnotation(annotationClass, HelidonConstants.HTTP_HTTP_METHOD);
    return metaAnnotation == null ? null : getAnnotationStringValue(metaAnnotation);
  }

  private static @NotNull List<PathDefinition> getEndpointTypePaths(@NotNull PsiClass endpointClass) {
    List<PathDefinition> result = new ArrayList<>();
    collectEndpointTypePaths(endpointClass, result, new HashSet<>());
    return result;
  }

  private static void collectEndpointTypePaths(@NotNull PsiClass psiClass,
                                               @NotNull List<? super PathDefinition> result,
                                               @NotNull Set<? super PsiClass> visited) {
    if (!visited.add(psiClass)) return;
    result.addAll(getPathDefinitions(psiClass));

    PsiClass superClass = psiClass.getSuperClass();
    if (superClass != null && !CommonClassNames.JAVA_LANG_OBJECT.equals(superClass.getQualifiedName())) {
      collectEndpointTypePaths(superClass, result, visited);
    }
    for (PsiClass anInterface : psiClass.getInterfaces()) {
      collectEndpointTypePaths(anInterface, result, visited);
    }
  }

  private static @NotNull List<PathDefinition> getMethodPaths(@NotNull PsiMethod method,
                                                              @NotNull List<PsiMethod> methodHierarchy) {
    for (PsiMethod hierarchyMethod : methodHierarchy) {
      List<PathDefinition> paths = getPathDefinitions(hierarchyMethod);
      if (!paths.isEmpty()) {
        return paths;
      }
    }
    return Collections.singletonList(new PathDefinition("/", method.getNameIdentifier()));
  }

  private static @NotNull List<PathDefinition> getPathDefinitions(@NotNull PsiModifierListOwner owner) {
    PsiAnnotation annotation = findAnnotation(owner, HelidonConstants.HTTP_PATH);
    if (annotation == null) return Collections.emptyList();

    String path = getAnnotationStringValue(annotation);
    if (path == null) {
      path = "/";
    }
    PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("value");
    PsiElement source = value != null ? value : annotation.getNameReferenceElement();
    return Collections.singletonList(new PathDefinition(path, source));
  }

  private static @Nullable PsiAnnotation findAnnotation(@NotNull PsiModifierListOwner owner, @NotNull String annotationName) {
    for (PsiAnnotation annotation : getAnnotations(owner)) {
      if (annotationName.equals(annotation.getQualifiedName())) {
        return annotation;
      }
    }
    return null;
  }

  private static @NotNull PsiAnnotation[] getAnnotations(@NotNull PsiModifierListOwner owner) {
    PsiModifierList modifierList = owner.getModifierList();
    return modifierList == null ? PsiAnnotation.EMPTY_ARRAY : modifierList.getAnnotations();
  }

  private static @Nullable String getAnnotationStringValue(@NotNull PsiAnnotation annotation) {
    PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
    if (value instanceof PsiExpression) {
      Pair<PsiElement, String> evaluated = StringExpressionHelper.evaluateExpression((PsiExpression)value);
      if (evaluated != null) {
        return evaluated.second;
      }
      if (value instanceof PsiLiteralExpression) {
        Object literalValue = ((PsiLiteralExpression)value).getValue();
        return literalValue instanceof String ? (String)literalValue : null;
      }
    }
    return null;
  }

  private static boolean findAndProcessTargets(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                               @NotNull SearchScope scope,
                                               @NotNull PsiMethod psiMethod,
                                               @NotNull HelidonRequestMethods requestMethods,
                                               int expressionNum) {
    return findAndProcessTargets(processor, scope, psiMethod, requestMethods, expressionNum, false);
  }

  private static boolean findAndProcessTargets(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                               @NotNull SearchScope scope,
                                               @NotNull PsiMethod psiMethod,
                                               @NotNull HelidonRequestMethods requestMethods,
                                               int expressionNum,
                                               boolean skipServiceClasses) {
    for (UCallExpression callExpression : getUCallExpressions(scope, psiMethod)) {
      if (skipServiceClasses && isInsideHttpServiceClass(callExpression)) continue;
      UExpression expression = callExpression.getArgumentForParameter(expressionNum);
      if (expression == null) continue;
      if (!processExpressions(processor, requestMethods, expression, getExplicitMethods(requestMethods, callExpression))) return false;
    }
    return true;
  }

  private static boolean findAndProcessTargets(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                               @NotNull SearchScope scope,
                                               @NotNull RouteMethod routeMethod) {
    return findAndProcessTargets(processor, scope, routeMethod, false);
  }

  private static boolean findAndProcessTargets(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                               @NotNull SearchScope scope,
                                               @NotNull RouteMethod routeMethod,
                                               boolean skipServiceClasses) {
    for (UCallExpression callExpression : getUCallExpressions(scope, routeMethod.method)) {
      if (skipServiceClasses && isInsideHttpServiceClass(callExpression)) continue;
      if (routeMethod.routeObjectRegistration) {
        if (!processHttpRouteObjectTargets(processor, routeMethod, callExpression)) return false;
        continue;
      }
      Collection<String> explicitMethods = getRouteExplicitMethods(routeMethod, callExpression);
      HelidonRequestMethods requestMethod = routeMethod.getRequestMethod(explicitMethods);
      Collection<String> methods = getStoredExplicitMethods(requestMethod, explicitMethods);

      if (routeMethod.pathArgumentIndex >= 0) {
        UExpression expression = callExpression.getArgumentForParameter(routeMethod.pathArgumentIndex);
        if (expression == null) continue;
        if (!processExpressions(processor, requestMethod, expression, methods)) return false;
        continue;
      }

      PsiElement sourcePsi = callExpression.getSourcePsi();
      if (sourcePsi != null &&
          !processTarget(processor, sourcePsi, routeMethod.defaultPath, requestMethod, methods, getParentUrlPaths(sourcePsi))) {
        return false;
      }
    }
    return true;
  }

  private static boolean processHttpRouteObjectTargets(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                       @NotNull RouteMethod routeMethod,
                                                       @NotNull UCallExpression callExpression) {
    UExpression routeExpression = callExpression.getArgumentForParameter(0);
    if (routeExpression == null) return true;

    PsiElement callSourcePsi = callExpression.getSourcePsi();
    Set<String> parentUrlPaths = getParentUrlPaths(callSourcePsi);
    for (HttpRouteTarget target : getHttpRouteTargets(routeExpression)) {
      Collection<String> explicitMethods = target.explicitMethods;
      HelidonRequestMethods requestMethod = routeMethod.getRequestMethod(explicitMethods);
      Collection<String> methods = getStoredExplicitMethods(requestMethod, explicitMethods);
      if (target.pathExpression != null) {
        if (!processExpressions(processor, requestMethod, target.pathExpression, methods, parentUrlPaths)) return false;
      }
      else if (target.defaultPath != null &&
               !processTarget(processor,
                              target.sourcePsi,
                              target.defaultPath,
                              requestMethod,
                              methods,
                              parentUrlPaths)) {
        return false;
      }
    }
    return true;
  }

  private static @Nullable Collection<String> getRouteExplicitMethods(@NotNull RouteMethod routeMethod,
                                                                      @NotNull UCallExpression callExpression) {
    if (routeMethod.methodArgumentIndex < 0) return null;
    UExpression methodsExpression = callExpression.getArgumentForParameter(routeMethod.methodArgumentIndex);
    return getExplicitMethods(methodsExpression);
  }

  private static @Nullable Collection<String> getExplicitMethods(@Nullable UExpression methodsExpression) {
    if (methodsExpression == null) return null;

    PsiElement sourcePsi = methodsExpression.getSourcePsi();
    if (sourcePsi instanceof PsiExpression) {
      return getExplicitMethods((PsiExpression)sourcePsi);
    }

    String expressionText = getUExpressionText(methodsExpression);
    if (expressionText != null) {
      return createMethodSet(expressionText);
    }
    return null;
  }

  private static @NotNull HelidonRequestMethods getRequestMethodFromExplicitMethods(@Nullable Collection<String> explicitMethods) {
    if (explicitMethods == null || explicitMethods.isEmpty()) return HelidonRequestMethods.UNKNOWN;
    if (explicitMethods.size() > 1) return HelidonRequestMethods.ANY_OF;
    return HelidonRequestMethods.getTypeByMethodName(explicitMethods.iterator().next().toLowerCase(Locale.ENGLISH));
  }

  private static @Nullable Collection<String> getStoredExplicitMethods(@NotNull HelidonRequestMethods requestMethod,
                                                                       @Nullable Collection<String> explicitMethods) {
    if (explicitMethods == null || explicitMethods.isEmpty()) return null;
    if (requestMethod == HelidonRequestMethods.UNKNOWN ||
        requestMethod == HelidonRequestMethods.ANY_OF ||
        explicitMethods.size() > 1) {
      return explicitMethods;
    }
    return null;
  }

  private static boolean processExpressions(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                            @NotNull HelidonRequestMethods requestMethods,
                                            @NotNull UExpression expression,
                                            @Nullable Collection<String> explicitMethods) {
    return processExpressions(processor, requestMethods, expression, explicitMethods, getParentUrlPaths(expression.getSourcePsi()));
  }

  private static boolean processExpressions(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                            @NotNull HelidonRequestMethods requestMethods,
                                            @NotNull UExpression expression,
                                            @Nullable Collection<String> explicitMethods,
                                            @NotNull Set<String> parentUrlPaths) {
    return processExpressions(processor,
                              requestMethods,
                              expression,
                              explicitMethods,
                              parentUrlPaths,
                              HelidonUrlTargetInfo.PathSemantics.PATTERN,
                              null);
  }

  private static boolean processExpressions(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                            @NotNull HelidonRequestMethods requestMethods,
                                            @NotNull UExpression expression,
                                            @Nullable Collection<String> explicitMethods,
                                            @NotNull Set<String> parentUrlPaths,
                                            @NotNull HelidonUrlTargetInfo.PathSemantics pathSemantics,
                                            @Nullable String pathDefinition) {
    PsiElement javaPsi = expression.getJavaPsi();
    if (javaPsi instanceof PsiExpression) {
      PathMatcherFactoryPath pathMatcherPath = getPathMatcherFactoryPath((PsiExpression)javaPsi);
      if (pathMatcherPath != null) {
        UElement uElement = UastContextKt.toUElement(pathMatcherPath.expression);
        if (uElement instanceof UExpression) {
          return processExpressions(processor,
                                    requestMethods,
                                    (UExpression)uElement,
                                    explicitMethods,
                                    parentUrlPaths,
                                    pathMatcherPath.pathSemantics,
                                    pathMatcherPath.pathDefinition);
        }
        return true;
      }
    }

    String expressionText = getUExpressionText(expression);
    if (expressionText != null) {
      if (!processTargets(processor,
                          expression,
                          expressionText,
                          requestMethods,
                          explicitMethods,
                          parentUrlPaths,
                          pathSemantics,
                          pathDefinition)) {
        return false;
      }
    }
    else {
      // if UStringConcatenationsFacade failed to process)))
      if (javaPsi instanceof PsiExpression &&
          !processJavaStringExpressions(processor,
                                        requestMethods,
                                        explicitMethods,
                                        (PsiExpression)javaPsi,
                                        parentUrlPaths,
                                        pathSemantics,
                                        pathDefinition)) {
        return false;
      }
    }
    return true;
  }

  private static @Nullable Collection<String> getExplicitMethods(@NotNull HelidonRequestMethods requestMethods,
                                                                 @NotNull UCallExpression callExpression) {
    if (requestMethods != HelidonRequestMethods.ANY_OF) return null;

    UExpression methodsExpression = callExpression.getArgumentForParameter(0);
    if (methodsExpression == null) return null;

    PsiElement sourcePsi = methodsExpression.getSourcePsi();
    if (sourcePsi instanceof PsiExpression) {
      return getExplicitMethods((PsiExpression)sourcePsi);
    }

    String expressionText = getUExpressionText(methodsExpression);
    if (expressionText != null) {
      return createMethodSet(expressionText);
    }
    return null;
  }

  private static @Nullable Set<String> getExplicitMethods(@NotNull PsiExpression expression) {
    if (expression instanceof PsiParenthesizedExpression) {
      PsiExpression parenthesizedExpression = ((PsiParenthesizedExpression)expression).getExpression();
      return parenthesizedExpression == null ? null : getExplicitMethods(parenthesizedExpression);
    }
    if (expression instanceof PsiTypeCastExpression) {
      PsiExpression operand = ((PsiTypeCastExpression)expression).getOperand();
      return operand == null ? null : getExplicitMethods(operand);
    }
    if (expression instanceof PsiLiteralExpression) {
      return createMethodSet(((PsiLiteralExpression)expression).getValue());
    }
    if (expression instanceof PsiReferenceExpression) {
      String method = getStaticMethodName(expression);
      if (method != null) return createMethodSet(method);

      PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
      if (resolved instanceof PsiVariable) {
        PsiExpression initializer = ((PsiVariable)resolved).getInitializer();
        if (initializer != null) {
          return getExplicitMethods(initializer);
        }
      }
      return null;
    }
    if (expression instanceof PsiMethodCallExpression) {
      PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)expression;
      Set<String> methods = getExplicitMethodCallMethods(methodCallExpression);
      if (methods != null) return methods;
      String method = getStaticRequestMethodName(methodCallExpression);
      return method == null ? null : createMethodSet(method);
    }
    if (expression instanceof PsiArrayInitializerExpression) {
      return getExplicitMethods(((PsiArrayInitializerExpression)expression).getInitializers());
    }
    if (expression instanceof PsiNewExpression) {
      PsiArrayInitializerExpression arrayInitializer = ((PsiNewExpression)expression).getArrayInitializer();
      return arrayInitializer == null ? null : getExplicitMethods(arrayInitializer.getInitializers());
    }
    return null;
  }

  private static @Nullable Set<String> getExplicitMethodCallMethods(@NotNull PsiMethodCallExpression expression) {
    String methodName = expression.getMethodExpression().getReferenceName();
    PsiMethod method = expression.resolveMethod();
    PsiClass containingClass = method == null ? null : method.getContainingClass();
    String containingClassName = containingClass == null ? null : containingClass.getQualifiedName();
    if (HelidonConstants.HTTP_METHOD.equals(containingClassName) && "predicate".equals(methodName)) {
      PsiExpression[] arguments = expression.getArgumentList().getExpressions();
      return arguments.length == 1 ? getExplicitMethods(arguments[0]) : getExplicitMethods(arguments);
    }
    if (!isSupportedMethodCollectionFactory(containingClassName, methodName)) {
      return null;
    }

    return getExplicitMethods(expression.getArgumentList().getExpressions());
  }

  private static boolean isSupportedMethodCollectionFactory(@Nullable String className, @Nullable String methodName) {
    if ("of".equals(methodName)) {
      return JAVA_UTIL_LIST.equals(className) ||
             JAVA_UTIL_SET.equals(className) ||
             JAVA_UTIL_ENUM_SET.equals(className);
    }
    if ("asList".equals(methodName)) {
      return JAVA_UTIL_ARRAYS.equals(className);
    }
    if ("singleton".equals(methodName) || "singletonList".equals(methodName)) {
      return JAVA_UTIL_COLLECTIONS.equals(className);
    }
    return false;
  }

  private static @Nullable Set<String> getExplicitMethods(@NotNull PsiExpression[] expressions) {
    Set<String> methods = new LinkedHashSet<>();
    for (PsiExpression expression : expressions) {
      String method = getStaticMethodName(expression);
      if (method == null) return null;

      method = method.trim();
      if (method.isEmpty()) return null;
      methods.add(method.toUpperCase(Locale.ENGLISH));
    }
    return methods.isEmpty() ? null : methods;
  }

  private static @NotNull Collection<HttpRouteTarget> getHttpRouteTargets(@NotNull UExpression routeExpression) {
    PsiElement sourcePsi = routeExpression.getSourcePsi();
    if (!(sourcePsi instanceof PsiExpression)) return Collections.emptyList();
    return getHttpRouteTargets((PsiExpression)sourcePsi, new HashSet<>());
  }

  private static @NotNull Collection<HttpRouteTarget> getHttpRouteTargets(@Nullable PsiExpression expression,
                                                                          @NotNull Set<PsiElement> stack) {
    expression = unwrapExpression(expression);
    if (expression == null || !stack.add(expression)) return Collections.emptyList();
    try {
      if (expression instanceof PsiMethodReferenceExpression) {
        return getHttpRouteTargetsFromMethodReference((PsiMethodReferenceExpression)expression, stack);
      }

      if (expression instanceof PsiReferenceExpression) {
        PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
        if (resolved instanceof PsiVariable) {
          return getHttpRouteTargets(((PsiVariable)resolved).getInitializer(), stack);
        }
        return Collections.emptyList();
      }

      if (expression instanceof PsiLambdaExpression) {
        return getHttpRouteTargetsFromLambda((PsiLambdaExpression)expression, stack);
      }

      if (!(expression instanceof PsiMethodCallExpression)) return Collections.emptyList();

      PsiMethodCallExpression callExpression = (PsiMethodCallExpression)expression;
      PsiMethod method = callExpression.resolveMethod();
      if (method == null) return Collections.emptyList();

      if (getRouteObjectFactoryMethodPattern().accepts(method)) {
        return getLegacyHttpRouteFactoryTarget(callExpression);
      }

      HttpRouteBuilderInfo builderInfo = collectHttpRouteBuilderInfo(callExpression, new HashSet<>());
      if (builderInfo != null) {
        return builderInfo.toTargets();
      }
      PsiType callType = callExpression.getType();
      if (callType != null && isExpandableHttpRouteHelperType(callType, method.getProject())) {
        return getHttpRouteTargetsFromMethod(method, stack);
      }
      return Collections.emptyList();
    }
    finally {
      stack.remove(expression);
    }
  }

  private static @NotNull Collection<HttpRouteTarget> getHttpRouteTargetsFromLambda(@NotNull PsiLambdaExpression lambdaExpression,
                                                                                   @NotNull Set<PsiElement> stack) {
    PsiElement body = lambdaExpression.getBody();
    if (body instanceof PsiExpression) {
      return getHttpRouteTargets((PsiExpression)body, stack);
    }
    if (body instanceof PsiCodeBlock) {
      List<HttpRouteTarget> result = new ArrayList<>();
      for (PsiStatement statement : ((PsiCodeBlock)body).getStatements()) {
        if (statement instanceof PsiReturnStatement) {
          result.addAll(getHttpRouteTargets(((PsiReturnStatement)statement).getReturnValue(), stack));
        }
      }
      return result;
    }
    return Collections.emptyList();
  }

  private static @NotNull Collection<HttpRouteTarget> getHttpRouteTargetsFromMethodReference(@NotNull PsiMethodReferenceExpression methodReference,
                                                                                             @NotNull Set<PsiElement> stack) {
    PsiElement resolved = methodReference.resolve();
    if (!(resolved instanceof PsiMethod)) return Collections.emptyList();

    return getHttpRouteTargetsFromMethod((PsiMethod)resolved, stack);
  }

  private static @NotNull Collection<HttpRouteTarget> getHttpRouteTargetsFromMethod(@NotNull PsiMethod method,
                                                                                    @NotNull Set<PsiElement> stack) {
    PsiCodeBlock body = method.getBody();
    if (body == null) return Collections.emptyList();

    List<HttpRouteTarget> result = new ArrayList<>();
    for (PsiStatement statement : body.getStatements()) {
      if (statement instanceof PsiReturnStatement) {
        result.addAll(getHttpRouteTargets(((PsiReturnStatement)statement).getReturnValue(), stack));
      }
    }
    return result;
  }

  private static @NotNull Collection<HttpRouteTarget> getLegacyHttpRouteFactoryTarget(@NotNull PsiMethodCallExpression callExpression) {
    PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();
    if (arguments.length < 2) return Collections.emptyList();
    UElement uElement = UastContextKt.toUElement(arguments[1]);
    if (!(uElement instanceof UExpression)) return Collections.emptyList();
    UExpression pathExpression = (UExpression)uElement;
    return Collections.singletonList(new HttpRouteTarget(pathExpression, null, arguments[1], getExplicitMethods(arguments[0])));
  }

  private static @Nullable HttpRouteBuilderInfo collectHttpRouteBuilderInfo(@Nullable PsiExpression expression,
                                                                            @NotNull Set<PsiElement> stack) {
    expression = unwrapExpression(expression);
    if (expression == null || !stack.add(expression)) return null;
    try {
      if (expression instanceof PsiReferenceExpression) {
        PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
        if (resolved instanceof PsiVariable) {
          return collectHttpRouteBuilderInfo(((PsiVariable)resolved).getInitializer(), stack);
        }
        return null;
      }
      if (!(expression instanceof PsiMethodCallExpression)) return null;

      PsiMethodCallExpression callExpression = (PsiMethodCallExpression)expression;
      PsiMethod method = callExpression.resolveMethod();
      if (method == null) return null;

      String methodName = callExpression.getMethodExpression().getReferenceName();
      if ("builder".equals(methodName) && method.getParameterList().getParametersCount() == 0 && isHttpRouteType(method.getContainingClass())) {
        return new HttpRouteBuilderInfo();
      }

      HttpRouteBuilderInfo info = collectHttpRouteBuilderInfo(callExpression.getMethodExpression().getQualifierExpression(), stack);
      if (info == null) return null;

      PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();
      if ("path".equals(methodName) && arguments.length == 1 && isHttpRouteBuilderMethod(method)) {
        UElement uElement = UastContextKt.toUElement(arguments[0]);
        if (uElement instanceof UExpression) {
          info.pathExpression = (UExpression)uElement;
          info.sourcePsi = arguments[0];
        }
      }
      else if ("handler".equals(methodName) && arguments.length == 1 && isHttpRouteBuilderMethod(method)) {
        info.hasHandler = true;
        if (info.sourcePsi == null) {
          info.sourcePsi = arguments[0];
        }
      }
      else if ("methods".equals(methodName) && arguments.length > 0 && isHttpRouteBuilderMethod(method)) {
        info.explicitMethods = arguments.length == 1 ? getExplicitMethods(arguments[0]) : getExplicitMethods(arguments);
      }
      return info;
    }
    finally {
      stack.remove(expression);
    }
  }

  private static boolean isHttpRouteBuilderMethod(@NotNull PsiMethod method) {
    PsiClass containingClass = method.getContainingClass();
    return containingClass != null && HelidonConstants.HTTP_ROUTE_BUILDER.equals(containingClass.getQualifiedName());
  }

  private static boolean isHttpRouteType(@Nullable PsiClass psiClass) {
    return psiClass != null && HelidonConstants.HTTP_ROUTE.equals(psiClass.getQualifiedName());
  }

  private static boolean isExpandableHttpRouteHelperType(@NotNull PsiType type, @NotNull Project project) {
    return isAssignableToAny(type,
                             project,
                             HelidonConstants.HTTP_ROUTE,
                             HelidonConstants.LEGACY_HTTP_ROUTE,
                             HelidonConstants.HTTP_ROUTE_BUILDER,
                             JAVA_UTIL_FUNCTION_SUPPLIER);
  }

  public static @Nullable PsiExpression unwrapExpression(@Nullable PsiExpression expression) {
    PsiExpression current = expression;
    while (current instanceof PsiParenthesizedExpression || current instanceof PsiTypeCastExpression) {
      if (current instanceof PsiParenthesizedExpression) {
        current = ((PsiParenthesizedExpression)current).getExpression();
      }
      else {
        current = ((PsiTypeCastExpression)current).getOperand();
      }
    }
    return current;
  }

  private static @Nullable Set<String> createMethodSet(@Nullable Object value) {
    if (!(value instanceof String)) return null;

    String method = ((String)value).trim();
    if (method.isEmpty()) return null;
    return Collections.singleton(method.toUpperCase(Locale.ENGLISH));
  }

  private static @Nullable String getStaticMethodName(@NotNull PsiExpression expression) {
    if (expression instanceof PsiParenthesizedExpression) {
      PsiExpression parenthesizedExpression = ((PsiParenthesizedExpression)expression).getExpression();
      return parenthesizedExpression == null ? null : getStaticMethodName(parenthesizedExpression);
    }
    if (expression instanceof PsiTypeCastExpression) {
      PsiExpression operand = ((PsiTypeCastExpression)expression).getOperand();
      return operand == null ? null : getStaticMethodName(operand);
    }
    if (expression instanceof PsiLiteralExpression) {
      Object value = ((PsiLiteralExpression)expression).getValue();
      return value instanceof String ? (String)value : null;
    }
    if (expression instanceof PsiReferenceExpression) {
      PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
      if (resolved instanceof PsiVariable) {
        PsiExpression initializer = ((PsiVariable)resolved).getInitializer();
        if (initializer != null) {
          String method = getStaticMethodName(initializer);
          if (method != null) return method;
        }
      }
      if (resolved instanceof PsiEnumConstant) {
        return ((PsiEnumConstant)resolved).getName();
      }
      if (resolved instanceof PsiField &&
          isBuiltInRequestMethodField((PsiField)resolved) &&
          isRequestMethodType(((PsiField)resolved).getType())) {
        return ((PsiField)resolved).getName();
      }
    }
    if (expression instanceof PsiMethodCallExpression) {
      String requestMethod = getStaticRequestMethodName((PsiMethodCallExpression)expression);
      if (requestMethod != null) {
        return requestMethod;
      }
    }

    Pair<PsiElement, String> pair = StringExpressionHelper.evaluateExpression(expression);
    return pair == null ? null : pair.second;
  }

  private static boolean isBuiltInRequestMethodField(@NotNull PsiField field) {
    PsiClass containingClass = field.getContainingClass();
    String className = containingClass == null ? null : containingClass.getQualifiedName();
    return HELIDON_HTTP_METHOD.equals(className) ||
           HELIDON_HTTP_METHODS.equals(className) ||
           HELIDON_COMMON_HTTP_REQUEST_METHOD.equals(className);
  }

  private static @Nullable String getStaticRequestMethodName(@NotNull PsiMethodCallExpression expression) {
    PsiReferenceExpression methodExpression = expression.getMethodExpression();
    String methodName = methodExpression.getReferenceName();
    PsiExpression[] arguments = expression.getArgumentList().getExpressions();
    if ("create".equals(methodName) && arguments.length == 1 && isRequestMethodType(expression.getType())) {
      return getStaticMethodName(arguments[0]);
    }
    if ("name".equals(methodName) && arguments.length == 0) {
      PsiExpression qualifier = methodExpression.getQualifierExpression();
      if (qualifier != null && isRequestMethodType(qualifier.getType())) {
        return getStaticMethodName(qualifier);
      }
    }
    return null;
  }

  private static boolean isRequestMethodType(@Nullable PsiType type) {
    if (!(type instanceof PsiClassType)) return false;
    PsiClass resolved = ((PsiClassType)type).resolve();
    String className = resolved == null ? null : resolved.getQualifiedName();
    if (className == null) return false;
    if (HELIDON_COMMON_HTTP_REQUEST_METHOD.equals(className) ||
        HELIDON_HTTP_METHOD.equals(className) ||
        HELIDON_HTTP_METHODS.equals(className)) {
      return true;
    }
    for (PsiType superType : type.getSuperTypes()) {
      if (superType instanceof PsiClassType) {
        PsiClass superClass = ((PsiClassType)superType).resolve();
        if (superClass != null && HELIDON_COMMON_HTTP_REQUEST_METHOD.equals(superClass.getQualifiedName())) {
          return true;
        }
      }
    }
    return false;
  }

  private static @Nullable String getUExpressionText(UExpression expression) {
    return UastUtils.evaluateString(expression);
  }

  private static boolean processJavaStringExpressions(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                      @NotNull HelidonRequestMethods requestMethods,
                                                      @Nullable Collection<String> explicitMethods,
                                                      @NotNull PsiExpression expression,
                                                      @NotNull Set<String> parentUrlPaths,
                                                      @NotNull HelidonUrlTargetInfo.PathSemantics pathSemantics,
                                                      @Nullable String pathDefinition) {
    PathMatcherFactoryPath pathMatcherPath = getPathMatcherFactoryPath(expression);
    if (pathMatcherPath != null) {
      UElement uElement = UastContextKt.toUElement(pathMatcherPath.expression);
      if (uElement instanceof UExpression) {
        return processExpressions(processor,
                                  requestMethods,
                                  (UExpression)uElement,
                                  explicitMethods,
                                  parentUrlPaths,
                                  pathMatcherPath.pathSemantics,
                                  pathMatcherPath.pathDefinition);
      }
    }

    Pair<PsiElement, String> pair = StringExpressionHelper.evaluateExpression(expression);
    if (pair != null) {
      UElement uElement = UastContextKt.toUElement(pair.first);
      if (uElement instanceof UExpression &&
          !processTargets(processor,
                          (UExpression)uElement,
                          pair.second,
                          requestMethods,
                          explicitMethods,
                          parentUrlPaths,
                          pathSemantics,
                          pathDefinition)) {
        return false;
      }
    }
    return true;
  }

  public static boolean isPathMatcherFactoryCall(@NotNull PsiExpression expression) {
    return getPathMatcherFactoryCall(expression) != null;
  }

  private static @Nullable PathMatcherFactoryPath getPathMatcherFactoryPath(@NotNull PsiExpression expression) {
    PsiMethodCallExpression callExpression = getPathMatcherFactoryCall(expression);
    if (callExpression == null) return null;

    PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();
    if (arguments.length != 1) return null;

    PsiMethod method = callExpression.resolveMethod();
    PsiClass containingClass = method == null ? null : method.getContainingClass();
    String className = containingClass == null ? null : containingClass.getQualifiedName();
    String methodName = callExpression.getMethodExpression().getReferenceName();
    boolean prefixPathMatcher = isPrefixPathMatcherFactory(className, methodName, arguments[0]);
    String prefixPathDefinition = prefixPathMatcher ? getPrefixPathMatcherDefinition(className, methodName, arguments[0]) : null;
    HelidonUrlTargetInfo.PathSemantics pathSemantics = prefixPathMatcher
                                                       ? HelidonUrlTargetInfo.PathSemantics.PREFIX
                                                       : getPathMatcherPathSemantics(className, methodName, arguments[0]);
    return new PathMatcherFactoryPath(arguments[0], pathSemantics, prefixPathDefinition);
  }

  public static @Nullable PsiExpression getPathMatcherFactoryPattern(@NotNull PsiExpression expression) {
    PsiMethodCallExpression callExpression = getPathMatcherFactoryCall(expression);
    if (callExpression == null) return null;

    PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();
    if (arguments.length != 1) return null;

    PsiMethod method = callExpression.resolveMethod();
    PsiClass containingClass = method == null ? null : method.getContainingClass();
    String className = containingClass == null ? null : containingClass.getQualifiedName();
    String methodName = callExpression.getMethodExpression().getReferenceName();
    return isVariableBearingPathMatcherFactory(className, methodName, arguments[0]) ? arguments[0] : null;
  }

  private static @Nullable PsiMethodCallExpression getPathMatcherFactoryCall(@NotNull PsiExpression expression) {
    expression = unwrapExpression(expression);
    if (!(expression instanceof PsiMethodCallExpression)) return null;

    PsiMethodCallExpression callExpression = (PsiMethodCallExpression)expression;
    PsiMethod method = callExpression.resolveMethod();
    PsiClass containingClass = method == null ? null : method.getContainingClass();
    String className = containingClass == null ? null : containingClass.getQualifiedName();
    String methodName = callExpression.getMethodExpression().getReferenceName();
    return isPathMatcherFactory(className, methodName) ? callExpression : null;
  }

  private static boolean isVariableBearingPathMatcherFactory(@Nullable String className,
                                                             @Nullable String methodName,
                                                             @NotNull PsiExpression argument) {
    if (HelidonConstants.LEGACY_PATH_MATCHER.equals(className)) {
      return "create".equals(methodName);
    }
    if (!HelidonConstants.HTTP_PATH_MATCHERS.equals(className)) return false;
    if ("pattern".equals(methodName)) return true;
    return "create".equals(methodName) && isPatternPathMatcherCreateArgument(argument);
  }

  private static @NotNull HelidonUrlTargetInfo.PathSemantics getPathMatcherPathSemantics(@Nullable String className,
                                                                                         @Nullable String methodName,
                                                                                         @NotNull PsiExpression argument) {
    return isVariableBearingPathMatcherFactory(className, methodName, argument)
           ? HelidonUrlTargetInfo.PathSemantics.PATTERN
           : HelidonUrlTargetInfo.PathSemantics.LITERAL;
  }

  private static @Nullable String getPrefixPathMatcherDefinition(@Nullable String className,
                                                                 @Nullable String methodName,
                                                                 @NotNull PsiExpression argument) {
    if (!HelidonConstants.HTTP_PATH_MATCHERS.equals(className)) return null;

    if ("prefix".equals(methodName)) {
      String path = getStaticPathMatcherArgument(argument);
      return path == null ? null : normalizePrefixPathDefinition(path);
    }
    if (!"create".equals(methodName)) return null;

    String path = getStaticPathMatcherArgument(argument);
    if (path == null || !path.endsWith("/*")) return null;

    String checkPath = path.substring(0, path.length() - 2);
    if (containsPathMatcherPatternSyntax(checkPath)) return null;
    return normalizePrefixPathDefinition(checkPath);
  }

  private static boolean isPrefixPathMatcherFactory(@Nullable String className,
                                                    @Nullable String methodName,
                                                    @NotNull PsiExpression argument) {
    if (!HelidonConstants.HTTP_PATH_MATCHERS.equals(className)) return false;
    if ("prefix".equals(methodName)) return true;
    if (!"create".equals(methodName)) return false;
    return getPrefixPathMatcherDefinition(className, methodName, argument) != null;
  }

  private static boolean isPatternPathMatcherCreateArgument(@NotNull PsiExpression argument) {
    String path = getStaticPathMatcherArgument(argument);
    if (path == null) return false;

    String checkPath = path.endsWith("/*") ? path.substring(0, path.length() - 2) : path;
    return containsPathMatcherPatternSyntax(checkPath);
  }

  private static @Nullable String getStaticPathMatcherArgument(@NotNull PsiExpression argument) {
    Pair<PsiElement, String> evaluated = StringExpressionHelper.evaluateExpression(argument);
    return evaluated == null ? null : evaluated.second;
  }

  private static boolean containsPathMatcherPatternSyntax(@NotNull String path) {
    return path.contains("{") ||
           path.contains("[") ||
           path.contains("*") ||
           path.contains("\\");
  }

  private static @NotNull String normalizePrefixPathDefinition(@NotNull String path) {
    if (path.isEmpty() || "/".equals(path)) return "/";
    while (path.endsWith("/") && path.length() > 1) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  private static boolean isPathMatcherFactory(@Nullable String className, @Nullable String methodName) {
    if (HelidonConstants.LEGACY_PATH_MATCHER.equals(className)) {
      return "create".equals(methodName);
    }
    if (!HelidonConstants.HTTP_PATH_MATCHERS.equals(className)) return false;
    return "create".equals(methodName) ||
           "exact".equals(methodName) ||
           "prefix".equals(methodName) ||
           "pattern".equals(methodName);
  }

  private static boolean isInsideHttpServiceClass(@NotNull UCallExpression callExpression) {
    PsiElement sourcePsi = callExpression.getSourcePsi();
    if (sourcePsi == null) return false;
    PsiClass psiClass = PsiTreeUtil.getParentOfType(sourcePsi, PsiClass.class);
    return psiClass != null && isHelidonHttpServiceClass(psiClass);
  }

  private static boolean processTargets(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                        @NotNull UExpression resolveTo,
                                        @NotNull String url,
                                        HelidonRequestMethods requestMethods,
                                        @Nullable Collection<String> explicitMethods,
                                        @NotNull Set<String> parentUrlPaths) {
    return processTargets(processor,
                          resolveTo,
                          url,
                          requestMethods,
                          explicitMethods,
                          parentUrlPaths,
                          HelidonUrlTargetInfo.PathSemantics.PATTERN,
                          null);
  }

  private static boolean processTargets(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                        @NotNull UExpression resolveTo,
                                        @NotNull String url,
                                        HelidonRequestMethods requestMethods,
                                        @Nullable Collection<String> explicitMethods,
                                        @NotNull Set<String> parentUrlPaths,
                                        @NotNull HelidonUrlTargetInfo.PathSemantics pathSemantics,
                                        @Nullable String pathDefinition) {

    PsiElement psiElement = resolveTo.getSourcePsi();
    return psiElement == null ||
           processTarget(processor, psiElement, url, requestMethods, explicitMethods, parentUrlPaths, pathSemantics, pathDefinition);
  }

  private static boolean processTarget(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                       @NotNull PsiElement psiElement,
                                       @NotNull String url,
                                       HelidonRequestMethods requestMethods,
                                       @Nullable Collection<String> explicitMethods,
                                       @NotNull Set<String> parentUrlPaths) {
    return processTarget(processor,
                         psiElement,
                         url,
                         requestMethods,
                         explicitMethods,
                         parentUrlPaths,
                         HelidonUrlTargetInfo.PathSemantics.PATTERN,
                         null);
  }

  private static boolean processTarget(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                       @NotNull PsiElement psiElement,
                                       @NotNull String url,
                                       HelidonRequestMethods requestMethods,
                                       @Nullable Collection<String> explicitMethods,
                                       @NotNull Set<String> parentUrlPaths,
                                       @NotNull HelidonUrlTargetInfo.PathSemantics pathSemantics,
                                       @Nullable String pathDefinition) {
    if (parentUrlPaths.isEmpty()) {
      return processor.process(createTargetInfo(url, psiElement, requestMethods, explicitMethods, pathSemantics, pathDefinition));
    }
    for (String parentUrl : parentUrlPaths) {
      if (!processor.process(createTargetInfo(url, psiElement, requestMethods, explicitMethods, pathSemantics, pathDefinition)
                               .withParentUrl(parentUrl))) {
        return false;
      }
    }
    return true;
  }

  private static @NotNull HelidonUrlTargetInfo createTargetInfo(@NotNull String url,
                                                                @NotNull PsiElement psiElement,
                                                                @NotNull HelidonRequestMethods requestMethods,
                                                                @Nullable Collection<String> explicitMethods) {
    return createTargetInfo(url, psiElement, requestMethods, explicitMethods, HelidonUrlTargetInfo.PathSemantics.PATTERN, null);
  }

  private static @NotNull HelidonUrlTargetInfo createTargetInfo(@NotNull String url,
                                                                @NotNull PsiElement psiElement,
                                                                @NotNull HelidonRequestMethods requestMethods,
                                                                @Nullable Collection<String> explicitMethods,
                                                                @NotNull HelidonUrlTargetInfo.PathSemantics pathSemantics,
                                                                @Nullable String pathDefinition) {
    HelidonUrlTargetInfo targetInfo = HelidonUrlTargetInfo.create(url, psiElement).ofType(requestMethods);
    if (explicitMethods != null) {
      targetInfo.withMethods(explicitMethods);
    }
    if (pathSemantics == HelidonUrlTargetInfo.PathSemantics.LITERAL) {
      targetInfo.withLiteralPath();
    }
    else if (pathSemantics == HelidonUrlTargetInfo.PathSemantics.PREFIX) {
      targetInfo.withPrefixPath(pathDefinition);
    }
    return targetInfo;
  }

  private static @Nullable RouteMethod createRouteMethod(@NotNull PsiMethod method) {
    if (isHelidonRouteObjectRegistrationMethod(method)) {
      return new RouteMethod(method, HelidonRequestMethods.UNKNOWN, -1, -1, null, true, true);
    }
    if (getRegisterMethodPattern().accepts(method)) return null;

    int pathArgumentIndex = getHelidonRoutePathArgumentIndex(method);
    int methodArgumentIndex = getHelidonRouteMethodArgumentIndex(method);
    boolean pathless = isHelidonPathlessRouteMethod(method);
    if (pathArgumentIndex < 0 && !pathless) return null;

    HelidonRequestMethods requestMethod = methodArgumentIndex >= 0
                                          ? HelidonRequestMethods.UNKNOWN
                                          : HelidonRequestMethods.getTypeByMethodName(method.getName());
    if (getAnyOfMethodPattern().accepts(method)) {
      requestMethod = HelidonRequestMethods.ANY_OF;
    }
    boolean requestMethodFromArgument = methodArgumentIndex >= 0 && requestMethod != HelidonRequestMethods.ANY_OF;
    return new RouteMethod(method, requestMethod, pathArgumentIndex, methodArgumentIndex, pathless ? "/" : null, false, requestMethodFromArgument);
  }

  private static @NotNull Collection<RouteMethod> getRulesHttpMethods(@NotNull Module module) {
    return CachedValuesManager.getManager(module.getProject())
      .getCachedValue(module, () -> Result.createSingleDependency(getHttpMethods(module,
                                                                                 HelidonConstants.HTTP_RULES,
                                                                                 HelidonConstants.ROUTING_RULES),
                                                                  JavaLibraryModificationTracker.getInstance(module.getProject())));
  }

  private static @NotNull Collection<RouteMethod> getBuilderHttpMethods(@NotNull Module module) {
    return CachedValuesManager.getManager(module.getProject())
      .getCachedValue(module, () -> Result.createSingleDependency(getHttpMethods(module,
                                                                                 HelidonConstants.HTTP_ROUTING_BUILDER,
                                                                                 HelidonConstants.ROUTING_BUILDER),
                                                                  JavaLibraryModificationTracker.getInstance(module.getProject())));
  }

  private static @NotNull Collection<RouteMethod> getHttpMethods(@NotNull Module module,
                                                                 @NotNull String... containerClasses) {
    Set<RouteMethod> methods = new HashSet<>();
    for (String containerClass : containerClasses) {
      PsiClass routingBuilderClass = findClass(module, containerClass);

      if (routingBuilderClass == null) continue;

      Arrays.stream(routingBuilderClass.getAllMethods())
        .map(HelidonCommonUtils::createRouteMethod)
        .filter(Objects::nonNull)
        .forEach(methods::add);
    }
    return methods;
  }

  private static @NotNull Set<PsiMethod> getBuilderRegisterMethod(@NotNull Module module) {
    return CachedValuesManager.getManager(module.getProject())
      .getCachedValue(module, () -> Result.createSingleDependency(getRegisterMethod(module),
                                                                  JavaLibraryModificationTracker.getInstance(module.getProject())));
  }

  private static @NotNull Set<PsiMethod> getRegisterMethod(@NotNull Module module) {
    Set<PsiMethod> methods = new HashSet<>();
    String[] registerClasses = {
      HelidonConstants.HTTP_RULES,
      HelidonConstants.HTTP_ROUTING_BUILDER,
      HelidonConstants.ROUTING_RULES,
      HelidonConstants.ROUTING_BUILDER
    };

    for (String registerClass : registerClasses) {

      PsiClass routingBuilderClass = findClass(module, registerClass);

      if (routingBuilderClass == null) continue;
      for (PsiMethod psiMethod : routingBuilderClass.getAllMethods()) {
        if (getRegisterMethodPattern().accepts(psiMethod)) {
          methods.add(psiMethod);
        }
      }
    }
    return methods;
  }

  public static @NotNull GlobalSearchScope getRoutingClassReferencesScope(@NotNull Module module) {
    return CachedValuesManager.getManager(module.getProject())
      .getCachedValue(module, () -> Result.create(calculateRoutingClassReferencesScope(module),
                                                  UastModificationTracker.getInstance(module.getProject()),
                                                  JavaLibraryModificationTracker.getInstance(module.getProject())));
  }

  private static GlobalSearchScope calculateRoutingClassReferencesScope(@NotNull Module module) {
    Set<VirtualFile> virtualFiles = new HashSet<>();
    for (String routingReferenceClass : getRoutingReferenceClasses()) {
      PsiClass routingClass = findClass(module, routingReferenceClass);
      if (routingClass == null) continue;
      ReferencesSearch.search(routingClass, module.getModuleWithDependenciesScope()).forEach(reference -> {
        PsiFile containingFile = reference.getElement().getContainingFile();
        if (containingFile != null && containingFile.getVirtualFile() != null) {
          virtualFiles.add(containingFile.getVirtualFile());
        }
        return true;
      });
    }
    return virtualFiles.isEmpty() ? GlobalSearchScope.EMPTY_SCOPE : GlobalSearchScope.filesScope(module.getProject(), virtualFiles);
  }

  private static @NotNull List<String> getRoutingReferenceClasses() {
    return Arrays.asList(HelidonConstants.WEB_SERVER,
                         HelidonConstants.WEB_SERVER_CONFIG,
                         HelidonConstants.WEB_SERVER_CONFIG_BUILDER,
                         HelidonConstants.LISTENER_CONFIG,
                         HelidonConstants.LISTENER_CONFIG_BUILDER,
                         HelidonConstants.HTTP_ROUTING,
                         HelidonConstants.HTTP_ROUTING_BUILDER,
                         HelidonConstants.HTTP_RULES,
                         HelidonConstants.HTTP_SERVICE,
                         HelidonConstants.REST_SERVER_ENDPOINT,
                         HelidonConstants.HTTP_PATH,
                         HelidonConstants.ROUTING,
                         HelidonConstants.ROUTING_BUILDER,
                         HelidonConstants.ROUTING_RULES,
                         HelidonConstants.SERVICE,
                         HelidonConstants.SERVICE_REGISTRY_SERVICE);
  }

  private static final class RouteMethod {
    private final PsiMethod method;
    private final HelidonRequestMethods requestMethod;
    private final int pathArgumentIndex;
    private final int methodArgumentIndex;
    private final @Nullable String defaultPath;
    private final boolean routeObjectRegistration;
    private final boolean requestMethodFromArgument;

    private RouteMethod(@NotNull PsiMethod method,
                        @NotNull HelidonRequestMethods requestMethod,
                        int pathArgumentIndex,
                        int methodArgumentIndex,
                        @Nullable String defaultPath,
                        boolean routeObjectRegistration,
                        boolean requestMethodFromArgument) {
      this.method = method;
      this.requestMethod = requestMethod;
      this.pathArgumentIndex = pathArgumentIndex;
      this.methodArgumentIndex = methodArgumentIndex;
      this.defaultPath = defaultPath;
      this.routeObjectRegistration = routeObjectRegistration;
      this.requestMethodFromArgument = requestMethodFromArgument;
    }

    private @NotNull HelidonRequestMethods getRequestMethod(@Nullable Collection<String> explicitMethods) {
      return requestMethodFromArgument ? getRequestMethodFromExplicitMethods(explicitMethods) : requestMethod;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof RouteMethod)) return false;
      RouteMethod other = (RouteMethod)obj;
      return method.equals(other.method) &&
             requestMethod == other.requestMethod &&
             pathArgumentIndex == other.pathArgumentIndex &&
             methodArgumentIndex == other.methodArgumentIndex &&
             Objects.equals(defaultPath, other.defaultPath) &&
             routeObjectRegistration == other.routeObjectRegistration &&
             requestMethodFromArgument == other.requestMethodFromArgument;
    }

    @Override
    public int hashCode() {
      return Objects.hash(method,
                          requestMethod,
                          pathArgumentIndex,
                          methodArgumentIndex,
                          defaultPath,
                          routeObjectRegistration,
                          requestMethodFromArgument);
    }
  }

  private static final class HttpRouteTarget {
    private final @Nullable UExpression pathExpression;
    private final @Nullable String defaultPath;
    private final PsiElement sourcePsi;
    private final @Nullable Collection<String> explicitMethods;

    private HttpRouteTarget(@Nullable UExpression pathExpression,
                            @Nullable String defaultPath,
                            @NotNull PsiElement sourcePsi,
                            @Nullable Collection<String> explicitMethods) {
      this.pathExpression = pathExpression;
      this.defaultPath = defaultPath;
      this.sourcePsi = sourcePsi;
      this.explicitMethods = explicitMethods;
    }
  }

  private static final class HttpRouteBuilderInfo {
    private @Nullable UExpression pathExpression;
    private @Nullable PsiElement sourcePsi;
    private @Nullable Collection<String> explicitMethods;
    private boolean hasHandler;

    private @NotNull Collection<HttpRouteTarget> toTargets() {
      if (sourcePsi == null || !hasHandler) return Collections.emptyList();
      if (pathExpression == null) {
        return Collections.singletonList(new HttpRouteTarget(null, "/", sourcePsi, explicitMethods));
      }
      return Collections.singletonList(new HttpRouteTarget(pathExpression, null, sourcePsi, explicitMethods));
    }
  }

  private static final class PathMatcherFactoryPath {
    private final PsiExpression expression;
    private final HelidonUrlTargetInfo.PathSemantics pathSemantics;
    private final @Nullable String pathDefinition;

    private PathMatcherFactoryPath(@NotNull PsiExpression expression,
                                   @NotNull HelidonUrlTargetInfo.PathSemantics pathSemantics,
                                   @Nullable String pathDefinition) {
      this.expression = expression;
      this.pathSemantics = pathSemantics;
      this.pathDefinition = pathDefinition;
    }
  }

  private static final class PathDefinition {
    private final String path;
    private final @Nullable PsiElement source;

    private PathDefinition(@NotNull String path, @Nullable PsiElement source) {
      this.path = path;
      this.source = source;
    }
  }

  private static final class ServiceRegistration {
    private final String urlDefinition;
    private final UExpression urlExpression;
    private final UExpression serviceExpression;
    private final Collection<PsiType> serviceTypes;

    private ServiceRegistration(@NotNull String urlDefinition,
                                @NotNull UExpression urlExpression,
                                @NotNull UExpression serviceExpression,
                                @NotNull Collection<PsiType> serviceTypes) {
      this.urlDefinition = urlDefinition;
      this.urlExpression = urlExpression;
      this.serviceExpression = serviceExpression;
      this.serviceTypes = serviceTypes;
    }

    private boolean accepts(@NotNull PsiType serviceType) {
      for (PsiType registeredServiceType : serviceTypes) {
        if (registeredServiceType.isAssignableFrom(serviceType)) return true;
      }
      return false;
    }
  }

  private static @Nullable PsiClass findClass(@NotNull Module module, @NotNull String className) {
    return JavaPsiFacade.getInstance(module.getProject()).findClass(className, module.getModuleRuntimeScope(true));
  }
}
