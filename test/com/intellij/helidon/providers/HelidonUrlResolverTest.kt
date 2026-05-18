// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.utils.HelidonUrlTargetInfo
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlResolveRequest
import com.intellij.microservices.url.UrlTargetInfo
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager

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
    assertSize(1, resolve("/prefix/{name}-suffix"))
    assertEmpty(resolve("/prefix/bob"))
  }

  fun testResolveMatchesCreateWildcardPrefixRouteChildren() {
    assertSize(1, resolve("/created"))
    assertSize(1, resolve("/created/file"))
    assertEmpty(resolve("/created-suffix"))
  }

  fun testResolveMatchesRawPrefixPathMatcherSegmentPrefix() {
    assertSize(1, resolve("/foo"))
    assertSize(1, resolve("/foo/bar"))
    assertSize(1, resolve("/foobar"))
  }

  fun testResolveMatchesPathMatcherPatternSyntax() {
    assertSize(1, resolve("/files/readme.txt"))
    assertSize(1, resolve("/docs"))
    assertSize(1, resolve("/docs/api"))
    assertSize(1, resolve("/deep/a/b"))
  }

  fun testResolveMatchesPathMatcherCustomRegexWithNestedBraces() {
    assertSize(1, resolve("/bounded/ab/name"))
    assertEmpty(resolve("/bounded/a/name"))
    assertEmpty(resolve("/bounded/abc/name"))
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

  fun testEndpointVariantsAreCachedUntilProjectChanges() {
    configureRoutingEndpoint("/cached")
    val resolver = HelidonUrlResolver(project)

    val firstVariants = resolver.getVariants()
    val secondVariants = resolver.getVariants()

    assertSame(firstVariants, secondVariants)
    assertEquals(listOf("/cached"), firstVariants.endpointPaths())
  }

  fun testEndpointVariantCacheInvalidatesAfterRoutePathEdit() {
    configureRoutingEndpoint("/cached")
    val resolver = HelidonUrlResolver(project)

    val initialVariants = resolver.getVariants()
    assertEquals(listOf("/cached"), initialVariants.endpointPaths())
    assertTrue(resolver.resolve(request("/cached")).toList().isNotEmpty())

    replacePathLiteral("/cached", "/updated")

    val updatedVariants = resolver.getVariants()
    assertNotSame(initialVariants, updatedVariants)
    assertEquals(listOf("/updated"), updatedVariants.endpointPaths())
    assertEmpty(resolver.resolve(request("/cached")).toList())
    assertTrue(resolver.resolve(request("/updated")).toList().isNotEmpty())
  }

  private fun resolve(path: String, method: String = "GET"): List<UrlTargetInfo> {
    return HelidonUrlResolver(project, variants()).resolve(request(path, method)).toList()
  }

  private fun request(path: String, method: String = "GET"): UrlResolveRequest {
    return UrlResolveRequest("http", null, UrlPath.fromExactString(path), method)
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
        .withPrefixPath("/created/"),
      HelidonUrlTargetInfo.create("/foo", psiElement)
        .ofType(HelidonRequestMethods.GET)
        .withPrefixPath(null),
      HelidonUrlTargetInfo.create("/files/*", psiElement)
        .ofType(HelidonRequestMethods.GET)
        .withMatcherPatternPath(),
      HelidonUrlTargetInfo.create("/docs[/{section}]", psiElement)
        .ofType(HelidonRequestMethods.GET)
        .withMatcherPatternPath(),
      HelidonUrlTargetInfo.create("/deep/{+path}", psiElement)
        .ofType(HelidonRequestMethods.GET)
        .withMatcherPatternPath(),
      HelidonUrlTargetInfo.create("/bounded/{id:\\w{2}}/name", psiElement)
        .ofType(HelidonRequestMethods.GET)
        .withMatcherPatternPath(),
      HelidonUrlTargetInfo.create("/api", psiElement).ofType(HelidonRequestMethods.REGISTER),
      HelidonUrlTargetInfo.create("/multi", psiElement)
        .ofType(HelidonRequestMethods.ANY_OF)
        .withMethods(setOf("GET", "POST"))
    )
  }

  private fun configureRoutingEndpoint(path: String) {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.get("$path", Main::hello);
        }

        static void hello(ServerRequest request, ServerResponse response) {
        }
      }
    """.trimIndent())
  }

  private fun replacePathLiteral(oldPath: String, newPath: String) {
    val document = myFixture.editor.document
    val pathOffset = document.text.indexOf("\"$oldPath\"") + 1
    assertTrue(pathOffset > 0)
    WriteCommandAction.runWriteCommandAction(project) {
      document.replaceString(pathOffset, pathOffset + oldPath.length, newPath)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  private fun Iterable<UrlTargetInfo>.endpointPaths(): List<String> {
    return filterIsInstance<HelidonUrlTargetInfo>().map { it.urlDefinition }.distinct().sorted()
  }
}
