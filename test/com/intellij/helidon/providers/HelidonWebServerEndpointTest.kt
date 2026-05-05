// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.helidon.utils.HelidonUrlTargetInfo
import com.intellij.microservices.url.parameters.PathVariablePomTarget
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.util.CommonProcessors.CollectProcessor

class HelidonWebServerEndpointTest : HelidonHighlightingTestCase() {

  fun testDirectHelidon4RouteOnWebServerBuilder() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.WebServer;

      class Main {
        static void main(String[] args) {
          WebServer.builder()
            .routing(routing -> routing
              .get("/hello/{name}", (req, res) -> {})
              .patch("/hello/{name}", (req, res) -> {}))
            .build();
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.any { it.type == HelidonRequestMethods.GET && it.urlDefinition == "/hello/{name}" })
    assertTrue(endpoints.any { it.type == HelidonRequestMethods.PATCH && it.urlDefinition == "/hello/{name}" })
  }

  fun testHelidon4AnyOfMethodPatternAcceptsHttpHandler() {
    val rulesClass = addHelidon4AnyOfStubs().first

    val anyOfMethod = rulesClass.methods.single { it.name == "anyOf" }

    assertTrue(anyOfMethodPattern.accepts(anyOfMethod))
  }

  fun testHelidon4AnyOfRouteUsesPathArgument() {
    addHelidon4AnyOfStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import java.util.List;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.anyOf(List.of("GET", "POST"), "/multi/{name}", (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertAnyOfEndpointMethods(endpoints, "/multi/{name}", setOf("GET", "POST"))
  }

  fun testHelidon4AnyOfRulesRouteUsesPathArgument() {
    addHelidon4AnyOfStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRules;
      import java.util.List;

      class GreetingService {
        void routing(HttpRules rules) {
          rules.anyOf(List.of("GET", "POST"), "/multi/{name}", (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectServiceEndpoints(myFixture.findClass("GreetingService"))

    assertAnyOfEndpointMethods(endpoints, "/multi/{name}", setOf("GET", "POST"))
  }

  fun testLegacyAnyOfRouteUsesRequestMethodConstants() {
    addLegacyAnyOfStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.Routing;
      import java.util.Set;

      import static io.helidon.common.http.Http.Method.GET;
      import static io.helidon.common.http.Http.Method.POST;

      class Main {
        static void routing(Routing.Builder routing) {
          routing.anyOf(Set.of(GET, POST), "/legacy/{name}", (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertAnyOfEndpointMethods(endpoints, "/legacy/{name}", setOf("GET", "POST"))
  }

  fun testAnyOfRouteUsesReferencedRequestMethodList() {
    addLegacyAnyOfStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.common.http.Http;
      import io.helidon.webserver.Routing;
      import java.util.List;

      class Main {
        private static final List<Http.RequestMethod> METHODS = List.of(Http.Method.POST, Http.RequestMethod.create("PROPFIND"));

        static void routing(Routing.Builder routing) {
          routing.anyOf(METHODS, "/custom/{name}", (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertAnyOfEndpointMethods(endpoints, "/custom/{name}", setOf("POST", "PROPFIND"))
  }

  fun testAnyOfRouteDoesNotExtractMethodsFromUnrelatedFactory() {
    addHelidon4AnyOfStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.anyOf(MethodFactory.of("GET"), "/factory/{name}", (req, res) -> {});
        }
      }

      class MethodFactory {
        static Iterable<String> of(String... methods) {
          return null;
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertAnyOfEndpointMethods(endpoints, "/factory/{name}", emptySet())
  }

  fun testRegisteredHelidon4ServiceEndpointsKeepParentPath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api", new GreetingService());
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

    val groups = collectBuilderEndpoints()
    assertTrue(groups.any { it.type == HelidonRequestMethods.REGISTER && it.urlDefinition == "/api" })

    val serviceEndpoints = collectServiceEndpoints(myFixture.findClass("GreetingService"))
    assertTrue(serviceEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api" && it.urlDefinition == "/hello/{name}"
    })
  }

  fun testMultiServiceHelidon4RegistrationKeepsParentPathForEveryService() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api", new AlphaService(), new BetaService());
        }
      }

      class AlphaService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.get("/alpha/{name}", this::alpha);
        }

        void alpha(ServerRequest request, ServerResponse response) {
        }
      }

      class BetaService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.get("/beta/{name}", this::beta);
        }

        void beta(ServerRequest request, ServerResponse response) {
        }
      }
    """.trimIndent())

    val alphaEndpoints = collectServiceEndpoints(myFixture.findClass("AlphaService"))
    val betaEndpoints = collectServiceEndpoints(myFixture.findClass("BetaService"))

    assertTrue(alphaEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api" && it.urlDefinition == "/alpha/{name}"
    })
    assertTrue(betaEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api" && it.urlDefinition == "/beta/{name}"
    })
  }

  fun testNestedServiceConstructorArgumentDoesNotKeepParentPathForInnerService() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api", new CompositeService(new HelperService()));
        }
      }

      class CompositeService implements HttpService {
        CompositeService(Object ignored) {
        }

        @Override
        public void routing(HttpRules rules) {
          rules.get("/composite/{name}", this::composite);
        }

        void composite(ServerRequest request, ServerResponse response) {
        }
      }

      class HelperService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.get("/helper/{name}", this::helper);
        }

        void helper(ServerRequest request, ServerResponse response) {
        }
      }
    """.trimIndent())

    val compositeEndpoints = collectServiceEndpoints(myFixture.findClass("CompositeService"))
    val helperEndpoints = collectServiceEndpoints(myFixture.findClass("HelperService"))

    assertTrue(compositeEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api" && it.urlDefinition == "/composite/{name}"
    })
    assertFalse(helperEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api" && it.urlDefinition == "/helper/{name}"
    })
    assertTrue(helperEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == null && it.urlDefinition == "/helper/{name}"
    })
  }

  fun testConfigBuilderRoutingEndpointsAreDiscoveredThroughRoutingReferenceScope() {
    val webServerConfigFile = myFixture.addFileToProject("WebServerConfigRoutes.java", """
      import io.helidon.webserver.WebServerConfig;

      class WebServerConfigRoutes {
        static void configure() {
          WebServerConfig.builder()
            .routing(routing -> routing.get("/config/{name}", (req, res) -> {}));
        }
      }
    """.trimIndent())
    val listenerConfigFile = myFixture.addFileToProject("ListenerConfigRoutes.java", """
      import io.helidon.webserver.ListenerConfig;

      class ListenerConfigRoutes {
        static void configure() {
          ListenerConfig.builder()
            .routing(routing -> routing.get("/listener/{name}", (req, res) -> {}));
        }
      }
    """.trimIndent())
    val module = ModuleUtilCore.findModuleForPsiElement(webServerConfigFile)!!

    val scope = HelidonCommonUtils.getRoutingClassReferencesScope(module)
    assertTrue(scope.contains(webServerConfigFile.virtualFile))
    assertTrue(scope.contains(listenerConfigFile.virtualFile))

    val endpoints = collectBuilderEndpoints(scope, webServerConfigFile)
    assertTrue(endpoints.any { it.type == HelidonRequestMethods.GET && it.urlDefinition == "/config/{name}" })
    assertTrue(endpoints.any { it.type == HelidonRequestMethods.GET && it.urlDefinition == "/listener/{name}" })
  }

  fun testSupplierServiceRegistrationKeepsParentPath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api/{tenant}", GreetingService::new);
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

    val serviceEndpoints = collectServiceEndpoints(myFixture.findClass("GreetingService"))
    assertTrue(serviceEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api/{tenant}" && it.urlDefinition == "/hello/{name}"
    })
  }

  fun testLambdaSupplierServiceRegistrationKeepsParentPath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api/{tenant}", () -> new GreetingService());
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

    val serviceEndpoints = collectServiceEndpoints(myFixture.findClass("GreetingService"))
    assertTrue(serviceEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api/{tenant}" && it.urlDefinition == "/hello/{name}"
    })
  }

  fun testListSupplierServiceRegistrationKeepsParentPathForListedServiceOnly() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;
      import java.util.List;
      import java.util.function.Supplier;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api/{tenant}", List.of((Supplier<? extends HttpService>) GreetingService::new));
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

      class OtherService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.get("/other/{name}", this::other);
        }

        void other(ServerRequest request, ServerResponse response) {
        }
      }
    """.trimIndent())

    val serviceEndpoints = collectServiceEndpoints(myFixture.findClass("GreetingService"))
    val otherEndpoints = collectServiceEndpoints(myFixture.findClass("OtherService"))

    assertTrue(serviceEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api/{tenant}" && it.urlDefinition == "/hello/{name}"
    })
    assertFalse(otherEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api/{tenant}" && it.urlDefinition == "/other/{name}"
    })
  }

  fun testHelidon4PathParameterReference() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api", new GreetingService());
        }
      }

      class GreetingService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.get("/hello/{name}", this::hello);
        }

        void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNotNull(reference.resolve())
  }

  fun testHelidon4PathParameterReferenceFromConstantRoutePath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        private static final String PREFIX = "/hello";
        private static final String PARAM = "name";
        private static final String PATH = PREFIX + "/{" + PARAM + "}";

        static void routing(HttpRouting.Builder routing) {
          routing.get(PATH, Main::hello);
        }

        static void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    val target = assertInstanceOf(assertInstanceOf(reference.resolve(), PomTargetPsiElement::class.java).target,
                                  PathVariablePomTarget::class.java)
    assertTrue(target.scope.isPhysical)
    assertEquals("name", target.textRange.substring(target.scope.text))
  }

  fun testObjectTypedMethodReferenceDoesNotResolveHelidon4PathParameterReference() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.get("/hello/{name}", Main::hello);
        }

        static void hello(Object request, Object response) {
          ((ServerRequest) request).path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNull(reference.resolve())
  }

  fun testInlineHelidon4PathParameterReference() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.get("/hello/{name}", (req, res) -> {
            req.path().pathParameters().get("na<caret>me");
          });
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNotNull(reference.resolve())
  }

  fun testServiceRegistrationCacheInvalidatesAfterPathEdit() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api", new GreetingService());
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

    val serviceClass = myFixture.findClass("GreetingService")
    val initialEndpoints = collectServiceEndpoints(serviceClass)
    assertTrue(initialEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api" && it.urlDefinition == "/hello/{name}"
    })

    val document = myFixture.editor.document
    val pathOffset = document.text.indexOf("\"/api\"") + 1
    WriteCommandAction.runWriteCommandAction(project) {
      document.replaceString(pathOffset, pathOffset + "/api".length, "/v2")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updatedEndpoints = collectServiceEndpoints(serviceClass)
    assertFalse(updatedEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api" && it.urlDefinition == "/hello/{name}"
    })
    assertTrue(updatedEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/v2" && it.urlDefinition == "/hello/{name}"
    })
  }

  private fun collectBuilderEndpoints(): Collection<HelidonUrlTargetInfo> {
    return collectBuilderEndpoints(GlobalSearchScope.fileScope(myFixture.file), myFixture.file)
  }

  private fun collectBuilderEndpoints(scope: GlobalSearchScope, context: PsiElement): Collection<HelidonUrlTargetInfo> {
    val processor = CollectProcessor<HelidonUrlTargetInfo>()
    val module = ModuleUtilCore.findModuleForPsiElement(context)!!
    assertTrue(HelidonCommonUtils.processBuilderRegisterMethods(processor, scope, module))
    assertTrue(HelidonCommonUtils.processBuilderHttpMethods(processor, scope, module))
    return processor.results
  }

  private fun collectServiceEndpoints(serviceClass: PsiClass): Collection<HelidonUrlTargetInfo> {
    val processor = CollectProcessor<HelidonUrlTargetInfo>()
    val module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!
    assertTrue(HelidonCommonUtils.processRulesHttpMethods(processor, LocalSearchScope(serviceClass), module))
    return processor.results
  }

  private fun assertAnyOfEndpointMethods(endpoints: Collection<HelidonUrlTargetInfo>,
                                         urlDefinition: String,
                                         methods: Set<String>) {
    val anyOfEndpoints = endpoints.filter { it.type == HelidonRequestMethods.ANY_OF && it.urlDefinition == urlDefinition }
    assertFalse(anyOfEndpoints.isEmpty())
    assertTrue(anyOfEndpoints.all { it.methods == methods })
  }

  private fun addHelidon4AnyOfStubs(): Pair<PsiClass, PsiClass> {
    val handlerClass = myFixture.addClass("""
      package io.helidon.webserver.http;

      public interface Handler {
        void handle(Object request, Object response);
      }
    """.trimIndent())
    val rulesClass = myFixture.addClass("""
      package io.helidon.webserver.http;

      public interface HttpRules {
        HttpRules anyOf(java.lang.Iterable<String> methods, String path, Handler... handlers);
      }
    """.trimIndent())
    val routingClass = myFixture.addClass("""
      package io.helidon.webserver.http;

      public interface HttpRouting {
        interface Builder extends HttpRules {
          Builder anyOf(java.lang.Iterable<String> methods, String path, Handler... handlers);
        }
      }
    """.trimIndent())
    assertNotNull(handlerClass)
    return Pair(rulesClass, routingClass)
  }

  private fun addLegacyAnyOfStubs() {
    myFixture.addClass("""
      package io.helidon.common.http;

      public final class Http {
        private Http() {
        }

        public interface RequestMethod {
          String name();

          static RequestMethod create(String name) {
            return null;
          }
        }

        public enum Method implements RequestMethod {
          GET,
          POST,
          DELETE
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver;

      public interface Handler {
        void accept(Object request, Object response);
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver;

      public interface Routing {
        interface Rules {
          Rules anyOf(java.lang.Iterable<io.helidon.common.http.Http.RequestMethod> methods, String path, Handler... handlers);
        }

        interface Builder extends Rules {
          Builder anyOf(java.lang.Iterable<io.helidon.common.http.Http.RequestMethod> methods, String path, Handler... handlers);
        }
      }
    """.trimIndent())
  }
}
