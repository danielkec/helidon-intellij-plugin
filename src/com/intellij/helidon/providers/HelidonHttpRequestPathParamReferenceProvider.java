// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers;

import com.intellij.helidon.constants.HelidonConstants;
import com.intellij.helidon.utils.HelidonCommonUtils;
import com.intellij.microservices.jvm.pathvars.usages.PathVariableUsageUastReferenceProvider;
import com.intellij.microservices.url.parameters.PathVariableDefinitionsSearcher;
import com.intellij.openapi.project.Project;
import com.intellij.pom.PomTargetPsiElement;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.UExpression;

import static com.intellij.helidon.providers.HelidonReferenceContributorKt.*;
import static com.intellij.microservices.jvm.pathvars.usages.AnnotationParamSearcherUtils.processPathVariables;

public final class HelidonHttpRequestPathParamReferenceProvider extends PathVariableUsageUastReferenceProvider {
  public static final HelidonHttpRequestPathParamReferenceProvider INSTANCE = new HelidonHttpRequestPathParamReferenceProvider();

  private HelidonHttpRequestPathParamReferenceProvider() {}

  @Override
  public PathVariableDefinitionsSearcher getSearcher() {
    return new MyPathVariableDefinitionsSearcher();
  }

  private static class MyPathVariableDefinitionsSearcher implements PathVariableDefinitionsSearcher {
    @Override
    public boolean processDefinitions(@NotNull PsiElement context,
                                      @NotNull Processor<? super PomTargetPsiElement> processor) {
      if (!isPathParameterLookup(context)) return true;

      PsiMethod declaration = PsiTreeUtil.getParentOfType(context, PsiMethod.class);
      if (isHandlerMethodCandidate(declaration)) {
        return MethodReferencesSearch.search(declaration, declaration.getResolveScope(), true).forEach(reference -> {
          PsiMethodCallExpression methodCallExpression =
            PsiTreeUtil.getParentOfType(reference.getElement(), PsiMethodCallExpression.class);
          return methodCallExpression == null || processRoutePathVariables(methodCallExpression, processor);
        });
      }

      PsiLambdaExpression lambdaExpression = PsiTreeUtil.getParentOfType(context, PsiLambdaExpression.class);
      if (lambdaExpression != null) {
        PsiMethodCallExpression methodCallExpression = PsiTreeUtil.getParentOfType(lambdaExpression, PsiMethodCallExpression.class);
        return methodCallExpression == null || processRoutePathVariables(methodCallExpression, processor);
      }
      return true;
    }
  }

  private static boolean isPathParameterLookup(@NotNull PsiElement context) {
    PsiMethodCallExpression methodCallExpression = PsiTreeUtil.getParentOfType(context, PsiMethodCallExpression.class);
    if (methodCallExpression == null) return false;
    PsiMethod method = methodCallExpression.resolveMethod();
    if (method == null) return false;

    PsiClass containingClass = method.getContainingClass();
    String containingClassName = containingClass != null ? containingClass.getQualifiedName() : null;
    if ("param".equals(method.getName()) && HelidonConstants.HTTP_REQUEST_PATH.equals(containingClassName)) {
      return true;
    }

    if (!("get".equals(method.getName()) || "first".equals(method.getName())) ||
        !HelidonConstants.HTTP_PARAMETERS.equals(containingClassName)) {
      return false;
    }

    return isPathParametersExpression(methodCallExpression.getMethodExpression().getQualifierExpression());
  }

  private static boolean isPathParametersExpression(@Nullable PsiExpression expression) {
    if (expression == null) return false;
    if (expression instanceof PsiMethodCallExpression) {
      PsiMethod method = ((PsiMethodCallExpression)expression).resolveMethod();
      return method != null &&
             "pathParameters".equals(method.getName()) &&
             isAssignableFromAny(method.getReturnType(), method.getProject(), HelidonConstants.HTTP_PARAMETERS);
    }
    if (expression instanceof PsiReferenceExpression) {
      PsiElement resolved = ((PsiReferenceExpression)expression).resolve();
      if (resolved instanceof PsiLocalVariable) {
        return isPathParametersExpression(((PsiLocalVariable)resolved).getInitializer());
      }
    }
    return false;
  }

  private static boolean processRoutePathVariables(@NotNull PsiMethodCallExpression methodCallExpression,
                                                   @NotNull Processor<? super PomTargetPsiElement> processor) {
    PsiMethod routeMethod = methodCallExpression.resolveMethod();
    if (routeMethod == null) return true;

    int pathArgumentIndex;
    if (getHttpMethodsPattern().accepts(routeMethod) || getRegisterMethodPattern().accepts(routeMethod)) {
      pathArgumentIndex = 0;
    }
    else if (getAnyOfMethodPattern().accepts(routeMethod)) {
      pathArgumentIndex = 1;
    }
    else {
      return true;
    }

    PsiExpression[] expressions = methodCallExpression.getArgumentList().getExpressions();
    if (expressions.length <= pathArgumentIndex ||
        !processPathVariableDefinitions(expressions[pathArgumentIndex], processor)) {
      return false;
    }
    for (UExpression parentPathExpression : HelidonCommonUtils.getParentUrlPathExpressions(methodCallExpression)) {
      PsiElement sourcePsi = parentPathExpression.getSourcePsi();
      if (sourcePsi != null && !processPathVariableDefinitions(sourcePsi, processor)) {
        return false;
      }
    }
    return true;
  }

  private static boolean processPathVariableDefinitions(@NotNull PsiElement expression,
                                                        @NotNull Processor<? super PomTargetPsiElement> processor) {
    if (expression instanceof PsiLiteralExpression) {
      return processPathVariables(expression, processor);
    }
    for (PsiLiteralExpression literalExpression : PsiTreeUtil.findChildrenOfType(expression, PsiLiteralExpression.class)) {
      if (!processPathVariables(literalExpression, processor)) return false;
    }
    return true;
  }

  private static boolean isHandlerMethodCandidate(@Nullable PsiMethod declaration) {
    if (declaration == null) return false;
    PsiParameter[] parameters = declaration.getParameterList().getParameters();
    return parameters.length == 2
           && isAssignableFromAny(parameters[0].getType(), declaration.getProject(),
                                  HelidonConstants.HTTP_SERVER_REQUEST,
                                  HelidonConstants.LEGACY_HTTP_SERVER_REQUEST)
           && isAssignableFromAny(parameters[1].getType(), declaration.getProject(),
                                  HelidonConstants.HTTP_SERVER_RESPONSE,
                                  HelidonConstants.LEGACY_HTTP_SERVER_RESPONSE);
  }

  private static boolean isAssignableFromAny(@Nullable PsiType type, @NotNull Project project, @NotNull String... classNames) {
    if (type == null) return false;
    for (String className : classNames) {
      if (type.isAssignableFrom(getTypeByName(className, project))) {
        return true;
      }
    }
    return false;
  }

  private static @NotNull PsiClassType getTypeByName(@NotNull String request, @NotNull Project project) {
    return PsiType.getTypeByName(request, project, GlobalSearchScope.allScope(project));
  }
}
