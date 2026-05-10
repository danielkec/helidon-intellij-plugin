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
  private static final DefaultPathVariableUsagesProvider PATH_VARIABLE_USAGES_PROVIDER = new DefaultPathVariableUsagesProvider();

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

  private static boolean processRoutePathVariables(@NotNull PsiMethodCallExpression methodCallExpression,
                                                   @NotNull Processor<? super PomTargetPsiElement> processor) {
    PsiMethod routeMethod = methodCallExpression.resolveMethod();
    if (routeMethod == null) return true;

    if (isHelidonHttpRouteBuilderHandlerMethod(routeMethod)) {
      return processHttpRouteBuilderHandlerPathVariables(methodCallExpression, processor);
    }

    int pathArgumentIndex = getHelidonRoutePathArgumentIndex(routeMethod);
    if (pathArgumentIndex < 0) {
      return !isHelidonPathlessRouteMethod(routeMethod) || processParentRoutePathVariables(methodCallExpression, processor);
    }

    PsiExpression[] expressions = methodCallExpression.getArgumentList().getExpressions();
    if (expressions.length <= pathArgumentIndex ||
        !processPathVariableDefinitions(expressions[pathArgumentIndex], processor)) {
      return false;
    }
    return processParentRoutePathVariables(methodCallExpression, processor);
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
                                                                     @NotNull Processor<? super PomTargetPsiElement> processor) {
    PsiExpression pathExpression = findHttpRouteBuilderPathExpression(methodCallExpression);
    if (pathExpression != null && !processPathVariableDefinitions(pathExpression, processor)) {
      return false;
    }
    return processParentRoutePathVariables(methodCallExpression, processor);
  }

  private static @Nullable PsiExpression findHttpRouteBuilderPathExpression(@Nullable PsiExpression expression) {
    return findHttpRouteBuilderPathExpression(expression, new HashSet<>());
  }

  private static @Nullable PsiExpression findHttpRouteBuilderPathExpression(@Nullable PsiExpression expression,
                                                                           @NotNull Set<? super PsiElement> stack) {
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

  private static @Nullable String getContainingClassName(@NotNull PsiMethod method) {
    PsiClass containingClass = method.getContainingClass();
    return containingClass == null ? null : containingClass.getQualifiedName();
  }

  private static boolean processPathVariableDefinitions(@NotNull PsiElement expression,
                                                        @NotNull Processor<? super PomTargetPsiElement> processor) {
    if (expression instanceof PsiLiteralExpression) {
      return processPathVariables(expression, processor);
    }
    if (expression instanceof PsiExpression) {
      PartiallyKnownString evaluatedExpression = evaluatePathExpression((PsiExpression)expression);
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
    return evaluatePathExpression(expression, new HashSet<>());
  }

  private static @Nullable PartiallyKnownString evaluatePathExpression(@NotNull PsiExpression expression,
                                                                       @NotNull Set<PsiElement> stack) {
    if (!stack.add(expression)) return null;
    try {
      if (expression instanceof PsiParenthesizedExpression) {
        PsiExpression parenthesizedExpression = ((PsiParenthesizedExpression)expression).getExpression();
        return parenthesizedExpression == null ? null : evaluatePathExpression(parenthesizedExpression, stack);
      }
      if (expression instanceof PsiTypeCastExpression) {
        PsiExpression operand = ((PsiTypeCastExpression)expression).getOperand();
        return operand == null ? null : evaluatePathExpression(operand, stack);
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
            return initializer == null ? null : evaluatePathExpression(initializer, stack);
          }
          finally {
            stack.remove(resolved);
          }
        }
      }
      if (expression instanceof PsiPolyadicExpression) {
        return evaluateConcatenation((PsiPolyadicExpression)expression, stack);
      }
      return null;
    }
    finally {
      stack.remove(expression);
    }
  }

  private static @Nullable PartiallyKnownString evaluateConcatenation(@NotNull PsiPolyadicExpression expression,
                                                                      @NotNull Set<PsiElement> stack) {
    if (expression.getOperationTokenType() != JavaTokenType.PLUS ||
        !CommonClassNames.JAVA_LANG_STRING.equals(expression.getType() == null ? null : expression.getType().getCanonicalText())) {
      return null;
    }

    List<StringEntry> segments = new ArrayList<>();
    for (PsiExpression operand : expression.getOperands()) {
      PartiallyKnownString operandValue = evaluatePathExpression(operand, stack);
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

      int variableEnd = pathText.indexOf(':', start + 1);
      if (variableEnd < 0 || variableEnd > end) {
        variableEnd = end;
      }
      if (variableEnd > start + 1) {
        TextRange variableRange = TextRange.create(start + 1, variableEnd);
        String variableName = variableRange.substring(pathText);
        if (!processMappedPathVariable(path, variableRange, variableName, processor)) return false;
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

  private static boolean isHandlerMethodCandidate(@Nullable PsiMethod declaration) {
    if (declaration == null) return false;
    PsiParameter[] parameters = declaration.getParameterList().getParameters();
    return parameters.length == 2
           && isAssignableToAny(parameters[0].getType(), declaration.getProject(),
                                HelidonConstants.HTTP_SERVER_REQUEST,
                                HelidonConstants.LEGACY_HTTP_SERVER_REQUEST)
           && isAssignableToAny(parameters[1].getType(), declaration.getProject(),
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
