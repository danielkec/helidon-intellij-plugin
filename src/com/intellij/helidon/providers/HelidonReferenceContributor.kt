// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers

import com.intellij.helidon.constants.HelidonConstants
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.microservices.jvm.url.uastUrlPathReferenceInjectorForScheme
import com.intellij.microservices.url.FrameworkUrlPathSpecification
import com.intellij.microservices.url.HTTP_SCHEMES
import com.intellij.microservices.url.UrlConversionConstants
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.references.UrlPathContext
import com.intellij.microservices.url.references.UrlPksParser
import com.intellij.microservices.url.references.extractPathVariable
import com.intellij.microservices.url.PlaceholderSplitEscaper
import com.intellij.microservices.jvm.pathvars.PathVariableReferenceProvider
import com.intellij.microservices.jvm.url.UastUrlPathReferenceProvider
import com.intellij.openapi.project.Project
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PsiJavaPatterns.psiMethod
import com.intellij.patterns.StandardPatterns
import com.intellij.patterns.StandardPatterns.or
import com.intellij.patterns.uast.UExpressionPattern
import com.intellij.patterns.uast.callExpression
import com.intellij.patterns.uast.injectionHostUExpression
import com.intellij.psi.*
import com.intellij.psi.CommonClassNames.JAVA_LANG_ITERABLE
import com.intellij.psi.CommonClassNames.JAVA_LANG_STRING
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.InheritanceUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.uast.UExpression

private const val JAVA_UTIL_FUNCTION_SUPPLIER = "java.util.function.Supplier"

private val handlerMethods: List<String> = listOf("get", "post", "put", "patch", "delete", "options", "head", "trace", "any")

private val registerClasses: Set<String> = setOf(
  HelidonConstants.HTTP_RULES,
  HelidonConstants.HTTP_ROUTING_BUILDER,
  HelidonConstants.ROUTING_RULES,
  HelidonConstants.ROUTING_BUILDER
)

internal val httpMethodsPattern = or(
  psiMethod()
    .withName(StandardPatterns.string().oneOf(handlerMethods))
    .withParameters(JAVA_LANG_STRING, HelidonConstants.HTTP_HANDLER + "..."),
  psiMethod()
    .withName(StandardPatterns.string().oneOf(handlerMethods))
    .withParameters(JAVA_LANG_STRING, HelidonConstants.HANDLER + "...")
)

internal val anyOfMethodPattern = or(
  psiMethod()
    .withName("anyOf")
    .withParameters(JAVA_LANG_ITERABLE, JAVA_LANG_STRING, HelidonConstants.HTTP_HANDLER + "..."),
  psiMethod()
    .withName("anyOf")
    .withParameters(JAVA_LANG_ITERABLE, JAVA_LANG_STRING, HelidonConstants.HANDLER + "...")
)
internal val registerMethodPattern = psiMethod()
  .withName("register")
  .with(object : PatternCondition<PsiMethod>("pathBasedHelidonRegisterMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isPathBasedHelidonRegisterMethod()
  })

private fun PsiMethod.isPathBasedHelidonRegisterMethod(): Boolean {
  val containingClassName = containingClass?.qualifiedName ?: return false
  if (containingClassName !in registerClasses) return false

  val parameters = parameterList.parameters
  return parameters.size >= 2 &&
         parameters[0].type.equalsToText(JAVA_LANG_STRING) &&
         parameters.drop(1).any { parameter -> parameter.type.isRegisterTargetType(project) }
}

private fun PsiType.isRegisterTargetType(project: Project): Boolean {
  val targetType = when (this) {
    is PsiEllipsisType -> componentType
    is PsiArrayType -> componentType
    else -> this
  }

  if (targetType is PsiWildcardType) {
    return targetType.extendsBound.isRegisterTargetType(project)
  }

  if (targetType.isAssignableToAny(project,
                                   HelidonConstants.HTTP_SERVICE,
                                   HelidonConstants.SERVICE,
                                   HelidonConstants.HTTP_HANDLER,
                                   HelidonConstants.HANDLER)) {
    return true
  }

  val classType = targetType as? PsiClassType ?: return false
  val resolved = classType.resolve() ?: return false
  if (resolved.qualifiedName == JAVA_LANG_ITERABLE || InheritanceUtil.isInheritor(resolved, JAVA_LANG_ITERABLE)) {
    return classType.parameters.any { parameter -> parameter.isRegisterTargetType(project) }
  }
  if (resolved.qualifiedName != JAVA_UTIL_FUNCTION_SUPPLIER) return false
  return classType.parameters.any { parameter -> parameter.isRegisterTargetType(project) }
}

private fun PsiType.isAssignableToAny(project: Project, vararg classNames: String): Boolean {
  return classNames.any { className ->
    PsiType.getTypeByName(className, project, GlobalSearchScope.allScope(project)).isAssignableFrom(this)
  }
}

internal fun httpRulesMethods(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(0, callExpression().withResolvedMethod(httpMethodsPattern, false))

internal fun anyOfMethod(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(1, callExpression().withResolvedMethod(anyOfMethodPattern, false))

internal fun serviceMethodCallPattern(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(0, callExpression().withResolvedMethod(registerMethodPattern, false))

internal class HelidonReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    val httpRulesMethods = httpRulesMethods(injectionHostUExpression())
    val anyOfMethod = anyOfMethod(injectionHostUExpression())
    val serviceMethodCallPattern = serviceMethodCallPattern(injectionHostUExpression())

    registrar.registerUastReferenceProvider(
      or(httpRulesMethods, serviceMethodCallPattern, anyOfMethod),
      UastUrlPathReferenceProvider { uExpression, psiElement ->
        val injector = uastUrlPathReferenceInjectorForScheme(HTTP_SCHEMES)
          .withDefaultRootContextProviderFactory { HelidonUrlPathSpecification.getUrlPathContext(psiElement) }
        injector.buildReferences(uExpression).forPsiElement(psiElement)
      })

    // path variables
    registrar.registerReferenceProviderByUsage(or(httpRulesMethods, serviceMethodCallPattern, anyOfMethod),
                                               PathVariableReferenceProvider.TO_PATH_VARIABLE)

    val httpRequestPathParam = injectionHostUExpression().callParameter(0,
                                                                        callExpression().withResolvedMethod(or(
                                                                          psiMethod().withName("get")
                                                                            .definedInClass(HelidonConstants.HTTP_PARAMETERS),
                                                                          psiMethod().withName("first")
                                                                            .definedInClass(HelidonConstants.HTTP_PARAMETERS),
                                                                          psiMethod().withName("param")
                                                                            .definedInClass(HelidonConstants.HTTP_REQUEST_PATH)
                                                                        ), false))
    registrar.registerUastReferenceProvider(httpRequestPathParam, HelidonHttpRequestPathParamReferenceProvider.INSTANCE)
  }
}

internal object HelidonUrlPathSpecification : FrameworkUrlPathSpecification() {
  override fun getUrlPathContext(declaration: PsiElement): UrlPathContext {
    val parentUrlPaths = HelidonCommonUtils.getParentUrlPaths(declaration)
    val singleContext = UrlPathContext.supportingSchemes(HTTP_SCHEMES)

    return if (parentUrlPaths.size == 0) singleContext
    else singleContext.subContexts(parentUrlPaths.map { path -> HelidonUrlPathSpecification.parsePath(path) })
  }

  override val parser: UrlPksParser = UrlPksParser().apply {
    splitEscaper = { input, pattern -> PlaceholderSplitEscaper.create("{", "}", input, pattern) }
    customPathSegmentExtractor = { segmentStr ->
      extractPathVariable(segmentStr, UrlConversionConstants.SPRING_LIKE_PATH_VARIABLE_BRACES)
      ?: UrlPath.PathSegment.Exact(segmentStr)
    }
    parseQueryParameters = false
  }
}
