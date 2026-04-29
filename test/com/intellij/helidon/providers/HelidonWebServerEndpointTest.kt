// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.providers

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.utils.HelidonCommonUtils
import com.intellij.helidon.utils.HelidonUrlTargetInfo
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiClass
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

  private fun collectBuilderEndpoints(): Collection<HelidonUrlTargetInfo> {
    val processor = CollectProcessor<HelidonUrlTargetInfo>()
    val module = ModuleUtilCore.findModuleForPsiElement(myFixture.file)!!
    val scope = GlobalSearchScope.fileScope(myFixture.file)
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
}
