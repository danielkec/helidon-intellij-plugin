// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers;

import com.intellij.helidon.constants.HelidonConstants;
import com.intellij.helidon.utils.HelidonCommonUtils;
import com.intellij.microservices.jvm.pathvars.usages.PathVariableUsageUastReferenceProvider;
import com.intellij.microservices.url.parameters.DefaultPathVariableUsagesProvider;
import com.intellij.microservices.url.parameters.PathVariableDefinitionsSearcher;
import com.intellij.microservices.url.parameters.PathVariablePsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.pom.PomTargetPsiElement;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PartiallyKnownString;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.StringEntry;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.UExpression;

import java.util.*;

import static com.intellij.helidon.providers.HelidonReferenceContributorKt.*;
import static com.intellij.microservices.jvm.pathvars.usages.AnnotationParamSearcherUtils.processPathVariables;

public final class HelidonHttpRequestPathParamReferenceProvider extends PathVariableUsageUastReferenceProvider {
  public static final HelidonHttpRequestPathParamReferenceProvider INSTANCE = new HelidonHttpRequestPathParamReferenceProvider();
  private static final String JAVA_UTIL_FUNCTION_SUPPLIER = "java.util.function.Supplier";
  private static final DefaultPathVariableUsagesProvider PATH_VARIABLE_USAGES_PROVIDER = new DefaultPathVariableUsagesProvider();

  private HelidonHttpRequestPathParamReferenceProvider() {}

  private enum PathParameterLookup {
    NONE,
    HELIDON_PATH_PARAMETERS,
    LEGACY_RELATIVE_PATH,
    LEGACY_ABSOLUTE_PATH;

    private boolean includesParentPathVariables() {
      return this != LEGACY_RELATIVE_PATH;
    }
  }

  @Override
  public PathVariableDefinitionsSearcher getSearcher() {
    return new MyPathVariableDefinitionsSearcher();
  }

  private static class MyPathVariableDefinitionsSearcher implements PathVariableDefinitionsSearcher {
    @Override
    public boolean processDefinitions(@NotNull PsiElement context,
                                      @NotNull Processor<? super PomTargetPsiElement> processor) {
      PathParameterLookup lookup = getPathParameterLookup(context);
      if (lookup == PathParameterLookup.NONE) return true;

      PsiMethod declaration = PsiTreeUtil.getParentOfType(context, PsiMethod.class);
      if (isHandlerMethodCandidate(declaration)) {
        return MethodReferencesSearch.search(declaration, declaration.getResolveScope(), true).forEach(reference -> {
          PsiMethodCallExpression methodCallExpression =
            PsiTreeUtil.getParentOfType(reference.getElement(), PsiMethodCallExpression.class);
          return methodCallExpression == null ||
                 processRoutePathVariables(methodCallExpression,
                                           reference.getElement(),
                                           processor,
                                           lookup.includesParentPathVariables());
        });
      }

      PsiLambdaExpression lambdaExpression = PsiTreeUtil.getParentOfType(context, PsiLambdaExpression.class);
      if (lambdaExpression != null) {
        PsiMethodCallExpression methodCallExpression = PsiTreeUtil.getParentOfType(lambdaExpression, PsiMethodCallExpression.class);
        return methodCallExpression == null ||
               processRoutePathVariables(methodCallExpression,
                                         lambdaExpression,
                                         processor,
                                         lookup.includesParentPathVariables());
      }
      return true;
    }
  }

  private static @NotNull PathParameterLookup getPathParameterLookup(@NotNull PsiElement context) {
    PsiMethodCallExpression methodCallExpression = PsiTreeUtil.getParentOfType(context, PsiMethodCallExpression.class);
    if (methodCallExpression == null) return PathParameterLookup.NONE;
    PsiMethod method = methodCallExpression.resolveMethod();
    if (method == null) return PathParameterLookup.NONE;

    PsiClass containingClass = method.getContainingClass();
    String containingClassName = containingClass != null ? containingClass.getQualifiedName() : null;
    if ("param".equals(method.getName()) && HelidonConstants.HTTP_REQUEST_PATH.equals(containingClassName)) {
      return isAbsoluteLegacyPathExpression(methodCallExpression.getMethodExpression().getQualifierExpression())
             ? PathParameterLookup.LEGACY_ABSOLUTE_PATH
             : PathParameterLookup.LEGACY_RELATIVE_PATH;
    }

    if (!("get".equals(method.getName()) || "first".equals(method.getName())) ||
        !HelidonConstants.HTTP_PARAMETERS.equals(containingClassName)) {
      return PathParameterLookup.NONE;
    }

    return isPathParametersExpression(methodCallExpression.getMethodExpression().getQualifierExpression())
           ? PathParameterLookup.HELIDON_PATH_PARAMETERS
           : PathParameterLookup.NONE;
  }

  private static boolean isPathParametersExpression(@Nullable PsiExpression expression) {
    if (expression == null) return false;
    if (expression instanceof PsiMethodCallExpression) {
      PsiMethod method = ((PsiMethodCallExpression)expression).resolveMethod();
      return method != null &&
             "pathParameters".equals(method.getName()) &&
             isAssignableToAny(method.getReturnType(), method.getProject(), HelidonConstants.HTTP_PARAMETERS);
    }
    if (expression instanceof PsiReferenceExpression) {
      PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
      if (resolved instanceof PsiLocalVariable) {
        return isPathParametersExpression(((PsiLocalVariable)resolved).getInitializer());
      }
    }
    return false;
  }

  private static boolean isAbsoluteLegacyPathExpression(@Nullable PsiExpression expression) {
    expression = HelidonCommonUtils.unwrapExpression(expression);
    if (expression instanceof PsiMethodCallExpression) {
      PsiMethod method = ((PsiMethodCallExpression)expression).resolveMethod();
      return method != null &&
             "absolute".equals(method.getName()) &&
             isAssignableToAny(method.getReturnType(), method.getProject(), HelidonConstants.HTTP_REQUEST_PATH);
    }
    if (expression instanceof PsiReferenceExpression) {
      PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
      if (resolved instanceof PsiLocalVariable) {
        return isAbsoluteLegacyPathExpression(((PsiLocalVariable)resolved).getInitializer());
      }
    }
    return false;
  }

  private static boolean processRoutePathVariables(@NotNull PsiMethodCallExpression methodCallExpression,
                                                   @NotNull PsiElement handlerElement,
                                                   @NotNull Processor<? super PomTargetPsiElement> processor,
                                                   boolean includeParentPathVariables) {
    PsiMethod routeMethod = methodCallExpression.resolveMethod();
    if (routeMethod == null) return true;

    if (isHelidonHttpRouteBuilderHandlerMethod(routeMethod)) {
      return processHttpRouteBuilderHandlerPathVariables(methodCallExpression, processor, includeParentPathVariables);
    }

    if (isHelidonRouteObjectRegistrationMethod(routeMethod)) {
      return processHttpRouteObjectRegistrationPathVariables(methodCallExpression, handlerElement, processor);
    }

    if (isHttpRouteFactoryMethod(routeMethod) && findRouteObjectRegistrationCall(methodCallExpression.getMethodExpression()) != null) {
      return processHttpRouteHelperPathVariables(methodCallExpression, handlerElement, processor, includeParentPathVariables);
    }

    int pathArgumentIndex = getHelidonRoutePathArgumentIndex(routeMethod);
    if (pathArgumentIndex < 0) {
      return !includeParentPathVariables ||
             !isHelidonPathlessRouteMethod(routeMethod) ||
             processParentRoutePathVariables(methodCallExpression, processor);
    }

    PsiExpression[] expressions = methodCallExpression.getArgumentList().getExpressions();
    if (expressions.length <= pathArgumentIndex ||
        !processPathExpressionVariableDefinitions(expressions[pathArgumentIndex], processor)) {
      return false;
    }
    if (!includeParentPathVariables) return true;
    return processParentRoutePathVariables(methodCallExpression, processor) &&
           processHttpRouteFactoryCallSiteParentPathVariables(methodCallExpression, processor);
  }

  private static boolean processParentRoutePathVariables(@NotNull PsiMethodCallExpression methodCallExpression,
                                                         @NotNull Processor<? super PomTargetPsiElement> processor) {
    for (UExpression parentPathExpression : HelidonCommonUtils.getParentUrlPathExpressions(methodCallExpression)) {
      PsiElement sourcePsi = parentPathExpression.getSourcePsi();
      if (sourcePsi != null && !processPathVariableDefinitions(sourcePsi, processor)) {
        return false;
      }
    }
    return true;
  }

  private static boolean processHttpRouteBuilderHandlerPathVariables(@NotNull PsiMethodCallExpression methodCallExpression,
                                                                     @NotNull Processor<? super PomTargetPsiElement> processor,
                                                                     boolean includeParentPathVariables) {
    PsiExpression pathExpression = findHttpRouteBuilderPathExpression(getHttpRouteBuilderChainExpression(methodCallExpression));
    if (pathExpression != null) {
      if (!processPathExpressionVariableDefinitions(pathExpression, processor)) {
        return false;
      }
      if (!processHttpRouteFactoryCallSitePathVariables(methodCallExpression, pathExpression, processor)) {
        return false;
      }
    }
    if (!includeParentPathVariables) return true;
    return processParentRoutePathVariables(methodCallExpression, processor) &&
           processHttpRouteFactoryCallSiteParentPathVariables(methodCallExpression, processor);
  }

  private static boolean processHttpRouteObjectRegistrationPathVariables(@NotNull PsiMethodCallExpression methodCallExpression,
                                                                         @NotNull PsiElement handlerElement,
                                                                         @NotNull Processor<? super PomTargetPsiElement> processor) {
    PsiExpression[] arguments = methodCallExpression.getArgumentList().getExpressions();
    if (arguments.length != 1) return true;
    return processHttpRouteExpressionPathVariables(arguments[0],
                                                   Collections.emptyMap(),
                                                   handlerElement,
                                                   processor,
                                                   new HashSet<>(),
                                                   new HashSet<>());
  }

  private static boolean processHttpRouteHelperPathVariables(@NotNull PsiMethodCallExpression methodCallExpression,
                                                             @NotNull PsiElement handlerElement,
                                                             @NotNull Processor<? super PomTargetPsiElement> processor,
                                                             boolean includeParentPathVariables) {
    PsiMethod helperMethod = methodCallExpression.resolveMethod();
    if (helperMethod == null) return true;

    if (!processHttpRouteHelperMethodPathVariables(helperMethod,
                                                   getMethodSubstitutions(helperMethod, methodCallExpression, Collections.emptyMap()),
                                                   handlerElement,
                                                   processor,
                                                   new HashSet<>())) {
      return false;
    }
    if (!includeParentPathVariables) return true;

    PsiMethodCallExpression registrationCall = findRouteObjectRegistrationCall(methodCallExpression.getMethodExpression());
    return registrationCall == null || processParentRoutePathVariables(registrationCall, processor);
  }

  private static boolean processHttpRouteHelperMethodPathVariables(@NotNull PsiMethod method,
                                                                   @NotNull Map<PsiParameter, PsiExpression> substitutions,
                                                                   @NotNull PsiElement handlerElement,
                                                                   @NotNull Processor<? super PomTargetPsiElement> processor,
                                                                   @NotNull Set<PsiMethod> stack) {
    PsiCodeBlock body = method.getBody();
    if (body == null || !stack.add(method)) return true;

    try {
      for (PsiStatement statement : body.getStatements()) {
        if (statement instanceof PsiReturnStatement &&
            !processHttpRouteExpressionPathVariables(((PsiReturnStatement)statement).getReturnValue(),
                                                     substitutions,
                                                     handlerElement,
                                                     processor,
                                                     new HashSet<>(),
                                                     stack)) {
          return false;
        }
      }
      return true;
    }
    finally {
      stack.remove(method);
    }
  }

  private static boolean processHttpRouteExpressionPathVariables(@Nullable PsiExpression expression,
                                                                 @NotNull Map<PsiParameter, PsiExpression> substitutions,
                                                                 @NotNull PsiElement handlerElement,
                                                                 @NotNull Processor<? super PomTargetPsiElement> processor,
                                                                 @NotNull Set<PsiElement> expressionStack,
                                                                 @NotNull Set<PsiMethod> methodStack) {
    expression = substituteExpression(expression, substitutions);
    expression = HelidonCommonUtils.unwrapExpression(expression);
    if (expression == null || !expressionStack.add(expression)) return true;

    try {
      if (expression instanceof PsiReferenceExpression) {
        PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
        if (resolved instanceof PsiVariable) {
          return processHttpRouteExpressionPathVariables(((PsiVariable)resolved).getInitializer(),
                                                        substitutions,
                                                        handlerElement,
                                                        processor,
                                                        expressionStack,
                                                        methodStack);
        }
        return true;
      }

      if (expression instanceof PsiLambdaExpression) {
        return processHttpRouteLambdaPathVariables((PsiLambdaExpression)expression,
                                                   substitutions,
                                                   handlerElement,
                                                   processor,
                                                   expressionStack,
                                                   methodStack);
      }

      if (!(expression instanceof PsiMethodCallExpression)) return true;

      PsiMethodCallExpression callExpression = (PsiMethodCallExpression)expression;
      PsiMethod method = callExpression.resolveMethod();
      if (method == null) return true;

      if (isLegacyHttpRouteFactoryMethod(method)) {
        PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();
        if (arguments.length >= 3 && matchesHandlerExpression(arguments[2], substitutions, handlerElement)) {
          return processPathExpressionVariableDefinitions(arguments[1], substitutions, processor);
        }
        return true;
      }

      HttpRouteBuilderPathInfo builderInfo =
        collectMatchingHttpRouteBuilderPathInfo(callExpression, substitutions, handlerElement, new HashSet<>(), methodStack);
      if (builderInfo != null) {
        return !builderInfo.hasMatchingHandler ||
               builderInfo.pathExpression == null ||
               processPathExpressionVariableDefinitions(builderInfo.pathExpression, substitutions, processor);
      }

      PsiType callType = callExpression.getType();
      if (callType != null && isAssignableToAny(callType,
                                                method.getProject(),
                                                HelidonConstants.HTTP_ROUTE,
                                                HelidonConstants.LEGACY_HTTP_ROUTE,
                                                HelidonConstants.HTTP_ROUTE_BUILDER,
                                                JAVA_UTIL_FUNCTION_SUPPLIER)) {
        return processHttpRouteHelperMethodPathVariables(method,
                                                         getMethodSubstitutions(method, callExpression, substitutions),
                                                         handlerElement,
                                                         processor,
                                                         methodStack);
      }
      return true;
    }
    finally {
      expressionStack.remove(expression);
    }
  }

  private static boolean processHttpRouteLambdaPathVariables(@NotNull PsiLambdaExpression lambdaExpression,
                                                             @NotNull Map<PsiParameter, PsiExpression> substitutions,
                                                             @NotNull PsiElement handlerElement,
                                                             @NotNull Processor<? super PomTargetPsiElement> processor,
                                                             @NotNull Set<PsiElement> expressionStack,
                                                             @NotNull Set<PsiMethod> methodStack) {
    PsiElement body = lambdaExpression.getBody();
    if (body instanceof PsiExpression) {
      return processHttpRouteExpressionPathVariables((PsiExpression)body,
                                                     substitutions,
                                                     handlerElement,
                                                     processor,
                                                     expressionStack,
                                                     methodStack);
    }
    if (!(body instanceof PsiCodeBlock)) return true;

    for (PsiStatement statement : ((PsiCodeBlock)body).getStatements()) {
      if (statement instanceof PsiReturnStatement &&
          !processHttpRouteExpressionPathVariables(((PsiReturnStatement)statement).getReturnValue(),
                                                   substitutions,
                                                   handlerElement,
                                                   processor,
                                                   expressionStack,
                                                   methodStack)) {
        return false;
      }
    }
    return true;
  }

  private static @Nullable HttpRouteBuilderPathInfo collectMatchingHttpRouteBuilderPathInfo(
    @Nullable PsiExpression expression,
    @NotNull Map<PsiParameter, PsiExpression> substitutions,
    @NotNull PsiElement handlerElement,
    @NotNull Set<PsiElement> expressionStack,
    @NotNull Set<PsiMethod> methodStack) {
    expression = substituteExpression(expression, substitutions);
    expression = HelidonCommonUtils.unwrapExpression(expression);
    if (expression == null || !expressionStack.add(expression)) return null;

    try {
      if (expression instanceof PsiReferenceExpression) {
        PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
        if (resolved instanceof PsiVariable) {
          return collectMatchingHttpRouteBuilderPathInfo(((PsiVariable)resolved).getInitializer(),
                                                        substitutions,
                                                        handlerElement,
                                                        expressionStack,
                                                        methodStack);
        }
        return null;
      }
      if (!(expression instanceof PsiMethodCallExpression)) return null;

      PsiMethodCallExpression callExpression = (PsiMethodCallExpression)expression;
      PsiMethod method = callExpression.resolveMethod();
      if (method == null) return null;

      String methodName = callExpression.getMethodExpression().getReferenceName();
      if ("builder".equals(methodName) &&
          method.getParameterList().getParametersCount() == 0 &&
          HelidonConstants.HTTP_ROUTE.equals(getContainingClassName(method))) {
        return new HttpRouteBuilderPathInfo();
      }

      HttpRouteBuilderPathInfo info = collectMatchingHttpRouteBuilderPathInfo(callExpression.getMethodExpression().getQualifierExpression(),
                                                                              substitutions,
                                                                              handlerElement,
                                                                              expressionStack,
                                                                              methodStack);
      if (info == null) {
        PsiType callType = callExpression.getType();
        if (callType != null && isAssignableToAny(callType, method.getProject(), HelidonConstants.HTTP_ROUTE_BUILDER)) {
          return collectMatchingHttpRouteBuilderPathInfoFromMethod(method,
                                                                  getMethodSubstitutions(method, callExpression, substitutions),
                                                                  handlerElement,
                                                                  methodStack);
        }
        return null;
      }

      PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();
      if ("path".equals(methodName) && arguments.length == 1 && isHttpRouteBuilderMethod(method)) {
        PsiExpression pathMatcherPattern = HelidonCommonUtils.getPathMatcherFactoryPattern(arguments[0]);
        info.pathExpression = pathMatcherPattern != null ? pathMatcherPattern : arguments[0];
      }
      else if ("handler".equals(methodName) && arguments.length == 1 && isHttpRouteBuilderMethod(method)) {
        info.hasMatchingHandler = matchesHandlerExpression(arguments[0], substitutions, handlerElement);
      }
      return info;
    }
    finally {
      expressionStack.remove(expression);
    }
  }

  private static @Nullable HttpRouteBuilderPathInfo collectMatchingHttpRouteBuilderPathInfoFromMethod(
    @NotNull PsiMethod method,
    @NotNull Map<PsiParameter, PsiExpression> substitutions,
    @NotNull PsiElement handlerElement,
    @NotNull Set<PsiMethod> methodStack) {
    PsiCodeBlock body = method.getBody();
    if (body == null || !methodStack.add(method)) return null;

    try {
      for (PsiStatement statement : body.getStatements()) {
        if (statement instanceof PsiReturnStatement) {
          HttpRouteBuilderPathInfo info =
            collectMatchingHttpRouteBuilderPathInfo(((PsiReturnStatement)statement).getReturnValue(),
                                                    substitutions,
                                                    handlerElement,
                                                    new HashSet<>(),
                                                    methodStack);
          if (info != null) return info;
        }
      }
      return null;
    }
    finally {
      methodStack.remove(method);
    }
  }

  private static boolean processHttpRouteFactoryCallSitePathVariables(@NotNull PsiMethodCallExpression methodCallExpression,
                                                                      @NotNull PsiExpression pathExpression,
                                                                      @NotNull Processor<? super PomTargetPsiElement> processor) {
    PsiMethod factoryMethod = PsiTreeUtil.getParentOfType(methodCallExpression, PsiMethod.class);
    if (!referencesMethodParameter(pathExpression, factoryMethod)) return true;
    return processHttpRouteFactoryMethodCallSitePathVariables(factoryMethod, pathExpression, processor, new HashSet<>());
  }

  private static boolean processHttpRouteFactoryMethodCallSitePathVariables(@Nullable PsiMethod factoryMethod,
                                                                            @NotNull PsiExpression pathExpression,
                                                                            @NotNull Processor<? super PomTargetPsiElement> processor,
                                                                            @NotNull Set<PsiMethod> stack) {
    if (!isHttpRouteFactoryMethod(factoryMethod) || !stack.add(factoryMethod)) return true;

    try {
      return MethodReferencesSearch.search(factoryMethod, factoryMethod.getResolveScope(), true).forEach(reference -> {
        PsiElement referenceElement = reference.getElement();
        PsiMethodCallExpression helperCall = PsiTreeUtil.getParentOfType(referenceElement, PsiMethodCallExpression.class);
        if (helperCall == null || !factoryMethod.equals(helperCall.resolveMethod())) return true;

        Map<PsiParameter, PsiExpression> substitutions = getMethodSubstitutions(factoryMethod,
                                                                                helperCall,
                                                                                Collections.emptyMap());
        PsiExpression substitutedPathExpression = substituteExpression(pathExpression, substitutions);
        if (substitutedPathExpression == null) return true;

        if (findRouteObjectRegistrationCall(referenceElement) != null ||
            isRouteObjectVariableRegistered(referenceElement)) {
          return processPathExpressionVariableDefinitions(substitutedPathExpression, processor);
        }

        PsiMethod callerMethod = PsiTreeUtil.getParentOfType(referenceElement, PsiMethod.class);
        return callerMethod == null ||
               !isReturnedRouteHelperReference(referenceElement, callerMethod) ||
               processHttpRouteFactoryMethodCallSitePathVariables(callerMethod,
                                                                  substitutedPathExpression,
                                                                  processor,
                                                                  stack);
      });
    }
    finally {
      stack.remove(factoryMethod);
    }
  }

  private static boolean matchesHandlerExpression(@NotNull PsiExpression expression,
                                                  @NotNull Map<PsiParameter, PsiExpression> substitutions,
                                                  @NotNull PsiElement handlerElement) {
    PsiExpression substitutedExpression = substituteExpression(expression, substitutions);
    return substitutedExpression != null && isSameReference(substitutedExpression, handlerElement);
  }

  private static @NotNull Map<PsiParameter, PsiExpression> getMethodSubstitutions(@NotNull PsiMethod method,
                                                                                  @NotNull PsiMethodCallExpression callExpression,
                                                                                  @NotNull Map<PsiParameter, PsiExpression> substitutions) {
    PsiParameter[] parameters = method.getParameterList().getParameters();
    PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();
    if (parameters.length == 0 || arguments.length == 0) return Collections.emptyMap();

    Map<PsiParameter, PsiExpression> result = new HashMap<>();
    int count = Math.min(parameters.length, arguments.length);
    for (int i = 0; i < count; i++) {
      PsiExpression argument = substituteExpression(arguments[i], substitutions);
      if (argument != null) {
        result.put(parameters[i], argument);
      }
    }
    return result.isEmpty() ? Collections.emptyMap() : result;
  }

  private static @Nullable PsiExpression substituteExpression(@Nullable PsiExpression expression,
                                                              @NotNull Map<PsiParameter, PsiExpression> substitutions) {
    expression = HelidonCommonUtils.unwrapExpression(expression);
    if (expression == null || substitutions.isEmpty() || !(expression instanceof PsiReferenceExpression)) {
      return expression;
    }

    PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
    if (resolved instanceof PsiParameter) {
      PsiExpression substituted = substitutions.get(resolved);
      return substituted == null ? expression : HelidonCommonUtils.unwrapExpression(substituted);
    }
    return expression;
  }

  private static boolean referencesMethodParameter(@NotNull PsiExpression expression, @Nullable PsiMethod method) {
    if (method == null) return false;
    expression = HelidonCommonUtils.unwrapExpression(expression);
    if (expression instanceof PsiReferenceExpression && isMethodParameterReference((PsiReferenceExpression)expression, method)) {
      return true;
    }
    for (PsiReferenceExpression referenceExpression : PsiTreeUtil.findChildrenOfType(expression, PsiReferenceExpression.class)) {
      if (isMethodParameterReference(referenceExpression, method)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isMethodParameterReference(@NotNull PsiReferenceExpression expression, @NotNull PsiMethod method) {
    PsiElement resolved = expression.resolve();
    return resolved instanceof PsiParameter &&
           PsiTreeUtil.isAncestor(method.getParameterList(), resolved, true);
  }

  private static boolean isRouteObjectVariableRegistered(@NotNull PsiElement referenceElement) {
    PsiLocalVariable localVariable =
      PsiTreeUtil.getParentOfType(referenceElement, PsiLocalVariable.class, true, PsiLambdaExpression.class, PsiClass.class);
    if (localVariable == null ||
        !PsiTreeUtil.isAncestor(localVariable.getInitializer(), referenceElement, false)) {
      return false;
    }

    boolean[] found = {false};
    ReferencesSearch.search(localVariable, localVariable.getUseScope()).forEach(reference -> {
      if (findRouteObjectRegistrationCall(reference.getElement()) != null) {
        found[0] = true;
        return false;
      }
      return true;
    });
    return found[0];
  }

  private static boolean isLegacyHttpRouteFactoryMethod(@NotNull PsiMethod method) {
    return "route".equals(method.getName()) &&
           HelidonConstants.LEGACY_HTTP_ROUTE.equals(getContainingClassName(method));
  }

  private static boolean isHttpRouteBuilderMethod(@NotNull PsiMethod method) {
    return HelidonConstants.HTTP_ROUTE_BUILDER.equals(getContainingClassName(method));
  }

  private static boolean processHttpRouteFactoryCallSiteParentPathVariables(@NotNull PsiMethodCallExpression methodCallExpression,
                                                                            @NotNull Processor<? super PomTargetPsiElement> processor) {
    PsiMethod factoryMethod = PsiTreeUtil.getParentOfType(methodCallExpression, PsiMethod.class);
    return processHttpRouteFactoryMethodCallSiteParentPathVariables(factoryMethod, processor, new HashSet<>());
  }

  private static boolean processHttpRouteFactoryMethodCallSiteParentPathVariables(@Nullable PsiMethod factoryMethod,
                                                                                  @NotNull Processor<? super PomTargetPsiElement> processor,
                                                                                  @NotNull Set<PsiMethod> stack) {
    if (!isHttpRouteFactoryMethod(factoryMethod) || !stack.add(factoryMethod)) return true;

    try {
      return MethodReferencesSearch.search(factoryMethod, factoryMethod.getResolveScope(), true).forEach(reference -> {
        PsiElement referenceElement = reference.getElement();
        PsiMethodCallExpression routeCallExpression = findRouteObjectRegistrationCall(referenceElement);
        if (routeCallExpression != null) {
          return processParentRoutePathVariables(routeCallExpression, processor);
        }

        Boolean variableRegistrationResult =
          processRouteObjectVariableRegistrationCallSiteParentPathVariables(referenceElement, processor);
        if (variableRegistrationResult != null) {
          return variableRegistrationResult;
        }

        PsiMethod callerMethod = PsiTreeUtil.getParentOfType(referenceElement, PsiMethod.class);
        return callerMethod == null ||
               !isReturnedRouteHelperReference(referenceElement, callerMethod) ||
               processHttpRouteFactoryMethodCallSiteParentPathVariables(callerMethod, processor, stack);
      });
    }
    finally {
      stack.remove(factoryMethod);
    }
  }

  private static boolean isReturnedRouteHelperReference(@NotNull PsiElement referenceElement, @NotNull PsiMethod method) {
    PsiReturnStatement returnStatement =
      PsiTreeUtil.getParentOfType(referenceElement, PsiReturnStatement.class, true, PsiLambdaExpression.class, PsiClass.class);
    PsiMethod returnOwner = returnStatement == null
                            ? null
                            : PsiTreeUtil.getParentOfType(returnStatement, PsiMethod.class, false, PsiLambdaExpression.class, PsiClass.class);
    if (returnStatement != null && returnOwner == method) {
      PsiExpression returnValue = returnStatement.getReturnValue();
      return returnValue != null && PsiTreeUtil.isAncestor(returnValue, referenceElement, false);
    }

    PsiLocalVariable localVariable =
      PsiTreeUtil.getParentOfType(referenceElement, PsiLocalVariable.class, true, PsiLambdaExpression.class, PsiClass.class);
    if (localVariable == null ||
        !PsiTreeUtil.isAncestor(method, localVariable, true) ||
        !PsiTreeUtil.isAncestor(localVariable.getInitializer(), referenceElement, false)) {
      return false;
    }
    return isLocalVariableReturned(localVariable, method);
  }

  private static boolean isLocalVariableReturned(@NotNull PsiLocalVariable localVariable, @NotNull PsiMethod method) {
    PsiCodeBlock body = method.getBody();
    if (body == null) return false;

    for (PsiReturnStatement returnStatement : PsiTreeUtil.findChildrenOfType(body, PsiReturnStatement.class)) {
      PsiMethod owner = PsiTreeUtil.getParentOfType(returnStatement, PsiMethod.class, false, PsiLambdaExpression.class, PsiClass.class);
      if (owner != method) continue;

      PsiExpression returnValue = HelidonCommonUtils.unwrapExpression(returnStatement.getReturnValue());
      if (returnValue instanceof PsiReferenceExpression &&
          ((PsiReferenceExpression)returnValue).resolve() == localVariable) {
        return true;
      }
    }
    return false;
  }

  private static boolean isHttpRouteFactoryMethod(@Nullable PsiMethod method) {
    if (method == null) return false;
    return isAssignableToAny(method.getReturnType(),
                             method.getProject(),
                             HelidonConstants.HTTP_ROUTE,
                             HelidonConstants.LEGACY_HTTP_ROUTE,
                             HelidonConstants.HTTP_ROUTE_BUILDER,
                             JAVA_UTIL_FUNCTION_SUPPLIER);
  }

  private static @Nullable PsiMethodCallExpression findRouteObjectRegistrationCall(@NotNull PsiElement element) {
    PsiElement current = element;
    while (true) {
      PsiMethodCallExpression callExpression = PsiTreeUtil.getParentOfType(current, PsiMethodCallExpression.class, true);
      if (callExpression == null) return null;

      PsiMethod method = callExpression.resolveMethod();
      if (method != null &&
          isHelidonRouteObjectRegistrationMethod(method) &&
          isDirectRouteObjectRegistrationArgument(callExpression, element)) {
        return callExpression;
      }
      current = callExpression;
    }
  }

  private static @Nullable Boolean processRouteObjectVariableRegistrationCallSiteParentPathVariables(
    @NotNull PsiElement referenceElement,
    @NotNull Processor<? super PomTargetPsiElement> processor) {
    PsiLocalVariable localVariable =
      PsiTreeUtil.getParentOfType(referenceElement, PsiLocalVariable.class, true, PsiLambdaExpression.class, PsiClass.class);
    if (localVariable == null ||
        !PsiTreeUtil.isAncestor(localVariable.getInitializer(), referenceElement, false)) {
      return null;
    }

    boolean[] found = {false};
    boolean processed = ReferencesSearch.search(localVariable, localVariable.getUseScope()).forEach(reference -> {
      PsiMethodCallExpression routeCallExpression = findRouteObjectRegistrationCall(reference.getElement());
      if (routeCallExpression == null) return true;

      found[0] = true;
      return processParentRoutePathVariables(routeCallExpression, processor);
    });
    return found[0] ? processed : null;
  }

  private static boolean isDirectRouteObjectRegistrationArgument(@NotNull PsiMethodCallExpression routeCallExpression,
                                                                 @NotNull PsiElement referenceElement) {
    PsiExpression[] arguments = routeCallExpression.getArgumentList().getExpressions();
    if (arguments.length != 1) return false;

    PsiExpression routeArgument = HelidonCommonUtils.unwrapExpression(arguments[0]);
    return isDirectRouteObjectExpression(routeArgument, referenceElement);
  }

  private static boolean isDirectRouteObjectExpression(@Nullable PsiExpression routeArgument, @NotNull PsiElement referenceElement) {
    if (routeArgument instanceof PsiMethodCallExpression) {
      return isSameReference(((PsiMethodCallExpression)routeArgument).getMethodExpression(), referenceElement);
    }
    if (routeArgument instanceof PsiMethodReferenceExpression) {
      return isSameReference(routeArgument, referenceElement);
    }
    if (routeArgument instanceof PsiLambdaExpression) {
      return isDirectRouteSupplierLambdaReference((PsiLambdaExpression)routeArgument, referenceElement);
    }
    if (routeArgument instanceof PsiReferenceExpression) {
      if (isSameReference(routeArgument, referenceElement)) return true;

      PsiElement resolved = ((PsiReferenceExpression)routeArgument).resolve();
      if (resolved instanceof PsiVariable) {
        return isDirectRouteObjectExpression(((PsiVariable)resolved).getInitializer(), referenceElement);
      }
    }
    return false;
  }

  private static boolean isDirectRouteSupplierLambdaReference(@NotNull PsiLambdaExpression lambdaExpression,
                                                              @NotNull PsiElement referenceElement) {
    PsiElement body = lambdaExpression.getBody();
    if (body instanceof PsiExpression) {
      return isDirectRouteObjectExpression((PsiExpression)body, referenceElement);
    }
    if (!(body instanceof PsiCodeBlock)) return false;

    for (PsiStatement statement : ((PsiCodeBlock)body).getStatements()) {
      if (statement instanceof PsiReturnStatement &&
          isDirectRouteObjectExpression(((PsiReturnStatement)statement).getReturnValue(), referenceElement)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSameReference(@NotNull PsiElement routeReferenceElement, @NotNull PsiElement referenceElement) {
    return PsiTreeUtil.isAncestor(routeReferenceElement, referenceElement, false) ||
           PsiTreeUtil.isAncestor(referenceElement, routeReferenceElement, false);
  }

  private static @NotNull PsiMethodCallExpression getHttpRouteBuilderChainExpression(@NotNull PsiMethodCallExpression methodCallExpression) {
    PsiMethodCallExpression result = methodCallExpression;
    PsiElement current = methodCallExpression;
    while (true) {
      PsiMethodCallExpression parent = PsiTreeUtil.getParentOfType(current, PsiMethodCallExpression.class, true);
      if (parent == null) return result;

      PsiMethod method = parent.resolveMethod();
      if (method == null || !HelidonConstants.HTTP_ROUTE_BUILDER.equals(getContainingClassName(method))) return result;

      PsiExpression qualifier = parent.getMethodExpression().getQualifierExpression();
      if (qualifier == null || !PsiTreeUtil.isAncestor(qualifier, result, false)) return result;

      result = parent;
      current = parent;
    }
  }

  private static @Nullable PsiExpression findHttpRouteBuilderPathExpression(@Nullable PsiExpression expression) {
    return findHttpRouteBuilderPathExpression(expression, new HashSet<>());
  }

  private static @Nullable PsiExpression findHttpRouteBuilderPathExpression(@Nullable PsiExpression expression,
                                                                           @NotNull Set<PsiElement> stack) {
    expression = HelidonCommonUtils.unwrapExpression(expression);
    if (expression == null || !stack.add(expression)) return null;
    try {
      if (expression instanceof PsiMethodCallExpression) {
        PsiMethodCallExpression callExpression = (PsiMethodCallExpression)expression;
        PsiMethod method = callExpression.resolveMethod();
        if (method != null && "path".equals(method.getName()) && HelidonConstants.HTTP_ROUTE_BUILDER.equals(getContainingClassName(method))) {
          PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();
          if (arguments.length == 0) return null;
          PsiExpression pathMatcherPattern = HelidonCommonUtils.getPathMatcherFactoryPattern(arguments[0]);
          return pathMatcherPattern != null ? pathMatcherPattern : arguments[0];
        }
        return findHttpRouteBuilderPathExpression(callExpression.getMethodExpression().getQualifierExpression(), stack);
      }
      if (expression instanceof PsiReferenceExpression) {
        PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
        if (resolved instanceof PsiVariable) {
          return findHttpRouteBuilderPathExpression(((PsiVariable)resolved).getInitializer(), stack);
        }
      }
      return null;
    }
    finally {
      stack.remove(expression);
    }
  }

  private static boolean processPathExpressionVariableDefinitions(@NotNull PsiExpression expression,
                                                                 @NotNull Processor<? super PomTargetPsiElement> processor) {
    return processPathExpressionVariableDefinitions(expression, Collections.emptyMap(), processor);
  }

  private static boolean processPathExpressionVariableDefinitions(@NotNull PsiExpression expression,
                                                                 @NotNull Map<PsiParameter, PsiExpression> substitutions,
                                                                 @NotNull Processor<? super PomTargetPsiElement> processor) {
    PsiExpression pathMatcherPattern = HelidonCommonUtils.getPathMatcherFactoryPattern(expression);
    if (pathMatcherPattern != null) {
      return processPathVariableDefinitions(pathMatcherPattern, substitutions, processor);
    }
    return HelidonCommonUtils.isPathMatcherFactoryCall(expression) || processPathVariableDefinitions(expression, substitutions, processor);
  }

  private static @Nullable String getContainingClassName(@NotNull PsiMethod method) {
    PsiClass containingClass = method.getContainingClass();
    return containingClass == null ? null : containingClass.getQualifiedName();
  }

  private static boolean processPathVariableDefinitions(@NotNull PsiElement expression,
                                                        @NotNull Processor<? super PomTargetPsiElement> processor) {
    return processPathVariableDefinitions(expression, Collections.emptyMap(), processor);
  }

  private static boolean processPathVariableDefinitions(@NotNull PsiElement expression,
                                                        @NotNull Map<PsiParameter, PsiExpression> substitutions,
                                                        @NotNull Processor<? super PomTargetPsiElement> processor) {
    if (expression instanceof PsiExpression) {
      PsiExpression substitutedExpression = substituteExpression((PsiExpression)expression, substitutions);
      if (substitutedExpression != null && substitutedExpression != expression) {
        return processPathVariableDefinitions(substitutedExpression, substitutions, processor);
      }
    }
    if (expression instanceof PsiLiteralExpression) {
      PartiallyKnownString evaluatedExpression = evaluatePathExpression((PsiExpression)expression, substitutions);
      return evaluatedExpression == null
             ? processPathVariables(expression, processor)
             : processEvaluatedPathVariableDefinitions(evaluatedExpression, processor);
    }
    if (expression instanceof PsiExpression) {
      PartiallyKnownString evaluatedExpression = evaluatePathExpression((PsiExpression)expression, substitutions);
      if (evaluatedExpression != null) {
        return processEvaluatedPathVariableDefinitions(evaluatedExpression, processor);
      }
    }
    for (PsiLiteralExpression literalExpression : PsiTreeUtil.findChildrenOfType(expression, PsiLiteralExpression.class)) {
      if (!processPathVariables(literalExpression, processor)) return false;
    }
    return true;
  }

  private static @Nullable PartiallyKnownString evaluatePathExpression(@NotNull PsiExpression expression) {
    return evaluatePathExpression(expression, Collections.emptyMap());
  }

  private static @Nullable PartiallyKnownString evaluatePathExpression(@NotNull PsiExpression expression,
                                                                       @NotNull Map<PsiParameter, PsiExpression> substitutions) {
    return evaluatePathExpression(expression, substitutions, new HashSet<>());
  }

  private static @Nullable PartiallyKnownString evaluatePathExpression(@NotNull PsiExpression expression,
                                                                       @NotNull Map<PsiParameter, PsiExpression> substitutions,
                                                                       @NotNull Set<PsiElement> stack) {
    PsiExpression substitutedExpression = substituteExpression(expression, substitutions);
    if (substitutedExpression != null && substitutedExpression != expression) {
      return evaluatePathExpression(substitutedExpression, substitutions, stack);
    }
    if (!stack.add(expression)) return null;
    try {
      if (expression instanceof PsiParenthesizedExpression) {
        PsiExpression parenthesizedExpression = ((PsiParenthesizedExpression)expression).getExpression();
        return parenthesizedExpression == null ? null : evaluatePathExpression(parenthesizedExpression, substitutions, stack);
      }
      if (expression instanceof PsiTypeCastExpression) {
        PsiExpression operand = ((PsiTypeCastExpression)expression).getOperand();
        return operand == null ? null : evaluatePathExpression(operand, substitutions, stack);
      }
      if (expression instanceof PsiLiteralExpression) {
        Object value = ((PsiLiteralExpression)expression).getValue();
        return value instanceof String
               ? new PartiallyKnownString((String)value, expression, ElementManipulators.getValueTextRange(expression))
               : null;
      }
      if (expression instanceof PsiReferenceExpression) {
        PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
        if (resolved instanceof PsiVariable) {
          if (!stack.add(resolved)) return null;
          try {
            PsiExpression initializer = ((PsiVariable)resolved).getInitializer();
            return initializer == null ? null : evaluatePathExpression(initializer, substitutions, stack);
          }
          finally {
            stack.remove(resolved);
          }
        }
      }
      if (expression instanceof PsiPolyadicExpression) {
        return evaluateConcatenation((PsiPolyadicExpression)expression, substitutions, stack);
      }
      return null;
    }
    finally {
      stack.remove(expression);
    }
  }

  private static @Nullable PartiallyKnownString evaluateConcatenation(@NotNull PsiPolyadicExpression expression,
                                                                      @NotNull Map<PsiParameter, PsiExpression> substitutions,
                                                                      @NotNull Set<PsiElement> stack) {
    if (expression.getOperationTokenType() != JavaTokenType.PLUS ||
        !CommonClassNames.JAVA_LANG_STRING.equals(expression.getType() == null ? null : expression.getType().getCanonicalText())) {
      return null;
    }

    List<StringEntry> segments = new ArrayList<>();
    for (PsiExpression operand : expression.getOperands()) {
      PartiallyKnownString operandValue = evaluatePathExpression(operand, substitutions, stack);
      if (operandValue == null || operandValue.getValueIfKnown() == null) return null;
      segments.addAll(operandValue.getSegments());
    }
    return segments.isEmpty() ? null : new PartiallyKnownString(segments);
  }

  private static boolean processEvaluatedPathVariableDefinitions(@NotNull PartiallyKnownString path,
                                                                 @NotNull Processor<? super PomTargetPsiElement> processor) {
    String pathText = path.getValueIfKnown();
    if (pathText == null) return true;

    int searchFrom = 0;
    while (searchFrom < pathText.length()) {
      int start = pathText.indexOf('{', searchFrom);
      if (start < 0) break;
      int end = pathText.indexOf('}', start + 1);
      if (end < 0) break;

      int variableStart = start + 1;
      if (variableStart < end && pathText.charAt(variableStart) == '+') {
        variableStart++;
      }

      int variableEnd = pathText.indexOf(':', variableStart);
      if (variableEnd < 0 || variableEnd > end) {
        variableEnd = end;
      }
      if (variableEnd > variableStart) {
        TextRange variableRange = TextRange.create(variableStart, variableEnd);
        String variableName = variableRange.substring(pathText);
        if (!"*".equals(variableName) && !processMappedPathVariable(path, variableRange, variableName, processor)) return false;
      }
      searchFrom = end + 1;
    }
    return true;
  }

  private static boolean processMappedPathVariable(@NotNull PartiallyKnownString path,
                                                   @NotNull TextRange variableRange,
                                                   @NotNull String variableName,
                                                   @NotNull Processor<? super PomTargetPsiElement> processor) {
    int segmentStart = 0;
    for (StringEntry segment : path.getSegments()) {
      if (segment instanceof StringEntry.Known) {
        String value = ((StringEntry.Known)segment).getValue();
        TextRange segmentRange = TextRange.create(segmentStart, segmentStart + value.length());
        if (segmentRange.contains(variableRange)) {
          kotlin.Pair<PsiElement, TextRange> alignedRange = segment.getRangeAlignedToHost();
          if (alignedRange != null && alignedRange.getFirst().isPhysical()) {
            TextRange hostRange = TextRange.create(alignedRange.getSecond().getStartOffset() + variableRange.getStartOffset() - segmentStart,
                                                   alignedRange.getSecond().getStartOffset() + variableRange.getEndOffset() - segmentStart);
            PsiElement host = alignedRange.getFirst();
            return processor.process(PathVariablePsiElement.create(variableName, host, hostRange, PATH_VARIABLE_USAGES_PROVIDER));
          }
        }
        segmentStart += value.length();
      }
    }
    return true;
  }

  private static final class HttpRouteBuilderPathInfo {
    private @Nullable PsiExpression pathExpression;
    private boolean hasMatchingHandler;
  }

  private static boolean isHandlerMethodCandidate(@Nullable PsiMethod declaration) {
    if (declaration == null) return false;
    PsiParameter[] parameters = declaration.getParameterList().getParameters();
    boolean hasRequestParameter = parameters.length >= 1 &&
                                  isAssignableToAny(parameters[0].getType(), declaration.getProject(),
                                                    HelidonConstants.HTTP_SERVER_REQUEST,
                                                    HelidonConstants.LEGACY_HTTP_SERVER_REQUEST);
    if (parameters.length == 1) return hasRequestParameter;
    return parameters.length == 2 &&
           hasRequestParameter &&
           isAssignableToAny(parameters[1].getType(), declaration.getProject(),
                             HelidonConstants.HTTP_SERVER_RESPONSE,
                             HelidonConstants.LEGACY_HTTP_SERVER_RESPONSE);
  }

  private static boolean isAssignableToAny(@Nullable PsiType type, @NotNull Project project, @NotNull String... classNames) {
    if (type == null) return false;
    for (String className : classNames) {
      if (getTypeByName(className, project).isAssignableFrom(type)) {
        return true;
      }
    }
    return false;
  }

  private static @NotNull PsiClassType getTypeByName(@NotNull String request, @NotNull Project project) {
    return PsiType.getTypeByName(request, project, GlobalSearchScope.allScope(project));
  }
}
