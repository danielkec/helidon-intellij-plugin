// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers.view

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.providers.HelidonRequestMethods
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.helidon.utils.HelidonUrlTargetInfo
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.search.GlobalSearchScope
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

  private fun collectBuilderEndpoints(): Collection<HelidonUrlTargetInfo> {
    val processor = CollectProcessor<HelidonUrlTargetInfo>()
    val module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!
    val scope = GlobalSearchScope.fileScope(myFixture.file)
    assertTrue(HelidonCommonUtils.processBuilderRegisterMethods(processor, scope, module))
    return processor.results
  }
}
