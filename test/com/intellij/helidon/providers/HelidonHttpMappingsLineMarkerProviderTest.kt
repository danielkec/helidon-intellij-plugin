// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.utils.HelidonBundle
import com.intellij.java.ultimate.icons.JavaUltimateIcons
import com.intellij.microservices.endpoints.EndpointsViewOpener
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement

class HelidonHttpMappingsLineMarkerProviderTest : HelidonHighlightingTestCase() {

  fun testStandardHttpMethodAnnotationsHaveHttpMappingsGutterMarkers() {
    addHelidonDeclarativeStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Http;
      import io.helidon.webserver.http.RestServer;

      @RestServer.Endpoint
      @Http.Path("/api")
      class GreetingEndpoint {
        @Http.GET
        @Http.Path("/get")
        String get() { return ""; }

        @Http.HEAD
        String head() { return ""; }

        @Http.POST
        String post() { return ""; }

        @Http.PUT
        String put() { return ""; }

        @Http.PATCH
        String patch() { return ""; }

        @Http.DELETE
        String delete() { return ""; }

        @Http.OPTIONS
        String options() { return ""; }
      }
    """.trimIndent())

    listOf("get", "head", "post", "put", "patch", "delete", "options").forEach { methodName ->
      val markers = collectMarkers(httpAnnotation("GreetingEndpoint", methodName))

      assertSize(1, markers)
      assertSame(JavaUltimateIcons.Web.Gutter.RequestMapping, markers.single().icon)
      assertEquals(HelidonBundle.message("gutter.navigate.to.http.mapping"), markers.single().lineMarkerTooltip)
    }
  }

  fun testCustomHttpMethodMetaAnnotationHasHttpMappingsGutterMarker() {
    addHelidonDeclarativeStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Http;
      import io.helidon.webserver.http.RestServer;

      @Http.HttpMethod("PROPFIND")
      @interface PROPFIND {
      }

      @RestServer.Endpoint
      @Http.Path("/files")
      class FileEndpoint {
        @PROPFIND
        @Http.Path("/{path}")
        String properties() { return ""; }
      }
    """.trimIndent())

    val markers = collectMarkers(httpAnnotation("FileEndpoint", "properties"))

    assertSize(1, markers)
    assertSame(JavaUltimateIcons.Web.Gutter.RequestMapping, markers.single().icon)
  }

  fun testInterfaceHttpMethodAnnotationHasHttpMappingsGutterMarkerOnInterfaceDeclaration() {
    addHelidonDeclarativeStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Http;
      import io.helidon.webserver.http.RestServer;

      @Http.Path("/greet")
      interface GreetingResource {
        @Http.PUT
        @Http.Path("/greeting")
        void update(String greeting);
      }

      @RestServer.Endpoint
      class GreetingEndpoint implements GreetingResource {
        public void update(String greeting) {
        }
      }
    """.trimIndent())

    val markers = collectMarkers(httpAnnotation("GreetingResource", "update"))

    assertSize(1, markers)
    assertSame(JavaUltimateIcons.Web.Gutter.RequestMapping, markers.single().icon)
  }

  fun testNavigationUsesEndpointHttpMethodFilterAttribute() {
    addHelidonDeclarativeStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Http;
      import io.helidon.webserver.http.RestServer;

      @RestServer.Endpoint
      @Http.Path("/greet")
      class GreetingEndpoint {
        @Http.GET
        @Http.Path("/{name}")
        String get(String name) { return ""; }
      }
    """.trimIndent())

    val navigation = navigateFrom(httpAnnotation("GreetingEndpoint", "get"))

    assertEquals(module.name, navigation.moduleName)
    assertEquals(HelidonBundle.HELIDON_LIBRARY, navigation.framework)
    assertEquals("http-method: GET greet/{name}", navigation.searchText)
  }

  fun testNavigationForMultipleEndpointsKeepsAllMappingsVisible() {
    addHelidonDeclarativeStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Http;
      import io.helidon.webserver.http.RestServer;

      interface GreetingResource {
        @Http.GET
        @Http.Path("/{name}")
        String get(String name);
      }

      @RestServer.Endpoint
      @Http.Path("/alpha")
      class AlphaEndpoint implements GreetingResource {
        public String get(String name) { return ""; }
      }

      @RestServer.Endpoint
      @Http.Path("/bravo")
      class BravoEndpoint implements GreetingResource {
        public String get(String name) { return ""; }
      }
    """.trimIndent())

    val navigation = navigateFrom(httpAnnotation("GreetingResource", "get"))

    assertEquals(module.name, navigation.moduleName)
    assertEquals(HelidonBundle.HELIDON_LIBRARY, navigation.framework)
    assertEquals("http-method: GET", navigation.searchText)
  }

  fun testHttpMethodAnnotationOutsideRestServerEndpointHasNoHttpMappingsGutterMarker() {
    addHelidonDeclarativeStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Http;

      class NotEndpoint {
        @Http.GET
        String get() { return ""; }
      }
    """.trimIndent())

    val markers = collectMarkers(httpAnnotation("NotEndpoint", "get"))

    assertEmpty(markers)
  }

  private fun collectMarkers(annotation: PsiAnnotation): List<RelatedItemLineMarkerInfo<*>> {
    val anchor = annotation.nameReferenceElement!!.referenceNameElement!!
    val result = mutableListOf<RelatedItemLineMarkerInfo<*>>()

    HelidonHttpMappingsLineMarkerProvider().collectNavigationMarkers(listOf(anchor), result, true)

    return result
  }

  private fun navigateFrom(annotation: PsiAnnotation): EndpointsNavigation {
    return navigateFrom(collectMarkers(annotation).single())
  }

  private fun navigateFrom(marker: RelatedItemLineMarkerInfo<*>): EndpointsNavigation {
    val requests = mutableListOf<EndpointsNavigation>()
    project.messageBus.connect(testRootDisposable).subscribe(EndpointsViewOpener.Companion.TOPIC, object : EndpointsViewOpener {
      override fun showEndpoints(filter: String?) {
        requests += EndpointsNavigation(null, null, filter)
      }

      override fun showEndpoints(module: String?, framework: String?, filter: String?) {
        requests += EndpointsNavigation(module, framework, filter)
      }
    })

    @Suppress("UNCHECKED_CAST")
    val typedMarker = marker as RelatedItemLineMarkerInfo<PsiElement>
    typedMarker.navigationHandler.navigate(null, typedMarker.element)

    return requests.single()
  }

  private fun httpAnnotation(className: String, methodName: String): PsiAnnotation {
    return myFixture.findClass(className)
      .methods
      .single { it.name == methodName }
      .modifierList
      .annotations
      .first { it.qualifiedName != "io.helidon.http.Http.Path" }
  }

  private fun addHelidonDeclarativeStubs() {
    myFixture.addClass("""
      package io.helidon.service.registry;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      public final class Service {
        private Service() {
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
        public @interface Singleton {
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.http;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      public final class Http {
        private Http() {
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ElementType.TYPE, ElementType.METHOD})
        public @interface Path {
          String value() default "/";
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        public @interface HttpMethod {
          String value();
        }

        @HttpMethod("GET")
        public @interface GET {
        }

        @HttpMethod("POST")
        public @interface POST {
        }

        @HttpMethod("PUT")
        public @interface PUT {
        }

        @HttpMethod("DELETE")
        public @interface DELETE {
        }

        @HttpMethod("HEAD")
        public @interface HEAD {
        }

        @HttpMethod("PATCH")
        public @interface PATCH {
        }

        @HttpMethod("OPTIONS")
        public @interface OPTIONS {
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver.http;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      import io.helidon.service.registry.Service;

      public final class RestServer {
        private RestServer() {
        }

        @Retention(RetentionPolicy.CLASS)
        @Target(ElementType.TYPE)
        @Service.Singleton
        public @interface Endpoint {
        }
      }
    """.trimIndent())
  }

  private data class EndpointsNavigation(
    val moduleName: String?,
    val framework: String?,
    val searchText: String?,
  )
}
