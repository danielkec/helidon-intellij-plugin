// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.helidon.utils.HelidonUrlTargetInfo
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.parameters.PathVariablePomTarget
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.JavaPsiFacade
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

  fun testHelidon4RouteMethodUsesMethodAndPathArguments() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.POST, "/routed/{name}", (req, res) -> {});
          routing.route(Method.PATCH, "/consumer/{name}", req -> {});
          routing.route(Method.PUT, "/function/{name}", req -> "ok");
          routing.route(Method.DELETE, "/supplier/{name}", () -> "ok");
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.any { it.type == HelidonRequestMethods.POST && it.urlDefinition == "/routed/{name}" })
    assertTrue(endpoints.any { it.type == HelidonRequestMethods.PATCH && it.urlDefinition == "/consumer/{name}" })
    assertTrue(endpoints.any { it.type == HelidonRequestMethods.PUT && it.urlDefinition == "/function/{name}" })
    assertTrue(endpoints.any { it.type == HelidonRequestMethods.DELETE && it.urlDefinition == "/supplier/{name}" })
  }

  fun testHelidon4HttpRouteBuilderRegistrationIsDiscovered() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(HttpRoute.builder()
            .methods(Method.GET, Method.POST)
            .path("/built/{name}")
            .handler((req, res) -> {})
            .build());
          routing.route(HttpRoute.builder()
            .methods(Method.DELETE)
            .handler((req, res) -> {})
            .build());
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertAnyOfEndpointMethods(endpoints, "/built/{name}", setOf("GET", "POST"))
    assertTrue(endpoints.any { it.type == HelidonRequestMethods.DELETE && it.urlDefinition == "/" })
  }

  fun testBuilderRouteMethodEndpointIsNotDuplicatedByInheritedHttpRulesMethod() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, "/route/{name}", (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints().filter {
      it.type == HelidonRequestMethods.GET && it.urlDefinition == "/route/{name}"
    }

    assertEquals(endpoints.joinToString { "${it.type} ${it.urlDefinition} ${it.methods}" }, 1, endpoints.size)
  }

  fun testHelidon4HttpRouteBuilderWithoutHandlerIsNotDiscovered() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(HttpRoute.builder()
            .methods(Method.GET)
            .path("/dangling/{name}"));
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertFalse(endpoints.any { it.type == HelidonRequestMethods.GET && it.urlDefinition == "/dangling/{name}" })
  }

  fun testHelidon4HttpRouteBuilderSupplierRegistrationIsDiscovered() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(HttpRoute.builder()
            .methods(Method.GET)
            .path("/supplied-builder/{name}")
            .handler((req, res) -> {}));
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.any { it.type == HelidonRequestMethods.GET && it.urlDefinition == "/supplied-builder/{name}" })
  }

  fun testHelidon4HttpRouteBuilderRegistrationFromMethodReferenceSupplierIsDiscovered() {
    addHelidon4SourceRouteStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Main::route);
        }

        static HttpRoute route() {
          return HttpRoute.builder()
            .methods(Method.POST)
            .path("/supplied/{name}")
            .handler((req, res) -> {})
            .build();
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.joinToString { "${it.type} ${it.urlDefinition} ${it.methods}" },
               endpoints.any { it.type == HelidonRequestMethods.POST && it.urlDefinition == "/supplied/{name}" })
  }

  fun testHelidon4HttpRouteBuilderAndSupplierFromHelperAreDiscovered() {
    addHelidon4SourceRouteStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;
      import java.util.function.Supplier;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Routes.builder());
          routing.route(Routes.supplier());
        }
      }

      class Routes {
        static HttpRoute.Builder builder() {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path("/builder-helper/{name}")
            .handler((req, res) -> {});
        }

        static Supplier<? extends HttpRoute> supplier() {
          return HttpRoute.builder()
            .methods(Method.POST)
            .path("/supplier-helper/{name}")
            .handler((req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.joinToString { "${it.type} ${it.urlDefinition} ${it.methods}" },
               endpoints.any { it.type == HelidonRequestMethods.GET && it.urlDefinition == "/builder-helper/{name}" })
    assertTrue(endpoints.joinToString { "${it.type} ${it.urlDefinition} ${it.methods}" },
               endpoints.any { it.type == HelidonRequestMethods.POST && it.urlDefinition == "/supplier-helper/{name}" })
  }

  fun testHelidon4ParameterizedHttpRouteHelperIsDiscovered() {
    addHelidon4SourceRouteStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.Handler;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Routes.route(Method.POST, "/parameterized/{name}", (req, res) -> {}));
        }
      }

      class Routes {
        static HttpRoute route(Method method, String path, Handler handler) {
          return HttpRoute.builder()
            .methods(method)
            .path(path)
            .handler(handler)
            .build();
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.joinToString { "${it.type} ${it.urlDefinition} ${it.methods}" },
               endpoints.any { it.type == HelidonRequestMethods.POST && it.urlDefinition == "/parameterized/{name}" })
  }

  fun testHelidon4BuilderReturningHelperQualifierIsDiscovered() {
    addHelidon4SourceRouteStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Routes.base().handler((req, res) -> {}));
        }
      }

      class Routes {
        static HttpRoute.Builder base() {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path("/base/{name}");
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.joinToString { "${it.type} ${it.urlDefinition} ${it.methods}" },
               endpoints.any { it.type == HelidonRequestMethods.GET && it.urlDefinition == "/base/{name}" })
  }

  fun testHelidon4SourceMethodFieldsKeepConcreteRouteMethods() {
    addHelidon4SourceRouteStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.POST, "/posted/{name}", (req, res) -> {});
          routing.route(HttpRoute.builder()
            .methods(Method.GET)
            .path("/built/{name}")
            .handler((req, res) -> {})
            .build());
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.any { it.type == HelidonRequestMethods.POST && it.urlDefinition == "/posted/{name}" })
    assertTrue(endpoints.any { it.type == HelidonRequestMethods.GET && it.urlDefinition == "/built/{name}" })
  }

  fun testHelidon4SourceMethodAliasesUseInitializerMethods() {
    addHelidon4SourceRouteStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        private static final Method WRITE = Method.POST;
        private static final Method CUSTOM = Method.create("PROPFIND");

        static void routing(HttpRouting.Builder routing) {
          routing.route(WRITE, "/write/{name}", (req, res) -> {});
          routing.route(CUSTOM, "/custom/{name}", (req, res) -> {});
          routing.route(HttpRoute.builder()
            .methods(WRITE, CUSTOM)
            .path("/built/{name}")
            .handler((req, res) -> {})
            .build());
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.any { it.type == HelidonRequestMethods.POST && it.urlDefinition == "/write/{name}" })
    assertTrue(endpoints.any { it.type == HelidonRequestMethods.UNKNOWN && it.methods == setOf("PROPFIND") && it.urlDefinition == "/custom/{name}" })
    assertAnyOfEndpointMethods(endpoints, "/built/{name}", setOf("POST", "PROPFIND"))
  }

  fun testRegisterEndpointIsNotDuplicatedByHttpMethodProcessing() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api", new GreetingService());
        }
      }

      class GreetingService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
        }
      }
    """.trimIndent())

    val registerEndpoints = collectBuilderEndpoints().filter {
      it.type == HelidonRequestMethods.REGISTER && it.urlDefinition == "/api"
    }

    assertEquals(registerEndpoints.joinToString { "${it.type} ${it.urlDefinition} ${it.methods}" }, 1, registerEndpoints.size)
  }

  fun testRegisterEndpointsWithSamePathKeepDistinctSources() {
    myFixture.configureByText("Main.java", """
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api", new AlphaService());
          routing.register("/api", new BetaService());
        }
      }

      class AlphaService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
        }
      }

      class BetaService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
        }
      }
    """.trimIndent())

    val registerEndpoints = collectBuilderEndpoints().filter {
      it.type == HelidonRequestMethods.REGISTER && it.urlDefinition == "/api"
    }

    assertEquals(registerEndpoints.joinToString { "${it.type} ${it.urlDefinition} ${it.resolveToPsiElement()?.textRange}" },
                 2,
                 registerEndpoints.size)
  }

  fun testLegacyHttpRouteRegistrationIsDiscovered() {
    addLegacyAnyOfStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.common.http.Http;
      import io.helidon.webserver.HttpRoute;
      import io.helidon.webserver.Routing;

      class Main {
        static void routing(Routing.Builder routing) {
          routing.route(HttpRoute.route(Http.Method.DELETE, "/legacy-route/{name}", (req, res) -> {}));
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.any { it.type == HelidonRequestMethods.DELETE && it.urlDefinition == "/legacy-route/{name}" })
  }

  fun testLegacyHttpRouteRegistrationFromHelperIsDiscovered() {
    addLegacyAnyOfStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.common.http.Http;
      import io.helidon.webserver.HttpRoute;
      import io.helidon.webserver.Routing;

      class Main {
        static void routing(Routing.Builder routing) {
          routing.route(Routes.legacy());
        }
      }

      class Routes {
        static HttpRoute legacy() {
          return HttpRoute.route(Http.Method.DELETE, "/legacy-helper/{name}", (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.any { it.type == HelidonRequestMethods.DELETE && it.urlDefinition == "/legacy-helper/{name}" })
  }

  fun testParameterizedLegacyHttpRouteRegistrationFromHelperIsDiscovered() {
    addLegacyAnyOfStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.common.http.Http;
      import io.helidon.webserver.HttpRoute;
      import io.helidon.webserver.Routing;

      class Main {
        static void routing(Routing.Builder routing) {
          routing.route(Routes.legacy(Http.Method.DELETE, "/legacy-parameterized/{name}"));
        }
      }

      class Routes {
        static HttpRoute legacy(Http.Method method, String path) {
          return HttpRoute.route(method, path, (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()

    assertTrue(endpoints.joinToString { "${it.type} ${it.urlDefinition} ${it.methods}" },
               endpoints.any { it.type == HelidonRequestMethods.DELETE && it.urlDefinition == "/legacy-parameterized/{name}" })
  }

  fun testPathlessHelidon4ServiceRouteKeepsParentPath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.get((req, res) -> {});
          routing.route(Method.POST, (req, res) -> {});
          routing.register("/api", new GreetingService());
        }
      }

      class GreetingService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.get(this::hello);
        }

        void hello(ServerRequest request, ServerResponse response) {
        }
      }
    """.trimIndent())

    val builderEndpoints = collectBuilderEndpoints()
    val serviceEndpoints = collectServiceEndpoints(myFixture.findClass("GreetingService"))

    assertTrue(builderEndpoints.any { it.type == HelidonRequestMethods.GET && it.urlDefinition == "/" })
    assertTrue(builderEndpoints.any { it.type == HelidonRequestMethods.POST && it.urlDefinition == "/" })
    assertTrue(serviceEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api" && it.urlDefinition == "/"
    })
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

  fun testHelidon4RouteObjectFromHelperKeepsRegisteredServiceParentPath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api/{tenant}", new GreetingService());
        }
      }

      class GreetingService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.route(Routes.hello());
        }
      }

      class Routes {
        static HttpRoute hello() {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path("/helper/{name}")
            .handler((req, res) -> {})
            .build();
        }
      }
    """.trimIndent())

    val serviceEndpoints = collectServiceEndpoints(myFixture.findClass("GreetingService"))

    assertTrue(serviceEndpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/api/{tenant}" && it.urlDefinition == "/helper/{name}"
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

  fun testHelidon4RouteMethodPathParameterReference() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, "/hello/{name}", Main::hello);
        }

        static void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNotNull(reference.resolve())
  }

  fun testHelidon4RouteMethodSingleRequestHandlerPathParameterReference() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, "/hello/{name}", Main::hello);
        }

        static String hello(ServerRequest request) {
          return request.path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNotNull(reference.resolve())
  }

  fun testHelidon4RouteMethodPathMatcherPathParameterReference() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        private static final String PREFIX = "/hello";
        private static final String PARAM = "name";
        private static final String PATH = PREFIX + "/{" + PARAM + "}";

        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, PathMatchers.pattern(PATH), Main::hello);
        }

        static void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNotNull(reference.resolve())
  }

  fun testHelidon4PathMatcherExactPathParameterReferenceDoesNotResolve() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, PathMatchers.exact("/hello/{name}"), Main::hello);
        }

        static void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNull(reference.resolve())
  }

  fun testHelidon4PathMatcherPrefixPathParameterReferenceDoesNotResolve() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, PathMatchers.prefix("/hello/{name}"), Main::hello);
        }

        static void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNull(reference.resolve())
  }

  fun testPathMatcherLiteralFactoriesKeepLiteralEndpointPaths() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, PathMatchers.exact("/exact/{name}"), (req, res) -> {});
          routing.route(Method.GET, PathMatchers.prefix("/prefix/{name}"), (req, res) -> {});
          routing.route(Method.GET, PathMatchers.create("/created/*"), (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()
    val exactEndpoints = endpoints.filter { it.urlDefinition == "/exact/{name}" }
    val prefixEndpoints = endpoints.filter { it.urlDefinition == "/prefix/{name}" }
    val createEndpoints = endpoints.filter { it.urlDefinition == "/created/*" }

    assertTrue(endpointSummary(exactEndpoints), exactEndpoints.isNotEmpty())
    assertTrue(endpointSummary(exactEndpoints),
               exactEndpoints.all { it.matchesPath(UrlPath.fromExactString("/exact/{name}")) })
    assertTrue(endpointSummary(exactEndpoints),
               exactEndpoints.none { it.matchesPath(UrlPath.fromExactString("/exact/bob")) })
    assertTrue(endpointSummary(prefixEndpoints), prefixEndpoints.isNotEmpty())
    assertTrue(endpointSummary(prefixEndpoints),
               prefixEndpoints.all { it.matchesPath(UrlPath.fromExactString("/prefix/{name}")) })
    assertTrue(endpointSummary(prefixEndpoints),
               prefixEndpoints.all { it.matchesPath(UrlPath.fromExactString("/prefix/{name}/child")) })
    assertTrue(endpointSummary(prefixEndpoints),
               prefixEndpoints.none { it.matchesPath(UrlPath.fromExactString("/prefix/bob")) })
    assertTrue(endpointSummary(createEndpoints), createEndpoints.isNotEmpty())
    assertTrue(endpointSummary(createEndpoints),
               createEndpoints.all { it.matchesPath(UrlPath.fromExactString("/created")) })
    assertTrue(endpointSummary(createEndpoints),
               createEndpoints.all { it.matchesPath(UrlPath.fromExactString("/created/file")) })
    assertTrue(endpointSummary(createEndpoints),
               createEndpoints.none { it.matchesPath(UrlPath.fromExactString("/created-suffix")) })
  }

  fun testPathMatcherPatternFactoryKeepsMatcherEndpointSemantics() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, PathMatchers.pattern("/files/*"), (req, res) -> {});
          routing.route(Method.GET, PathMatchers.pattern("/docs[/{section}]"), (req, res) -> {});
          routing.route(Method.GET, PathMatchers.create("/created*"), (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints()
    val filesEndpoint = endpoints.single { it.urlDefinition == "/files/*" }
    val docsEndpoint = endpoints.single { it.urlDefinition == "/docs[/{section}]" }
    val createdEndpoint = endpoints.single { it.urlDefinition == "/created*" }

    assertTrue(endpointSummary(endpoints), filesEndpoint.matchesPath(UrlPath.fromExactString("/files/readme.txt")))
    assertTrue(endpointSummary(endpoints), docsEndpoint.matchesPath(UrlPath.fromExactString("/docs")))
    assertTrue(endpointSummary(endpoints), docsEndpoint.matchesPath(UrlPath.fromExactString("/docs/api")))
    assertTrue(endpointSummary(endpoints), createdEndpoint.matchesPath(UrlPath.fromExactString("/created-file")))
  }

  fun testPathMatcherLiteralFactoryConstantPathKeepsLiteralEndpointPath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRouting;

      class Main {
        private static final String NAME = "name";
        private static final String PATH = "/exact/{" + NAME + "}";

        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, PathMatchers.exact(PATH), (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectBuilderEndpoints().filter { it.urlDefinition == "/exact/{name}" }

    assertTrue(endpointSummary(endpoints), endpoints.isNotEmpty())
    assertTrue(endpointSummary(endpoints), endpoints.all { it.path.isCompatibleWith(UrlPath.fromExactString("/exact/{name}")) })
    assertTrue(endpointSummary(endpoints), endpoints.none { it.path.isCompatibleWith(UrlPath.fromExactString("/exact/bob")) })
  }

  fun testPathMatcherLiteralFactoryKeepsParentPathVariables() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api/{tenant}", new GreetingService());
        }
      }

      class GreetingService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.route(Method.GET, PathMatchers.exact("/literal/{name}"), (req, res) -> {});
          rules.route(Method.GET, PathMatchers.prefix("/prefix/{name}"), (req, res) -> {});
          rules.route(Method.GET, PathMatchers.create("/created/*"), (req, res) -> {});
        }
      }
    """.trimIndent())

    val endpoints = collectServiceEndpoints(myFixture.findClass("GreetingService"))
    val endpoint = endpoints.single { it.parentUrl == "/api/{tenant}" && it.urlDefinition == "/literal/{name}" }
    val prefixEndpoint = endpoints.single { it.parentUrl == "/api/{tenant}" && it.urlDefinition == "/prefix/{name}" }
    val createEndpoint = endpoints.single { it.parentUrl == "/api/{tenant}" && it.urlDefinition == "/created/*" }

    assertTrue(endpoint.matchesPath(UrlPath.fromExactString("/api/acme/literal/{name}")))
    assertFalse(endpoint.matchesPath(UrlPath.fromExactString("/api/acme/literal/bob")))
    assertTrue(prefixEndpoint.matchesPath(UrlPath.fromExactString("/api/acme/prefix/{name}/child")))
    assertFalse(prefixEndpoint.matchesPath(UrlPath.fromExactString("/api/acme/prefix/bob/child")))
    assertTrue(createEndpoint.matchesPath(UrlPath.fromExactString("/api/acme/created/file")))
  }

  fun testPathMatcherLiteralFactoriesAreNotVariableBearingDeclarationPatterns() {
    assertPathMatcherDeclarationDoesNotResolvePathVariable("exact")
    assertPathMatcherDeclarationDoesNotResolvePathVariable("prefix")

    val pathMatchers = JavaPsiFacade.getInstance(project)
      .findClass("io.helidon.http.PathMatchers", GlobalSearchScope.allScope(project))

    assertNotNull(pathMatchers)
    assertTrue(pathMatcherFactoryMethodPattern.accepts(pathMatcherMethod(pathMatchers!!, "create")))
    assertTrue(pathMatcherFactoryMethodPattern.accepts(pathMatcherMethod(pathMatchers, "pattern")))
    assertFalse(pathMatcherFactoryMethodPattern.accepts(pathMatcherMethod(pathMatchers, "exact")))
    assertFalse(pathMatcherFactoryMethodPattern.accepts(pathMatcherMethod(pathMatchers, "prefix")))
  }

  fun testPathMatcherLiteralFactoriesHaveUrlReferences() {
    assertPathMatcherFactoryHasUrlReference("exact")
    assertPathMatcherFactoryHasUrlReference("prefix")
  }

  fun testHelidon4PathMatcherWildcardPathParameterReferenceFromConstantRoutePath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        private static final String PATH = "/files/{+path}";

        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, PathMatchers.pattern(PATH), Main::files);
        }

        static void files(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("pa<caret>th");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    val target = assertInstanceOf(assertInstanceOf(reference.resolve(), PomTargetPsiElement::class.java).target,
                                  PathVariablePomTarget::class.java)
    assertTrue(target.scope.isPhysical)
    assertEquals("path", target.textRange.substring(target.scope.text))
  }

  fun testHelidon4PathMatcherUnnamedWildcardPathParameterReferenceDoesNotResolve() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Method.GET, PathMatchers.pattern("/files/{*}"), Main::files);
        }

        static void files(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("*<caret>");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNull(reference.resolve())
  }

  fun testHelidon4HttpRouteBuilderPathParameterReference() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(HttpRoute.builder()
            .methods(Method.GET)
            .path("/hello/{name}")
            .handler(Main::hello)
            .build());
        }

        static void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNotNull(reference.resolve())
  }

  fun testHelidon4HttpRouteBuilderPathParameterReferenceWithPathAfterHandler() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(HttpRoute.builder()
            .methods(Method.GET)
            .handler(Main::hello)
            .path("/hello/{name}")
            .build());
        }

        static void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNotNull(reference.resolve())
  }

  fun testHelidon4HttpRouteBuilderPathMatcherPathParameterReference() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.http.PathMatchers;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(HttpRoute.builder()
            .methods(Method.GET)
            .path(PathMatchers.pattern("/hello/{name}"))
            .handler(Main::hello)
            .build());
        }

        static void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNotNull(reference.resolve())
  }

  fun testHelidon4ParameterizedHttpRouteHelperPathParameterReferenceFromHandlerArgument() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.Handler;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Routes.route(Method.GET, "/parameterized/{name}", Main::hello));
        }

        static void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }

      class Routes {
        static HttpRoute route(Method method, String path, Handler handler) {
          return HttpRoute.builder()
            .methods(method)
            .path(path)
            .handler(handler)
            .build();
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    val target = assertInstanceOf(assertInstanceOf(reference.resolve(), PomTargetPsiElement::class.java).target,
                                  PathVariablePomTarget::class.java)
    assertTrue(target.scope.isPhysical)
    assertEquals("name", target.textRange.substring(target.scope.text))
  }

  fun testHelidon4HttpRouteBuilderHelperPathParameterReferenceFromPathArgument() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.route(Routes.route("/helper/{name}"));
        }

        static void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("na<caret>me");
        }
      }

      class Routes {
        static HttpRoute route(String path) {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path(path)
            .handler(Main::hello)
            .build();
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    val target = assertInstanceOf(assertInstanceOf(reference.resolve(), PomTargetPsiElement::class.java).target,
                                  PathVariablePomTarget::class.java)
    assertTrue(target.scope.isPhysical)
    assertEquals("name", target.textRange.substring(target.scope.text))
  }

  fun testHelidon4HttpRouteBuilderHelperPathParameterReferenceKeepsRegisteredServiceParentPath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
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
          rules.route(Routes.hello());
        }
      }

      class Routes {
        static HttpRoute hello() {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path("/helper/{name}")
            .handler(Routes::handle)
            .build();
        }

        static void handle(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("ten<caret>ant");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    val target = assertInstanceOf(assertInstanceOf(reference.resolve(), PomTargetPsiElement::class.java).target,
                                  PathVariablePomTarget::class.java)
    assertEquals("tenant", target.textRange.substring(target.scope.text))
  }

  fun testLegacyHttpRouteHelperPathParameterReferenceKeepsRegisteredServiceParentPath() {
    addLegacyAnyOfStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.common.http.Http;
      import io.helidon.webserver.HttpRoute;
      import io.helidon.webserver.Routing;
      import io.helidon.webserver.ServerRequest;
      import io.helidon.webserver.ServerResponse;
      import io.helidon.webserver.Service;

      class Main {
        static void routing(Routing.Builder routing) {
          routing.register("/api/{tenant}", new GreetingService());
        }
      }

      class GreetingService implements Service {
        @Override
        public void update(Routing.Rules rules) {
          rules.route(Routes.legacy());
        }
      }

      class Routes {
        static HttpRoute legacy() {
          return HttpRoute.route(Http.Method.GET, "/legacy/{name}", Routes::handle);
        }

        static void handle(ServerRequest request, ServerResponse response) {
          request.path().absolute().param("ten<caret>ant");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    val target = assertInstanceOf(assertInstanceOf(reference.resolve(), PomTargetPsiElement::class.java).target,
                                  PathVariablePomTarget::class.java)
    assertEquals("tenant", target.textRange.substring(target.scope.text))
  }

  fun testLegacyHttpRouteHelperRelativePathParameterReferenceDoesNotResolveRegisteredServiceParentPath() {
    addLegacyAnyOfStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.common.http.Http;
      import io.helidon.webserver.HttpRoute;
      import io.helidon.webserver.Routing;
      import io.helidon.webserver.ServerRequest;
      import io.helidon.webserver.ServerResponse;
      import io.helidon.webserver.Service;

      class Main {
        static void routing(Routing.Builder routing) {
          routing.register("/api/{tenant}", new GreetingService());
        }
      }

      class GreetingService implements Service {
        @Override
        public void update(Routing.Rules rules) {
          rules.route(Routes.legacy());
        }
      }

      class Routes {
        static HttpRoute legacy() {
          return HttpRoute.route(Http.Method.GET, "/legacy/{name}", Routes::handle);
        }

        static void handle(ServerRequest request, ServerResponse response) {
          request.path().param("ten<caret>ant");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNull(reference.resolve())
  }

  fun testNestedHttpRouteBuilderHelperPathParameterReferenceKeepsRegisteredServiceParentPath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
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
          rules.route(Routes.outer());
        }
      }

      class Routes {
        static HttpRoute outer() {
          return inner();
        }

        static HttpRoute inner() {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path("/helper/{name}")
            .handler(Routes::handle)
            .build();
        }

        static void handle(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("ten<caret>ant");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    val target = assertInstanceOf(assertInstanceOf(reference.resolve(), PomTargetPsiElement::class.java).target,
                                  PathVariablePomTarget::class.java)
    assertEquals("tenant", target.textRange.substring(target.scope.text))
  }

  fun testRouteObjectVariableHelperPathParameterReferenceKeepsRegisteredServiceParentPath() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
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
          HttpRoute route = Routes.hello();
          rules.route(route);
        }
      }

      class Routes {
        static HttpRoute hello() {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path("/helper/{name}")
            .handler(Routes::handle)
            .build();
        }

        static void handle(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("ten<caret>ant");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    val target = assertInstanceOf(assertInstanceOf(reference.resolve(), PomTargetPsiElement::class.java).target,
                                  PathVariablePomTarget::class.java)
    assertEquals("tenant", target.textRange.substring(target.scope.text))
  }

  fun testNestedLambdaReturnedHelperDoesNotResolveParentPathParameter() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
      import io.helidon.webserver.http.HttpRoute;
      import io.helidon.webserver.http.HttpRouting;
      import io.helidon.webserver.http.HttpRules;
      import io.helidon.webserver.http.HttpService;
      import io.helidon.webserver.http.ServerRequest;
      import io.helidon.webserver.http.ServerResponse;
      import java.util.function.Supplier;

      class Main {
        static void routing(HttpRouting.Builder routing) {
          routing.register("/api/{tenant}", new GreetingService());
        }
      }

      class GreetingService implements HttpService {
        @Override
        public void routing(HttpRules rules) {
          rules.route(Routes.outer());
        }
      }

      class Routes {
        static HttpRoute outer() {
          Supplier<HttpRoute> ignored = () -> {
            return inner();
          };
          return other();
        }

        static HttpRoute other() {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path("/other")
            .handler((req, res) -> {})
            .build();
        }

        static HttpRoute inner() {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path("/helper/{name}")
            .handler(Routes::handle)
            .build();
        }

        static void handle(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("ten<caret>ant");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNull(reference.resolve())
  }

  fun testNestedHelperUsageInsideUnrelatedRouteDoesNotResolveParentPathParameter() {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Method;
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
          rules.route(Routes.decorated(Routes.hello()));
        }
      }

      class Routes {
        static HttpRoute decorated(HttpRoute ignored) {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path("/decorated")
            .handler((req, res) -> {})
            .build();
        }

        static HttpRoute hello() {
          return HttpRoute.builder()
            .methods(Method.GET)
            .path("/helper/{name}")
            .handler(Routes::handle)
            .build();
        }

        static void handle(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("ten<caret>ant");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNull(reference.resolve())
  }

  fun testPathlessHelidon4RouteResolvesParentPathParameterReference() {
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
          rules.get(this::hello);
        }

        void hello(ServerRequest request, ServerResponse response) {
          request.path().pathParameters().get("ten<caret>ant");
        }
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertNotNull(reference.resolve())
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

  fun testRestServerEndpointMethodsAreDiscovered() {
    addHelidonDeclarativeStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Http;
      import io.helidon.service.registry.Service;
      import io.helidon.webserver.http.RestServer;

      @RestServer.Endpoint
      @Http.Path("/greet")
      @Service.Singleton
      class GreetingEndpoint {
        @Http.GET
        @Http.Path("/{name}")
        String get(@Http.PathParam("name") String name) {
          return name;
        }

        @Http.POST
        String create() {
          return "created";
        }
      }
    """.trimIndent())

    val endpoints = collectRestServerEndpoints()

    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.GET && it.parentUrl == "/greet" && it.urlDefinition == "/{name}"
    })
    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.POST && it.parentUrl == "/greet" && it.urlDefinition == "/"
    })
  }

  fun testRestServerEndpointUsesInterfacePathsAndMethods() {
    addHelidonDeclarativeStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.http.Http;
      import io.helidon.service.registry.Service;
      import io.helidon.webserver.http.RestServer;

      @Http.Path("/greet")
      interface GreetingResource {
        @Http.PUT
        @Http.Path("/greeting")
        void update(String greeting);
      }

      @RestServer.Endpoint
      @Service.Singleton
      class GreetingEndpoint implements GreetingResource {
        public void update(String greeting) {
        }
      }
    """.trimIndent())

    val endpoints = collectRestServerEndpoints()

    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.PUT && it.parentUrl == "/greet" && it.urlDefinition == "/greeting"
    })
  }

  fun testRestServerEndpointUsesCustomHttpMethodMetaAnnotation() {
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
        String properties(@Http.PathParam("path") String path) {
          return path;
        }
      }
    """.trimIndent())

    val endpoints = collectRestServerEndpoints()

    assertTrue(endpoints.any {
      it.type == HelidonRequestMethods.UNKNOWN &&
      it.methods == setOf("PROPFIND") &&
      it.parentUrl == "/files" &&
      it.urlDefinition == "/{path}"
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

  private fun collectRestServerEndpoints(): Collection<HelidonUrlTargetInfo> {
    val processor = CollectProcessor<HelidonUrlTargetInfo>()
    val module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!
    assertTrue(HelidonCommonUtils.processRestServerEndpointMethods(processor, GlobalSearchScope.fileScope(myFixture.file), module))
    return processor.results
  }

  private fun endpointSummary(endpoints: Collection<HelidonUrlTargetInfo>): String {
    return endpoints.joinToString("\n") {
      "${it.type} parent=${it.parentUrl} path=${it.urlDefinition} " +
        "methods=${it.methods} " +
        "semantics=${it.pathSemantics} " +
        "exactBob=${it.matchesPath(UrlPath.fromExactString("/exact/bob"))} " +
        "prefixBob=${it.matchesPath(UrlPath.fromExactString("/prefix/bob"))} " +
        "createdFile=${it.matchesPath(UrlPath.fromExactString("/created/file"))} " +
        "source=${it.resolveToPsiElement()?.textRange}"
    }
  }

  private fun assertAnyOfEndpointMethods(endpoints: Collection<HelidonUrlTargetInfo>,
                                         urlDefinition: String,
                                         methods: Set<String>) {
    val anyOfEndpoints = endpoints.filter { it.type == HelidonRequestMethods.ANY_OF && it.urlDefinition == urlDefinition }
    assertFalse(anyOfEndpoints.isEmpty())
    assertTrue(anyOfEndpoints.all { it.methods == methods })
  }

  private fun pathMatcherMethod(pathMatchers: PsiClass, name: String) =
    pathMatchers.methods.single {
      it.name == name &&
      it.parameterList.parameters.singleOrNull()?.type?.equalsToText("java.lang.String") == true
    }

  private fun assertPathMatcherDeclarationDoesNotResolvePathVariable(factoryMethod: String) {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.PathMatchers;

      class Main {
        static final Object MATCHER = PathMatchers.$factoryMethod("/hello/{na<caret>me}");
      }
    """.trimIndent())

    val resolved = myFixture.getReferenceAtCaretPosition()?.resolve()
    assertFalse(resolved is PomTargetPsiElement && resolved.target is PathVariablePomTarget)
  }

  private fun assertPathMatcherFactoryHasUrlReference(factoryMethod: String) {
    myFixture.configureByText("Main.java", """
      import io.helidon.http.PathMatchers;

      class Main {
        static final Object MATCHER = PathMatchers.$factoryMethod("/hello/{na<caret>me}");
      }
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    val resolved = reference.resolve()
    assertFalse(resolved is PomTargetPsiElement && resolved.target is PathVariablePomTarget)
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

  private fun addHelidon4SourceRouteStubs() {
    myFixture.addClass("""
      package io.helidon.http;

      public final class Method {
        private static final String GET_NAME = "GET";
        private static final String POST_NAME = "POST";

        public static final Method GET = new Method(GET_NAME, true);
        public static final Method POST = new Method(POST_NAME, true);

        private final String name;

        private Method(String name, boolean safe) {
          this.name = name;
        }

        public String name() {
          return name;
        }

        public static Method create(String name) {
          return new Method(name, false);
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver.http;

      public interface Handler {
        void handle(Object request, Object response);
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver.http;

      import io.helidon.http.Method;

      public interface HttpRoute {
        static Builder builder() {
          return null;
        }

        interface Builder extends java.util.function.Supplier<HttpRoute> {
          Builder methods(Method... methods);
          Builder path(String path);
          Builder handler(Handler handler);
          HttpRoute build();
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver.http;

      import io.helidon.http.Method;

      public interface HttpRules {
        HttpRules route(Method method, String path, Handler handler);
        HttpRules route(HttpRoute route);
        HttpRules route(java.util.function.Supplier<? extends HttpRoute> route);
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver.http;

      import io.helidon.http.Method;

      public interface HttpRouting {
        interface Builder extends HttpRules {
          Builder route(Method method, String path, Handler handler);
          Builder route(HttpRoute route);
          Builder route(java.util.function.Supplier<? extends HttpRoute> route);
        }
      }
    """.trimIndent())
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

      public interface ServerRequest {
        io.helidon.common.http.HttpRequest.Path path();
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver;

      public interface ServerResponse {
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.common.http;

      public interface HttpRequest {
        interface Path {
          Path absolute();
          String param(String name);
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver;

      public interface Handler {
        void accept(ServerRequest request, ServerResponse response);
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver;

      public interface Service {
        void update(Routing.Rules rules);
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver;

      public interface Routing {
        interface Rules {
          Rules anyOf(java.lang.Iterable<io.helidon.common.http.Http.RequestMethod> methods, String path, Handler... handlers);
          Rules route(HttpRoute route);
          Rules register(String path, Service... services);
        }

        interface Builder extends Rules {
          Builder anyOf(java.lang.Iterable<io.helidon.common.http.Http.RequestMethod> methods, String path, Handler... handlers);
          Builder route(HttpRoute route);
          Builder register(String path, Service... services);
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.webserver;

      import io.helidon.common.http.Http;

      public interface HttpRoute {
        static HttpRoute route(Http.Method method, String path, Handler handler) {
          return null;
        }
      }
    """.trimIndent())
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

        @Retention(RetentionPolicy.CLASS)
        @Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
        public @interface Inject {
        }

        @Retention(RetentionPolicy.CLASS)
        @Target(ElementType.TYPE)
        public @interface Contract {
        }

        @Retention(RetentionPolicy.CLASS)
        @Target(ElementType.TYPE)
        public @interface ExternalContracts {
          Class<?>[] value();
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        public @interface EntryPoint {
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

        @Retention(RetentionPolicy.CLASS)
        @Target(ElementType.PARAMETER)
        public @interface PathParam {
          String value();
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
