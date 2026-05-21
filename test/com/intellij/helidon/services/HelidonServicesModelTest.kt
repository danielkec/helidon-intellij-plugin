// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.services

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class HelidonServicesModelTest : HelidonHighlightingTestCase() {
  fun testCollectsServiceContractsInjectionLookupsAndAmbiguousTargets() {
    addServiceRegistryStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.service.registry.Service;
      import io.helidon.service.registry.Services;

      @Service.Contract
      interface Greeting {
      }

      @Service.Singleton
      class GreetingService implements Greeting {
      }

      @Service.Singleton
      class FormalGreetingService implements Greeting {
      }

      @Service.Singleton
      class GreetingConsumer {
        @Service.Inject
        Greeting greeting;

        void lookup() {
          Services.get(Greeting.class);
        }
      }
    """.trimIndent())

    val snapshot = HelidonServicesModel.collect(project)

    assertTrue(snapshot.nodes.any { it.kind == HelidonServicesNodeKind.SERVICE && it.name == "GreetingService" })
    assertTrue(snapshot.nodes.any { it.kind == HelidonServicesNodeKind.SERVICE && it.name == "FormalGreetingService" })
    assertTrue(snapshot.nodes.any { it.kind == HelidonServicesNodeKind.CONTRACT && it.name == "Greeting" })
    assertTrue(snapshot.nodes.any {
      it.kind == HelidonServicesNodeKind.INJECTION_POINT &&
        it.name == "greeting" &&
        it.status == HelidonServicesResolutionStatus.AMBIGUOUS
    })
    assertTrue(snapshot.nodes.any {
      it.kind == HelidonServicesNodeKind.SERVICE_LOOKUP &&
        it.name == "Greeting.class" &&
        it.status == HelidonServicesResolutionStatus.AMBIGUOUS
    })
  }

  fun testSurfacesUnresolvedInjectionPointAndProblemFilter() {
    addServiceRegistryStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.service.registry.Service;

      class MissingService {
      }

      @Service.Singleton
      class GreetingConsumer {
        @Service.Inject
        MissingService missing;
      }
    """.trimIndent())

    val snapshot = HelidonServicesModel.collect(project, HelidonServicesFilter(showOnlyProblems = true))

    assertTrue(snapshot.nodes.all { it.status != HelidonServicesResolutionStatus.RESOLVED })
    assertTrue(snapshot.nodes.any {
      it.kind == HelidonServicesNodeKind.INJECTION_POINT &&
        it.name == "missing" &&
        it.status == HelidonServicesResolutionStatus.UNRESOLVED
    })
  }

  fun testKindAndModuleFilters() {
    addServiceRegistryStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.service.registry.Service;

      @Service.Singleton
      class GreetingService {
      }
    """.trimIndent())

    val servicesOnly = HelidonServicesModel.collect(project, HelidonServicesFilter(kind = HelidonServicesNodeKind.SERVICE))
    val wrongModule = HelidonServicesModel.collect(project, HelidonServicesFilter(moduleName = "missing"))

    assertTrue(servicesOnly.nodes.isNotEmpty())
    assertTrue(servicesOnly.nodes.all { it.kind == HelidonServicesNodeKind.SERVICE })
    assertTrue(wrongModule.nodes.isEmpty())
  }

  fun testCollectsHttpEndpointRowsFromOptionalContributor() {
    addServiceRegistryStubs()
    addRestServerEndpointStubs()
    myFixture.configureByText("GreetingEndpoint.java", """
      import io.helidon.http.Http;
      import io.helidon.webserver.http.RestServer;

      @RestServer.Endpoint
      class GreetingEndpoint {
        @Http.GET
        @Http.Path("/hello")
        String hello() {
          return "hello";
        }
      }
    """.trimIndent())

    val nodes = HelidonHttpServicesViewContributor().collect(module, HelidonServicesFilter())

    assertTrue(nodes.any {
      it.kind == HelidonServicesNodeKind.HTTP_ENDPOINT &&
        it.name == "/hello" &&
        it.details?.contains("GET") == true
    })
  }

  fun testCollectsLangChain4jComponentsAndConfig() {
    addLangChain4jStubs()
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      @Ai.Service("assistant")
      interface AssistantService {
      }

      @Ai.ChatModel("assistant-model")
      class AssistantModel {
      }
    """.trimIndent())
    myFixture.configureByText("application.yaml", """
      langchain4j:
        services:
          assistant:
            chat-model: assistant-model
        models:
          assistant-model:
            provider: open-ai
    """.trimIndent())

    val snapshot = HelidonServicesModel.collect(project)

    assertTrue(snapshot.nodes.any {
      it.kind == HelidonServicesNodeKind.LANGCHAIN4J_COMPONENT &&
        it.name == "demo.AssistantService" &&
        it.details?.contains("@Ai.Service") == true
    })
    assertTrue(snapshot.nodes.any {
      it.kind == HelidonServicesNodeKind.LANGCHAIN4J_CONFIG &&
        it.name == "assistant" &&
        it.details == "langchain4j.services"
    })
  }

  private fun addServiceRegistryStubs() {
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
        @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
        public @interface Provider {
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PARAMETER})
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
        @Target(ElementType.TYPE)
        public @interface Named {
          String value();
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.service.registry;

      public final class Services {
        private Services() {
        }

        public static <T> T get(Class<T> contract) {
          return null;
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.service.registry;

      public interface ServiceRegistry {
        <T> T get(Class<T> contract);
      }
    """.trimIndent())
  }

  private fun addRestServerEndpointStubs() {
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

  private fun addLangChain4jStubs() {
    addLangChain4jAiStub("io.helidon.extensions.langchain4j")
    addLangChain4jAiStub("io.helidon.integrations.langchain4j")
  }

  private fun addLangChain4jAiStub(packageName: String) {
    myFixture.addClass("""
      package ${packageName};

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      public final class Ai {
        private Ai() {
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Service {
          String value() default "";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Agent {
          String value() default "";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ChatModel {
          String value() default "";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface StreamingChatModel {
          String value() default "";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ChatMemoryProvider {
          String value() default "";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ModerationModel {
          String value() default "";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ContentRetriever {
          String value() default "";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface RetrievalAugmentor {
          String value() default "";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ToolProvider {
          String value() default "";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface McpClients {
          String[] value() default {};
        }
      }
    """.trimIndent())
  }
}

class HelidonServicesModelNoHelidonTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor(): LightProjectDescriptor =
    DefaultLightProjectDescriptor(IdeaTestUtil::getMockJdk21)

  fun testNoOpOutsideHelidonProjects() {
    myFixture.configureByText("Plain.java", "class Plain {}")

    val snapshot = HelidonServicesModel.collect(project)

    assertTrue(snapshot.modules.isEmpty())
    assertTrue(snapshot.nodes.isEmpty())
  }
}
