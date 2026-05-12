// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.utils.HelidonUrlTargetInfo
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlResolveRequest
import com.intellij.microservices.url.UrlTargetInfo

class HelidonUrlResolverTest : HelidonHighlightingTestCase() {

  fun testResolveFindsMatchingEndpoint() {
    val targets = resolve("/greet")

    assertSize(1, targets)
    assertEquals("greet", (targets.single() as HelidonUrlTargetInfo).urlDefinition)
  }

  fun testResolveIgnoresLeadingAndTrailingSlashDifference() {
    val targets = resolve("greet/")

    assertSize(1, targets)
    assertEquals("greet", (targets.single() as HelidonUrlTargetInfo).urlDefinition)
  }

  fun testResolveReturnsEmptyListForNoMatch() {
    assertEmpty(resolve("/missing"))
  }

  fun testResolveFindsParameterizedRoute() {
    val targets = resolve("/items/42")

    assertSize(1, targets)
    assertEquals("/items/{id}", (targets.single() as HelidonUrlTargetInfo).urlDefinition)
  }

  fun testResolveKeepsLiteralPathMatcherExactRouteLiteral() {
    assertSize(1, resolve("/literal/{name}"))
    assertEmpty(resolve("/literal/bob"))
  }

  fun testResolveMatchesLiteralPathMatcherPrefixRouteChildren() {
    assertSize(1, resolve("/prefix/{name}"))
    assertSize(1, resolve("/prefix/{name}/child"))
    assertEmpty(resolve("/prefix/bob"))
  }

  fun testResolveMatchesCreateWildcardPrefixRouteChildren() {
    assertSize(1, resolve("/created"))
    assertSize(1, resolve("/created/file"))
    assertEmpty(resolve("/created-suffix"))
  }

  fun testResolveMatchesRegisterTargetAsAnyMethod() {
    val targets = resolve("/api", "GET")

    assertSize(1, targets)
    val target = targets.single() as HelidonUrlTargetInfo
    assertEquals(HelidonRequestMethods.REGISTER, target.type)
    assertEquals("/api", target.urlDefinition)
  }

  fun testResolveFiltersAnyOfTargetByExplicitMethods() {
    assertSize(1, resolve("/multi", "POST"))
    assertEmpty(resolve("/multi", "DELETE"))
  }

  private fun resolve(path: String, method: String = "GET"): List<UrlTargetInfo> {
    val request = UrlResolveRequest("http", null, UrlPath.fromExactString(path), method)
    return HelidonUrlResolver(project, variants()).resolve(request).toList()
  }

  private fun variants(): List<UrlTargetInfo> {
    val psiElement = myFixture.configureByText("Routes.java", "class Routes {}")
    return listOf(
      HelidonUrlTargetInfo.create("greet", psiElement).ofType(HelidonRequestMethods.GET),
      HelidonUrlTargetInfo.create("/items/{id}", psiElement).ofType(HelidonRequestMethods.GET),
      HelidonUrlTargetInfo.create("/literal/{name}", psiElement)
        .ofType(HelidonRequestMethods.GET)
        .withLiteralPath(),
      HelidonUrlTargetInfo.create("/prefix/{name}", psiElement)
        .ofType(HelidonRequestMethods.GET)
        .withPrefixPath(null),
      HelidonUrlTargetInfo.create("/created/*", psiElement)
        .ofType(HelidonRequestMethods.GET)
        .withPrefixPath("/created"),
      HelidonUrlTargetInfo.create("/api", psiElement).ofType(HelidonRequestMethods.REGISTER),
      HelidonUrlTargetInfo.create("/multi", psiElement)
        .ofType(HelidonRequestMethods.ANY_OF)
        .withMethods(setOf("GET", "POST"))
    )
  }
}
