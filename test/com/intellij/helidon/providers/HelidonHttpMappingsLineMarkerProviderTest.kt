// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.utils.HelidonBundle
import com.intellij.java.ultimate.icons.JavaUltimateIcons
import com.intellij.psi.PsiAnnotation

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

  fun testInterfaceHttpMethodAnnotationHasHttpMappingsGutterMarkerForEndpointImplementation() {
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
}
