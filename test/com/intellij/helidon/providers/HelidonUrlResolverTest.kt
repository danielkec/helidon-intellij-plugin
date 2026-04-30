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

  private fun resolve(path: String, method: String = "GET"): List<UrlTargetInfo> {
    val request = UrlResolveRequest("http", null, UrlPath.fromExactString(path), method)
    return HelidonUrlResolver(project, variants()).resolve(request).toList()
  }

  private fun variants(): List<UrlTargetInfo> {
    val psiElement = myFixture.configureByText("Routes.java", "class Routes {}")
    return listOf(
      HelidonUrlTargetInfo.create("greet", psiElement).ofType(HelidonRequestMethods.GET),
      HelidonUrlTargetInfo.create("/items/{id}", psiElement).ofType(HelidonRequestMethods.GET)
    )
  }
}
