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
private const val JAVA_UTIL_FUNCTION_CONSUMER = "java.util.function.Consumer"
private const val JAVA_UTIL_FUNCTION_FUNCTION = "java.util.function.Function"
private const val JAVA_UTIL_FUNCTION_PREDICATE = "java.util.function.Predicate"

private val handlerMethods: List<String> = listOf("get", "post", "put", "patch", "delete", "options", "head", "trace", "any")

private val routingClasses: Set<String> = setOf(
  HelidonConstants.HTTP_RULES,
  HelidonConstants.HTTP_ROUTING_BUILDER,
  HelidonConstants.ROUTING_RULES,
  HelidonConstants.ROUTING_BUILDER
)

private val directHandlerClassNames: Array<String> = arrayOf(
  HelidonConstants.HTTP_HANDLER,
  HelidonConstants.HANDLER
)

private val routeClassNames: Array<String> = arrayOf(
  HelidonConstants.HTTP_ROUTE,
  HelidonConstants.LEGACY_HTTP_ROUTE
)

private val pathMatcherClassNames: Array<String> = arrayOf(
  HelidonConstants.HTTP_PATH_MATCHER,
  HelidonConstants.LEGACY_PATH_MATCHER
)

internal val httpMethodsPattern = or(
  psiMethod().with(object : PatternCondition<PsiMethod>("pathBasedHelidonHttpMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isPathBasedHelidonHttpMethod()
  }),
  psiMethod().with(object : PatternCondition<PsiMethod>("pathlessHelidonHttpMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isPathlessHelidonHttpMethod()
  })
)

internal val anyOfMethodPattern = or(
  psiMethod().with(object : PatternCondition<PsiMethod>("pathBasedHelidonAnyOfMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isPathBasedHelidonAnyOfMethod()
  }),
  psiMethod().with(object : PatternCondition<PsiMethod>("pathlessHelidonAnyOfMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isPathlessHelidonAnyOfMethod()
  })
)

internal val routeMethodPattern = psiMethod()
  .withName("route")
  .with(object : PatternCondition<PsiMethod>("helidonRouteMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isHelidonRouteMethod()
  })

internal val routeObjectFactoryMethodPattern = psiMethod()
  .withName("route")
  .definedInClass(HelidonConstants.LEGACY_HTTP_ROUTE)
  .with(object : PatternCondition<PsiMethod>("legacyHttpRouteFactoryMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isLegacyHttpRouteFactoryMethod()
  })

internal val httpRouteBuilderPathMethodPattern = psiMethod()
  .withName("path")
  .definedInClass(HelidonConstants.HTTP_ROUTE_BUILDER)
  .withParameters(JAVA_LANG_STRING)

internal val httpRouteBuilderHandlerMethodPattern = psiMethod()
  .withName("handler")
  .definedInClass(HelidonConstants.HTTP_ROUTE_BUILDER)

internal val pathMatcherFactoryMethodPattern = or(
  psiMethod()
    .withName(StandardPatterns.string().oneOf("create", "pattern"))
    .definedInClass(HelidonConstants.HTTP_PATH_MATCHERS)
    .withParameters(JAVA_LANG_STRING),
  psiMethod()
    .withName("create")
    .definedInClass(HelidonConstants.LEGACY_PATH_MATCHER)
    .withParameters(JAVA_LANG_STRING)
)

internal val urlPathMatcherFactoryMethodPattern = or(
  psiMethod()
    .withName(StandardPatterns.string().oneOf("create", "exact", "prefix", "pattern"))
    .definedInClass(HelidonConstants.HTTP_PATH_MATCHERS)
    .withParameters(JAVA_LANG_STRING),
  psiMethod()
    .withName("create")
    .definedInClass(HelidonConstants.LEGACY_PATH_MATCHER)
    .withParameters(JAVA_LANG_STRING)
)

internal val registerMethodPattern = psiMethod()
  .withName("register")
  .with(object : PatternCondition<PsiMethod>("pathBasedHelidonRegisterMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isPathBasedHelidonRegisterMethod()
  })

private fun PsiMethod.isPathBasedHelidonRegisterMethod(): Boolean {
  val containingClassName = containingClass?.qualifiedName ?: return false
  if (containingClassName !in routingClasses) return false

  val parameters = parameterList.parameters
  return parameters.size >= 2 &&
         parameters[0].type.equalsToText(JAVA_LANG_STRING) &&
         parameters.drop(1).any { parameter -> parameter.type.isRegisterTargetType(project) }
}

private fun PsiMethod.isPathBasedHelidonHttpMethod(): Boolean {
  if (name !in handlerMethods || !isInRoutingClass()) return false
  val parameters = parameterList.parameters
  return parameters.size >= 2 &&
         parameters[0].type.isRoutePathType(project) &&
         parameters.drop(1).any { parameter -> parameter.type.isDirectHandlerType(project) }
}

private fun PsiMethod.isPathlessHelidonHttpMethod(): Boolean {
  if (name !in handlerMethods || !isInRoutingClass()) return false
  val parameters = parameterList.parameters
  return parameters.isNotEmpty() &&
         parameters.all { parameter -> parameter.type.isDirectHandlerType(project) }
}

private fun PsiMethod.isPathBasedHelidonAnyOfMethod(): Boolean {
  if (name != "anyOf" || !isInRoutingClass()) return false
  val parameters = parameterList.parameters
  return parameters.size >= 3 &&
         parameters[0].type.isIterableType() &&
         parameters[1].type.isRoutePathType(project) &&
         parameters.drop(2).any { parameter -> parameter.type.isDirectHandlerType(project) }
}

private fun PsiMethod.isPathlessHelidonAnyOfMethod(): Boolean {
  if (name != "anyOf" || !isInRoutingClass()) return false
  val parameters = parameterList.parameters
  return parameters.size >= 2 &&
         parameters[0].type.isIterableType() &&
         parameters.drop(1).all { parameter -> parameter.type.isDirectHandlerType(project) }
}

private fun PsiMethod.isHelidonRouteMethod(): Boolean {
  if (!isInRoutingClass()) return false
  val parameters = parameterList.parameters
  if (parameters.size == 1) {
    return parameters[0].type.isRouteObjectType(project) || parameters[0].type.isRouteSupplierType(project)
  }
  if (parameters.size == 2) {
    return parameters[0].type.isHelidonHttpMethodType(project) &&
           parameters[1].type.isDirectHandlerType(project)
  }
  return parameters.size >= 3 &&
         parameters[0].type.isHelidonRouteMethodPredicateType(project) &&
         parameters[1].type.isRoutePathType(project) &&
         parameters[2].type.isRouteHandlerShortcutType(project)
}

private fun PsiMethod.isLegacyHttpRouteFactoryMethod(): Boolean {
  val parameters = parameterList.parameters
  return parameters.size == 3 &&
         parameters[0].type.isHelidonHttpMethodType(project) &&
         parameters[1].type.equalsToText(JAVA_LANG_STRING) &&
         parameters[2].type.isDirectHandlerType(project)
}

private fun PsiMethod.isInRoutingClass(): Boolean {
  return containingClass?.qualifiedName in routingClasses
}

internal fun getHelidonRoutePathArgumentIndex(method: PsiMethod): Int {
  return when {
    method.isPathBasedHelidonHttpMethod() -> 0
    method.isPathBasedHelidonAnyOfMethod() -> 1
    method.isPathBasedHelidonRouteMethod() -> 1
    method.isLegacyHttpRouteFactoryMethod() -> 1
    httpRouteBuilderPathMethodPattern.accepts(method) -> 0
    registerMethodPattern.accepts(method) -> 0
    else -> -1
  }
}

internal fun getHelidonRouteMethodArgumentIndex(method: PsiMethod): Int {
  return when {
    method.isPathBasedHelidonAnyOfMethod() || method.isPathlessHelidonAnyOfMethod() -> 0
    method.isPathBasedHelidonRouteMethod() || method.isPathlessHelidonRouteMethod() || method.isLegacyHttpRouteFactoryMethod() -> 0
    else -> -1
  }
}

internal fun isHelidonPathlessRouteMethod(method: PsiMethod): Boolean {
  return method.isPathlessHelidonHttpMethod() ||
         method.isPathlessHelidonAnyOfMethod() ||
         method.isPathlessHelidonRouteMethod()
}

internal fun isHelidonRouteObjectRegistrationMethod(method: PsiMethod): Boolean {
  return method.name == "route" &&
         method.isInRoutingClass() &&
         method.parameterList.parameters.singleOrNull()?.type?.let { type ->
           type.isRouteObjectType(method.project) || type.isRouteSupplierType(method.project)
         } == true
}

internal fun isHelidonHttpRouteBuilderHandlerMethod(method: PsiMethod): Boolean {
  return httpRouteBuilderHandlerMethodPattern.accepts(method)
}

private fun PsiMethod.isPathBasedHelidonRouteMethod(): Boolean {
  val parameters = parameterList.parameters
  return name == "route" &&
         isInRoutingClass() &&
         parameters.size >= 3 &&
         parameters[0].type.isHelidonRouteMethodPredicateType(project) &&
         parameters[1].type.isRoutePathType(project) &&
         parameters[2].type.isRouteHandlerShortcutType(project)
}

private fun PsiMethod.isPathlessHelidonRouteMethod(): Boolean {
  val parameters = parameterList.parameters
  return name == "route" &&
         isInRoutingClass() &&
         parameters.size == 2 &&
         parameters[0].type.isHelidonHttpMethodType(project) &&
         parameters[1].type.isDirectHandlerType(project)
}

private fun PsiType.isRegisterTargetType(project: Project): Boolean {
  val targetType = when (this) {
    is PsiEllipsisType -> componentType
    is PsiArrayType -> componentType
    else -> this
  }

  if (targetType is PsiWildcardType) {
    if (!targetType.isExtends) return false
    return targetType.bound?.isRegisterTargetType(project) ?: false
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

private fun PsiType.isDirectHandlerType(project: Project): Boolean {
  return unwrapVarargType().isAssignableToAny(project, *directHandlerClassNames)
}

private fun PsiType.isRouteHandlerShortcutType(project: Project): Boolean {
  val targetType = unwrapVarargType()
  if (targetType.isDirectHandlerType(project)) return true
  return targetType.isAssignableToAny(project,
                                      JAVA_UTIL_FUNCTION_CONSUMER,
                                      JAVA_UTIL_FUNCTION_FUNCTION,
                                      JAVA_UTIL_FUNCTION_SUPPLIER)
}

private fun PsiType.isRoutePathType(project: Project): Boolean {
  val targetType = unwrapVarargType()
  return targetType.equalsToText(JAVA_LANG_STRING) ||
         targetType.isAssignableToAny(project, *pathMatcherClassNames)
}

private fun PsiType.isHelidonRouteMethodPredicateType(project: Project): Boolean {
  val targetType = unwrapVarargType()
  return targetType.isHelidonHttpMethodType(project) ||
         targetType.isAssignableToAny(project, HelidonConstants.HTTP_METHOD_PREDICATE, JAVA_UTIL_FUNCTION_PREDICATE)
}

private fun PsiType.isHelidonHttpMethodType(project: Project): Boolean {
  return unwrapVarargType().isAssignableToAny(project,
                                             HelidonConstants.HTTP_METHOD,
                                             "io.helidon.common.http.Http.RequestMethod")
}

private fun PsiType.isRouteObjectType(project: Project): Boolean {
  return unwrapVarargType().isAssignableToAny(project, *routeClassNames)
}

private fun PsiType.isRouteSupplierType(project: Project): Boolean {
  val targetType = unwrapVarargType()
  return targetType.isAssignableToAny(project, JAVA_UTIL_FUNCTION_SUPPLIER) &&
         targetType.hasRouteObjectTypeArgument(project, HashSet())
}

private fun PsiType.isIterableType(): Boolean {
  val classType = unwrapVarargType() as? PsiClassType ?: return false
  val resolved = classType.resolve() ?: return false
  return resolved.qualifiedName == JAVA_LANG_ITERABLE || InheritanceUtil.isInheritor(resolved, JAVA_LANG_ITERABLE)
}

private fun PsiType.unwrapVarargType(): PsiType {
  return when (this) {
    is PsiEllipsisType -> componentType
    is PsiArrayType -> componentType
    is PsiWildcardType -> if (isExtends) bound?.unwrapVarargType() ?: this else this
    else -> this
  }
}

private fun PsiType.hasRouteObjectTypeArgument(project: Project, visited: MutableSet<PsiType>): Boolean {
  val targetType = unwrapVarargType()
  if (!visited.add(targetType)) return false
  if (targetType.isRouteObjectType(project)) return true

  val classType = targetType as? PsiClassType ?: return false
  if (classType.parameters.any { parameter -> parameter.hasRouteObjectTypeArgument(project, visited) }) return true
  return classType.superTypes.any { superType -> superType.hasRouteObjectTypeArgument(project, visited) }
}

private fun PsiType.isAssignableToAny(project: Project, vararg classNames: String): Boolean {
  return classNames.any { className ->
    PsiType.getTypeByName(className, project, GlobalSearchScope.allScope(project)).isAssignableFrom(this)
  }
}

internal fun httpRulesMethods(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(0, callExpression().withResolvedMethod(psiMethod().with(object : PatternCondition<PsiMethod>("stringPathHttpMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isPathBasedHelidonHttpMethod() &&
                                                                                   method.parameterList.parameters[0].type.equalsToText(JAVA_LANG_STRING)
  }), false))

internal fun anyOfMethod(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(1, callExpression().withResolvedMethod(psiMethod().with(object : PatternCondition<PsiMethod>("stringPathAnyOfMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isPathBasedHelidonAnyOfMethod() &&
                                                                                   method.parameterList.parameters[1].type.equalsToText(JAVA_LANG_STRING)
  }), false))

internal fun routeMethod(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(1, callExpression().withResolvedMethod(psiMethod().with(object : PatternCondition<PsiMethod>("stringPathRouteMethod") {
    override fun accepts(method: PsiMethod, context: ProcessingContext): Boolean = method.isPathBasedHelidonRouteMethod() &&
                                                                                   method.parameterList.parameters[1].type.equalsToText(JAVA_LANG_STRING)
  }), false))

internal fun routeObjectFactoryMethod(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(1, callExpression().withResolvedMethod(routeObjectFactoryMethodPattern, false))

internal fun httpRouteBuilderPathMethod(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(0, callExpression().withResolvedMethod(httpRouteBuilderPathMethodPattern, false))

internal fun pathMatcherFactoryMethod(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(0, callExpression().withResolvedMethod(pathMatcherFactoryMethodPattern, false))

internal fun urlPathMatcherFactoryMethod(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(0, callExpression().withResolvedMethod(urlPathMatcherFactoryMethodPattern, false))

internal fun serviceMethodCallPattern(elementPattern: UExpressionPattern<UExpression, *>): UExpressionPattern<*, *> =
  elementPattern.callParameter(0, callExpression().withResolvedMethod(registerMethodPattern, false))

internal class HelidonReferenceContributor : PsiReferenceContributor() {
  override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
    val httpRulesMethods = httpRulesMethods(injectionHostUExpression())
    val anyOfMethod = anyOfMethod(injectionHostUExpression())
    val routeMethod = routeMethod(injectionHostUExpression())
    val routeObjectFactoryMethod = routeObjectFactoryMethod(injectionHostUExpression())
    val httpRouteBuilderPathMethod = httpRouteBuilderPathMethod(injectionHostUExpression())
    val pathMatcherFactoryMethod = pathMatcherFactoryMethod(injectionHostUExpression())
    val urlPathMatcherFactoryMethod = urlPathMatcherFactoryMethod(injectionHostUExpression())
    val serviceMethodCallPattern = serviceMethodCallPattern(injectionHostUExpression())

    registrar.registerUastReferenceProvider(
      or(httpRulesMethods, serviceMethodCallPattern, anyOfMethod, routeMethod, routeObjectFactoryMethod, httpRouteBuilderPathMethod,
         urlPathMatcherFactoryMethod),
      UastUrlPathReferenceProvider { uExpression, psiElement ->
        val injector = uastUrlPathReferenceInjectorForScheme(HTTP_SCHEMES)
          .withDefaultRootContextProviderFactory { HelidonUrlPathSpecification.getUrlPathContext(psiElement) }
        injector.buildReferences(uExpression).forPsiElement(psiElement)
      })

    // path variables
    registrar.registerReferenceProviderByUsage(or(httpRulesMethods, serviceMethodCallPattern, anyOfMethod, routeMethod, routeObjectFactoryMethod,
                                                  httpRouteBuilderPathMethod, pathMatcherFactoryMethod),
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
