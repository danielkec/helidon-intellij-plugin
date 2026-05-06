// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.utils;

import com.intellij.codeInspection.dataFlow.StringExpressionHelper;
import com.intellij.helidon.constants.HelidonConstants;
import com.intellij.helidon.providers.HelidonRequestMethods;
import com.intellij.java.library.JavaLibraryModificationTracker;
import com.intellij.java.library.JavaLibraryUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.RecursionManager;
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
  private static final Set<String> HELIDON_SERVICE_SCOPE_ANNOTATIONS = Set.of(HelidonConstants.SERVICE_SINGLETON,
                                                                               HelidonConstants.SERVICE_PROVIDER,
                                                                               HelidonConstants.SERVICE_PER_LOOKUP,
                                                                               HelidonConstants.SERVICE_PER_REQUEST,
                                                                               HelidonConstants.REST_SERVER_ENDPOINT);
  private static final Map<String, String> HTTP_METHOD_ANNOTATIONS = Map.of(HelidonConstants.HTTP_GET, "GET",
                                                                            HelidonConstants.HTTP_HEAD, "HEAD",
                                                                            HelidonConstants.HTTP_POST, "POST",
                                                                            HelidonConstants.HTTP_PUT, "PUT",
                                                                            HelidonConstants.HTTP_PATCH, "PATCH",
                                                                            HelidonConstants.HTTP_DELETE, "DELETE",
                                                                            HelidonConstants.HTTP_OPTIONS, "OPTIONS");
  private static final Set<String> SERVICE_LOOKUP_METHODS = Set.of("get",
                                                                   "getNamed",
                                                                   "first",
                                                                   "firstNamed",
                                                                   "all",
                                                                   "supply",
                                                                   "supplyFirst",
                                                                   "supplyAll");
  private static final Key<CachedValue<Map<SearchScope, Set<UCallExpression>>>> METHOD_INVOCATIONS_KEY =
    Key.create("METHOD_INVOCATIONS_KEY");
  private static final Key<CachedValue<List<ServiceRegistration>>> SERVICE_REGISTRATIONS_KEY =
    Key.create("SERVICE_REGISTRATIONS_KEY");

  private HelidonCommonUtils() {
  }

  public static boolean hasHelidonLibrary(Project project) {
    return JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.HTTP_ROUTING) ||
           JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.ROUTING) ||
           JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.REST_SERVER_ENDPOINT) ||
           JavaLibraryUtil.hasLibraryClass(project, HelidonConstants.SERVICE_REGISTRY_SERVICE);
  }

  public static boolean hasHelidonLibrary(@Nullable Module module) {
    return JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.HTTP_ROUTING) ||
           JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.ROUTING) ||
           JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.REST_SERVER_ENDPOINT) ||
           JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.SERVICE_REGISTRY_SERVICE);
  }

  public static boolean hasHelidonMPLibrary(@Nullable Module module) {
    return JavaLibraryUtil.hasLibraryClass(module, HelidonConstants.MP_MAIN);
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
    for (PsiMethod registerMethod : getBuilderRegisterMethod(module)) {
      if (!findAndProcessTargets(processor, scope, registerMethod, HelidonRequestMethods.REGISTER, 0)) {
        return false;
      }
    }
    return true;
  }

  public static boolean processRulesHttpMethods(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                @NotNull SearchScope scope,
                                                @Nullable Module module) {
    if (module == null) return true;
    for (Pair<PsiMethod, HelidonRequestMethods> rulesMethod : getRulesHttpMethods(module)) {
      if (!findAndProcessTargets(processor, scope, rulesMethod.first, rulesMethod.second, getPathArgumentIndex(rulesMethod.second))) {
        return false;
      }
    }
    return true;
  }

  public static boolean processBuilderHttpMethods(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                                  @NotNull SearchScope scope,
                                                  @Nullable Module module) {
    if (module == null) return true;
    for (Pair<PsiMethod, HelidonRequestMethods> rulesMethod : getBuilderHttpMethods(module)) {
      if (!findAndProcessTargets(processor, scope, rulesMethod.first, rulesMethod.second, getPathArgumentIndex(rulesMethod.second), true)) {
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
    return hasAnnotationOrMetaAnnotation(psiClass, HELIDON_SERVICE_SCOPE_ANNOTATIONS, new HashSet<>());
  }

  public static @NotNull Set<PsiElement> getHelidonServiceUsageTargets(@NotNull Module module, @NotNull PsiClass serviceClass) {
    Set<PsiElement> targets = new LinkedHashSet<>();
    for (PsiClass contract : getHelidonServiceContracts(serviceClass)) {
      ReferencesSearch.search(contract, module.getModuleWithDependenciesScope()).forEach(reference -> {
        PsiElement element = reference.getElement();
        PsiElement target = getServiceUsageTarget(element);
        if (target != null && !PsiTreeUtil.isAncestor(serviceClass, target, false)) {
          targets.add(target);
        }
        return true;
      });
    }
    return targets;
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

  private static @NotNull Set<PsiClass> getHelidonServiceContracts(@NotNull PsiClass serviceClass) {
    Set<PsiClass> contracts = new LinkedHashSet<>();
    contracts.add(serviceClass);
    collectAllInterfaces(serviceClass, contracts, new HashSet<>());
    PsiClass superClass = serviceClass.getSuperClass();
    while (superClass != null && !CommonClassNames.JAVA_LANG_OBJECT.equals(superClass.getQualifiedName())) {
      contracts.add(superClass);
      superClass = superClass.getSuperClass();
    }
    collectExternalContractClasses(serviceClass, contracts);
    return contracts;
  }

  private static void collectAllInterfaces(@NotNull PsiClass psiClass,
                                           @NotNull Set<? super PsiClass> result,
                                           @NotNull Set<? super PsiClass> visited) {
    if (!visited.add(psiClass)) return;
    for (PsiClass anInterface : psiClass.getInterfaces()) {
      if (result.add(anInterface)) {
        collectAllInterfaces(anInterface, result, visited);
      }
    }
    PsiClass superClass = psiClass.getSuperClass();
    if (superClass != null && !CommonClassNames.JAVA_LANG_OBJECT.equals(superClass.getQualifiedName())) {
      collectAllInterfaces(superClass, result, visited);
    }
  }

  private static void collectExternalContractClasses(@NotNull PsiClass serviceClass, @NotNull Set<? super PsiClass> contracts) {
    PsiAnnotation annotation = findAnnotation(serviceClass, HelidonConstants.SERVICE_EXTERNAL_CONTRACTS);
    if (annotation == null) return;
    collectClassLiterals(annotation.findDeclaredAttributeValue("value"), contracts);
  }

  private static void collectClassLiterals(@Nullable PsiAnnotationMemberValue value, @NotNull Set<? super PsiClass> result) {
    if (value instanceof PsiArrayInitializerMemberValue) {
      for (PsiAnnotationMemberValue initializer : ((PsiArrayInitializerMemberValue)value).getInitializers()) {
        collectClassLiterals(initializer, result);
      }
      return;
    }
    if (value instanceof PsiClassObjectAccessExpression) {
      PsiTypeElement operand = ((PsiClassObjectAccessExpression)value).getOperand();
      PsiType type = operand.getType();
      if (type instanceof PsiClassType) {
        PsiClass resolved = ((PsiClassType)type).resolve();
        if (resolved != null) {
          result.add(resolved);
        }
      }
    }
  }

  private static @Nullable PsiElement getServiceUsageTarget(@NotNull PsiElement referenceElement) {
    PsiClassObjectAccessExpression classObjectAccess =
      PsiTreeUtil.getParentOfType(referenceElement, PsiClassObjectAccessExpression.class, false);
    if (classObjectAccess != null && isServiceLookupClassLiteral(classObjectAccess)) {
      return classObjectAccess;
    }

    PsiTypeElement typeElement = PsiTreeUtil.getParentOfType(referenceElement, PsiTypeElement.class, false);
    if (typeElement == null) return null;

    PsiField field = PsiTreeUtil.getParentOfType(typeElement, PsiField.class);
    if (field != null && hasAnnotation(field, HelidonConstants.SERVICE_INJECT)) {
      return field.getNameIdentifier() != null ? field.getNameIdentifier() : field;
    }

    PsiParameter parameter = PsiTreeUtil.getParentOfType(typeElement, PsiParameter.class);
    if (parameter != null && isInjectedParameter(parameter)) {
      return parameter.getNameIdentifier() != null ? parameter.getNameIdentifier() : parameter;
    }
    return null;
  }

  private static boolean isServiceLookupClassLiteral(@NotNull PsiClassObjectAccessExpression classObjectAccess) {
    PsiExpressionList expressionList = PsiTreeUtil.getParentOfType(classObjectAccess, PsiExpressionList.class);
    PsiMethodCallExpression methodCall = expressionList == null ? null : PsiTreeUtil.getParentOfType(expressionList, PsiMethodCallExpression.class);
    if (methodCall == null || expressionList.getParent() != methodCall) return false;

    PsiExpression[] expressions = expressionList.getExpressions();
    if (expressions.length == 0 || expressions[0] != classObjectAccess) return false;

    PsiMethod method = methodCall.resolveMethod();
    if (method == null || !SERVICE_LOOKUP_METHODS.contains(method.getName())) return false;

    PsiClass containingClass = method.getContainingClass();
    String containingClassName = containingClass == null ? null : containingClass.getQualifiedName();
    return HelidonConstants.SERVICE_REGISTRY_SERVICES.equals(containingClassName) ||
           HelidonConstants.SERVICE_REGISTRY.equals(containingClassName);
  }

  private static boolean isInjectedParameter(@NotNull PsiParameter parameter) {
    PsiElement parent = parameter.getDeclarationScope();
    return parent instanceof PsiMethod && hasAnnotation((PsiMethod)parent, HelidonConstants.SERVICE_INJECT);
  }

  private static boolean hasAnnotationOrMetaAnnotation(@NotNull PsiModifierListOwner owner,
                                                       @NotNull Set<String> annotations,
                                                       @NotNull Set<? super PsiModifierListOwner> visited) {
    if (!visited.add(owner)) return false;
    for (PsiAnnotation annotation : getAnnotations(owner)) {
      String qualifiedName = annotation.getQualifiedName();
      if (qualifiedName != null && annotations.contains(qualifiedName)) {
        return true;
      }
      PsiClass annotationClass = annotation.resolveAnnotationType();
      if (annotationClass != null && hasAnnotationOrMetaAnnotation(annotationClass, annotations, visited)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasAnnotation(@NotNull PsiModifierListOwner owner, @NotNull String annotationName) {
    return findAnnotation(owner, annotationName) != null;
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

  private static int getPathArgumentIndex(@NotNull HelidonRequestMethods requestMethods) {
    return requestMethods == HelidonRequestMethods.ANY_OF ? 1 : 0;
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

  private static boolean processExpressions(@NotNull Processor<? super HelidonUrlTargetInfo> processor,
                                            @NotNull HelidonRequestMethods requestMethods,
                                            @NotNull UExpression expression,
                                            @Nullable Collection<String> explicitMethods) {
    String expressionText = getUExpressionText(expression);
    if (expressionText != null) {
      if (!processTargets(processor, expression, expressionText, requestMethods, explicitMethods, getParentUrlPaths(expression.getSourcePsi()))) {
        return false;
      }
    }
    else {
      // if UStringConcatenationsFacade failed to process)))
      PsiElement javaPsi = expression.getJavaPsi();
      if (javaPsi instanceof PsiExpression && !processJavaStringExpressions(processor, requestMethods, explicitMethods, (PsiExpression)javaPsi)) {
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
      PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
      if (resolved instanceof PsiVariable) {
        PsiExpression initializer = ((PsiVariable)resolved).getInitializer();
        if (initializer != null) {
          return getExplicitMethods(initializer);
        }
      }
      String method = getStaticMethodName(expression);
      return method == null ? null : createMethodSet(method);
    }
    if (expression instanceof PsiMethodCallExpression) {
      return getExplicitMethodCallMethods((PsiMethodCallExpression)expression);
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
      if (resolved instanceof PsiEnumConstant) {
        return ((PsiEnumConstant)resolved).getName();
      }
      if (resolved instanceof PsiVariable) {
        PsiExpression initializer = ((PsiVariable)resolved).getInitializer();
        if (initializer != null) {
          return getStaticMethodName(initializer);
        }
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
                                                      @NotNull PsiExpression expression) {
    Pair<PsiElement, String> pair = StringExpressionHelper.evaluateExpression(expression);
    if (pair != null) {
      UElement uElement = UastContextKt.toUElement(pair.first);
      if (uElement instanceof UExpression &&
          !processTargets(processor, (UExpression)uElement, pair.second, requestMethods, explicitMethods, getParentUrlPaths(pair.first))) {
        return false;
      }
    }
    return true;
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

    PsiElement psiElement = resolveTo.getSourcePsi();
    if (psiElement == null) return true;
    if (parentUrlPaths.isEmpty()) {
      return processor.process(createTargetInfo(url, psiElement, requestMethods, explicitMethods));
    }
    for (String parentUrl : parentUrlPaths) {
      if (!processor.process(createTargetInfo(url, psiElement, requestMethods, explicitMethods).withParentUrl(parentUrl))) {
        return false;
      }
    }
    return true;
  }

  private static @NotNull HelidonUrlTargetInfo createTargetInfo(@NotNull String url,
                                                                @NotNull PsiElement psiElement,
                                                                @NotNull HelidonRequestMethods requestMethods,
                                                                @Nullable Collection<String> explicitMethods) {
    HelidonUrlTargetInfo targetInfo = HelidonUrlTargetInfo.create(url, psiElement).ofType(requestMethods);
    if (explicitMethods != null) {
      targetInfo.withMethods(explicitMethods);
    }
    return targetInfo;
  }

  private static @NotNull Collection<Pair<PsiMethod, HelidonRequestMethods>> getRulesHttpMethods(@NotNull Module module) {
    return CachedValuesManager.getManager(module.getProject())
      .getCachedValue(module, () -> Result.createSingleDependency(getHttpMethods(module,
                                                                                 HelidonConstants.HTTP_RULES,
                                                                                 HelidonConstants.ROUTING_RULES),
                                                                  JavaLibraryModificationTracker.getInstance(module.getProject())));
  }

  private static @NotNull Collection<Pair<PsiMethod, HelidonRequestMethods>> getBuilderHttpMethods(@NotNull Module module) {
    return CachedValuesManager.getManager(module.getProject())
      .getCachedValue(module, () -> Result.createSingleDependency(getHttpMethods(module,
                                                                                 HelidonConstants.HTTP_ROUTING_BUILDER,
                                                                                 HelidonConstants.ROUTING_BUILDER),
                                                                  JavaLibraryModificationTracker.getInstance(module.getProject())));
  }

  private static @NotNull Collection<Pair<PsiMethod, HelidonRequestMethods>> getHttpMethods(@NotNull Module module,
                                                                                            @NotNull String... containerClasses) {
    Set<Pair<PsiMethod, HelidonRequestMethods>> methods = new HashSet<>();
    for (String containerClass : containerClasses) {
      PsiClass routingBuilderClass = findClass(module, containerClass);

      if (routingBuilderClass == null) continue;

      Arrays.stream(routingBuilderClass.getAllMethods()).filter(method -> {
        return getHttpMethodsPattern().accepts(method) ||
               getAnyOfMethodPattern().accepts(method);
      }).map(method -> Pair.create(method, HelidonRequestMethods.getTypeByMethodName(method.getName()))).forEach(methods::add);
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
