// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers.view

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.providers.HelidonRequestMethods
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.helidon.utils.HelidonUrlTargetInfo
import com.intellij.microservices.endpoints.ModuleEndpointsFilter
import com.intellij.microservices.oas.OpenApiSpecification
import com.intellij.microservices.oas.OasParameterIn
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.CommonProcessors.CollectProcessor

class HelidonUrlFrameworkTest : HelidonHighlightingTestCase() {

  fun testRegisteredServiceGroupWithPathVariableExpandsEndpoints() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api/{tenant}", new GreetingService());
        }
      }

      class GreetingService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.get("/hello/{name}", this::hello);
        }

        void hello(ServerRequest request, ServerResponse response) {
        }
      }
    """.trimIndent())

    val groupEndpoint = collectBuilderEndpoints().first {
      it.type == HelidonRequestMethods.REGISTER && it.urlDefinition == "/api/{tenant}"
    }

    val endpoints = HelidonUrlFramework().getEndpoints(groupEndpoint).toList()

    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.GET &&
      it.parentUrl == "/api/{tenant}" &&
      it.urlDefinition == "/hello/{name}"
    })
  }

  fun testNestedRegisteredServiceGroupWithPathVariableExpandsEndpoints() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/base", new ApiService());
        }
      }

      class ApiService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.register("/api/{tenant}", new GreetingService());
        }
      }

      class GreetingService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.get("/hello/{name}", this::hello);
        }

        void hello(ServerRequest request, ServerResponse response) {
        }
      }
    """.trimIndent())

    val groupEndpoint = collectBuilderEndpoints().first {
      it.type == HelidonRequestMethods.REGISTER &&
      it.parentUrl == "/base" &&
      it.urlDefinition == "/api/{tenant}"
    }

    val endpoints = HelidonUrlFramework().getEndpoints(groupEndpoint).toList()

    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.GET &&
      it.parentUrl == "/base/api/{tenant}" &&
      it.urlDefinition == "/hello/{name}"
    })
  }

  fun testRegisteredServiceGroupExpandsRouteOverloadEndpoints() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api/{tenant}", new GreetingService());
        }
      }

      class GreetingService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.route(Method.GET, "/route/{name}", this::hello);
          rules.route(Method.GET, PathMatchers.exact("/exact/{name}"), this::hello);
          rules.route(Method.GET, PathMatchers.prefix("/prefix/{name}"), this::hello);
          rules.route(Method.GET, PathMatchers.create("/created/*"), this::hello);
          rules.route(HttpRoute.builder()
            .methods(Method.POST)
            .path("/built/{name}")
            .handler(this::hello)
            .build());
          rules.get(this::root);
        }

        void hello(ServerRequest request, ServerResponse response) {
        }

        void root(ServerRequest request, ServerResponse response) {
        }
      }
    """.trimIndent())

    val groupEndpoint = collectBuilderEndpoints().first {
      it.type == HelidonRequestMethods.REGISTER && it.urlDefinition == "/api/{tenant}"
    }

    val endpoints = HelidonUrlFramework().getEndpoints(groupEndpoint).toList()

    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.GET &&
      it.parentUrl == "/api/{tenant}" &&
      it.urlDefinition == "/route/{name}"
    })
    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.GET &&
      it.parentUrl == "/api/{tenant}" &&
      it.urlDefinition == "/exact/{name}"
    })
    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.GET &&
      it.parentUrl == "/api/{tenant}" &&
      it.urlDefinition == "/prefix/{name}"
    })
    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.GET &&
      it.parentUrl == "/api/{tenant}" &&
      it.urlDefinition == "/created/*"
    })
    val createdEndpoint = endpoints.first {
      it.type == HelidonRequestMethods.GET &&
      it.parentUrl == "/api/{tenant}" &&
      it.urlDefinition == "/created/*"
    }
    assertTrue(HelidonUrlFramework().getEndpointPresentation(groupEndpoint, createdEndpoint).presentableText
                 ?.contains("/api/{tenant}/created/*") == true)
    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.POST &&
      it.parentUrl == "/api/{tenant}" &&
      it.urlDefinition == "/built/{name}"
    })
    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.GET &&
      it.parentUrl == "/api/{tenant}" &&
      it.urlDefinition == "/"
    })
  }

  fun testGeneratedRestServerHttpFeatureDoesNotDuplicateDeclarativeEndpointGroup() {
    addHelidonDeclarativeStubs()
    addHelidonGeneratedAnnotationStub()

    myFixture.configureByText("SecretService.java", """
      package test;

      import io.helidon.common.Generated;
      import io.helidon.http.Http;
      import io.helidon.service.registry.Service;
      import io.helidon.webserver.http.HttpFeature;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.RestServer;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      @RestServer.Endpoint
      @Service.Singleton
      class SecretService {
        @Http.GET
        @Http.Path("/get-secret")
        String getSecret() {
          return "secret";
        }
      }

      @Generated(value = "io.helidon.declarative.codegen.http.webserver.RestServerExtension",
                 trigger = "test.SecretService")
      @Service.Singleton
      class SecretService__HttpFeature implements HttpFeature {
        @Override
        public void setup(HttpRouting.Builder routing) {
          routing.register("/", this::routing);
        }

        private void routing(HttpRules rules) {
          rules.get("/get-secret", this::getSecret);
        }

        private void getSecret(ServerRequest request, ServerResponse response) {
        }
      }
    """.trimIndent())

    val framework = HelidonUrlFramework()
    val module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!
    val endpoints = framework.getEndpointGroups(project, ModuleEndpointsFilter(module, false, false))
      .flatMap { framework.getEndpoints(it).toList() }
      .filter { it.type == HelidonRequestMethods.GET && it.urlDefinition == "/get-secret" }

    assertEquals(endpoints.joinToString { "${it.type} ${it.urlDefinition} ${containingClassName(it)}" }, 1, endpoints.size)
    assertEquals("SecretService", containingClassName(endpoints.single()))
  }

  fun testDeclarativeHeaderParameterIsAddedToOpenApiSpecification() {
    addHelidonDeclarativeStubs()

    myFixture.configureByText("GreetingService.java", """
      import io.helidon.http.Http;
      import io.helidon.webserver.http.RestServer;

      @RestServer.Endpoint
      @Http.Path("/greet")
      class GreetingService {
        @Http.GET
        @Http.Path("/{name}")
        String getMessage(@Http.PathParam("name") String name,
                          @Http.HeaderParam("test") String test) {
          return name + test;
        }
      }
    """.trimIndent())

    val parameters = getDeclarativeOpenApiParameters("greet/{name}")

    assertTrue(parameters.any { it.name == "name" && it.inPlace == OasParameterIn.PATH })
    assertTrue(parameters.any { it.name == "test" && it.inPlace == OasParameterIn.HEADER })
  }

  fun testInheritedDeclarativeHeaderParameterIsAddedToOpenApiSpecification() {
    addHelidonDeclarativeStubs()

    myFixture.configureByText("GreetingService.java", """
      import io.helidon.http.Http;
      import io.helidon.webserver.http.RestServer;

      interface GreetingApi {
        @Http.GET
        @Http.Path("/{name}")
        String getMessage(@Http.PathParam("name") String name,
                          @Http.HeaderParam("test") String test);
      }

      @RestServer.Endpoint
      @Http.Path("/greet")
      class GreetingService implements GreetingApi {
        public String getMessage(String name, String test) {
          return name + test;
        }
      }
    """.trimIndent())

    val parameters = getDeclarativeOpenApiParameters("greet/{name}")

    assertTrue(parameters.any { it.name == "name" && it.inPlace == OasParameterIn.PATH })
    assertTrue(parameters.any { it.name == "test" && it.inPlace == OasParameterIn.HEADER })
  }

  private fun getDeclarativeOpenApiParameters(path: String) =
    getDeclarativeOpenApiOperation(path).parameters

  private fun getDeclarativeOpenApiOperation(path: String) =
    getDeclarativeOpenApiSpecification(path).paths.single().operations.single()

  private fun getDeclarativeOpenApiSpecification(path: String): OpenApiSpecification {
    val framework = HelidonUrlFramework()
    val module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!
    val groupEndpoint = framework.getEndpointGroups(project, ModuleEndpointsFilter(module, false, false))
      .first { it.presentationPath == path }
    val endpoint = framework.getEndpoints(groupEndpoint).single()
    return framework.getOpenApiSpecification(groupEndpoint, endpoint)
  }

  private fun collectBuilderEndpoints(): Collection<HelidonUrlTargetInfo> {
    val processor = CollectProcessor<HelidonUrlTargetInfo>()
    val module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!
    val scope = GlobalSearchScope.fileScope(myFixture.file)
    assertTrue(HelidonCommonUtils.processBuilderRegisterMethods(processor, scope, module))
    return processor.results
  }

  private fun containingClassName(endpoint: HelidonUrlTargetInfo): String? {
    return PsiTreeUtil.getParentOfType(endpoint.resolveToPsiElement(), PsiClass::class.java)?.name
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
        @Target(ElementType.PARAMETER)
        public @interface PathParam {
          String value();
        }

        @Retention(RetentionPolicy.CLASS)
        @Target(ElementType.PARAMETER)
        public @interface HeaderParam {
          String value();
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        public @interface HttpMethod {
          String value();
        }

        @HttpMethod("GET")
        public @interface GET {
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

  private fun addHelidonGeneratedAnnotationStub() {
    myFixture.addClass("""
      package io.helidon.common;

      public @interface Generated {
        String value();
        String trigger() default "";
      }
    """.trimIndent())
  }
}
