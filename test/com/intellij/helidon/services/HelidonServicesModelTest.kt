// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.services

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.HelidonIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.runBlocking
import javax.swing.tree.DefaultMutableTreeNode
import java.util.concurrent.TimeUnit

class HelidonServicesModelTest : HelidonHighlightingTestCase() {
  fun testToolWindowApplicabilityCanRunOnBackgroundThread() {
    val future = ApplicationManager.getApplication().executeOnPooledThread<Boolean> {
      runBlocking {
        HelidonServicesToolWindowFactory().isApplicableAsync(project)
      }
    }

    assertTrue(future.get(10, TimeUnit.SECONDS))
  }

  fun testRefreshRelevanceIgnoresUnrelatedJavaPropertiesAndYamlFiles() {
    assertFalse(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("Plain.java", """
      class Plain {
        void update() {
        }
      }
    """.trimIndent())))
    assertFalse(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("notes.properties", """
      message=hello
    """.trimIndent())))
    assertFalse(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("notes.yaml", """
      message: hello
    """.trimIndent())))
  }

  fun testRefreshRelevanceAcceptsServiceRegistryJavaFiles() {
    val serviceFile = myFixture.configureByText("GreetingService.java", """
      import io.helidon.service.registry.Service;

      @Service.Singleton
      class GreetingService {
      }
    """.trimIndent())
    val lookupFile = myFixture.configureByText("GreetingLookup.java", """
      import io.helidon.service.registry.Services;

      class GreetingLookup {
        void lookup() {
          Services.get(Greeting.class);
        }
      }

      interface Greeting {
      }
    """.trimIndent())

    assertTrue(HelidonServicesRefreshRelevance.isRelevant(serviceFile))
    assertTrue(HelidonServicesRefreshRelevance.isRelevant(lookupFile))
  }

  fun testRefreshRelevanceAcceptsHttpEndpointJavaFiles() {
    val endpointFile = myFixture.configureByText("GreetingEndpoint.java", """
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
    val routingFile = myFixture.configureByText("GreetingRouting.java", """
      import io.helidon.webserver.http.HttpRouting;

      class GreetingRouting {
        void routes(HttpRouting.Builder routing) {
          routing.get("/hello", (req, res) -> res.send("hello"));
        }
      }
    """.trimIndent())

    assertTrue(HelidonServicesRefreshRelevance.isRelevant(endpointFile))
    assertTrue(HelidonServicesRefreshRelevance.isRelevant(routingFile))
  }

  fun testRefreshRelevanceAcceptsLangChain4jJavaFiles() {
    val file = myFixture.configureByText("AssistantService.java", """
      import io.helidon.extensions.langchain4j.Ai;

      @Ai.Service("assistant")
      interface AssistantService {
      }
    """.trimIndent())

    assertTrue(HelidonServicesRefreshRelevance.isRelevant(file))
  }

  fun testRefreshRelevanceAcceptsOnlyLangChain4jApplicationConfigFiles() {
    assertTrue(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("application.yaml", """
      langchain4j:
        services:
          assistant:
            chat-model: assistant-model
    """.trimIndent())))
    assertTrue(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("application-dev.yaml", """
      langchain4j:
        models:
          assistant-model:
            provider: open-ai
    """.trimIndent())))
    assertTrue(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("application.properties", """
      langchain4j.services.assistant.chat-model=assistant-model
    """.trimIndent())))
    assertTrue(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("microprofile-config.properties", """
      langchain4j.models.assistant-model.provider=open-ai
    """.trimIndent())))
    assertFalse(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("application-other.yaml", """
      server:
        port: 8080
    """.trimIndent())))
    assertFalse(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("application-dev.properties", """
      server.port=8080
    """.trimIndent())))
    assertFalse(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("oci-config.yaml", """
      helidon:
        oci:
          profile: DEFAULT
    """.trimIndent())))
    assertFalse(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("other.yaml", """
      langchain4j:
        services:
          assistant:
            chat-model: assistant-model
    """.trimIndent())))
    assertFalse(HelidonServicesRefreshRelevance.isRelevant(myFixture.configureByText("other.properties", """
      langchain4j.services.assistant.chat-model=assistant-model
    """.trimIndent())))
  }

  fun testRefreshRelevanceKeepsKnownModelInputFilesRelevant() {
    val file = myFixture.configureByText("Tracked.java", """
      class Tracked {
      }
    """.trimIndent())

    assertTrue(HelidonServicesRefreshRelevance.isRelevant(file, setOf(file.virtualFile!!)))
  }

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
        it.details == "MissingService" &&
        it.status == HelidonServicesResolutionStatus.UNRESOLVED
    })
  }

  fun testResolvesWrappedInjectionPointTypes() {
    addServiceRegistryStubs()
    myFixture.configureByText("Main.java", """
      import java.util.List;
      import java.util.Optional;
      import java.util.function.Supplier;
      import io.helidon.service.registry.Service;

      interface Greeting {
      }

      @Service.Singleton
      class GreetingService implements Greeting {
      }

      @Service.Singleton
      class GreetingConsumer {
        @Service.Inject
        Optional<Greeting> optionalGreeting;

        @Service.Inject
        Supplier<Greeting> suppliedGreeting;

        @Service.Inject
        List<Greeting> allGreetings;

        @Service.Inject
        Supplier<List<Greeting>> suppliedGreetings;
      }
    """.trimIndent())

    val snapshot = HelidonServicesModel.collect(project)
    val injectionNodes = snapshot.nodes.filter { it.kind == HelidonServicesNodeKind.INJECTION_POINT }

    assertEquals(4, injectionNodes.size)
    assertTrue(injectionNodes.all { it.status == HelidonServicesResolutionStatus.RESOLVED })
    assertTrue(injectionNodes.all { it.details == "Greeting" })
  }

  fun testUsesServiceNamesForInjectionAndLookups() {
    addServiceRegistryStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.service.registry.Service;
      import io.helidon.service.registry.Services;

      interface Greeting {
      }

      @Service.Named("casual")
      @Service.Singleton
      class CasualGreetingService implements Greeting {
      }

      @Service.Named("formal")
      @Service.Singleton
      class FormalGreetingService implements Greeting {
      }

      @Service.Singleton
      class GreetingConsumer {
        @Service.Inject("formal")
        Greeting greeting;

        void lookup() {
          Services.getNamed(Greeting.class, "formal");
        }
      }
    """.trimIndent())

    val snapshot = HelidonServicesModel.collect(project)

    val injectionNode = snapshot.nodes.single {
      it.kind == HelidonServicesNodeKind.INJECTION_POINT && it.name == "greeting"
    }
    val lookupNode = snapshot.nodes.single {
      it.kind == HelidonServicesNodeKind.SERVICE_LOOKUP && it.name == "Greeting.class"
    }
    assertEquals(HelidonServicesResolutionStatus.RESOLVED, injectionNode.status)
    assertTrue(injectionNode.parentId?.contains("FormalGreetingService") == true)
    assertEquals("Greeting | name: formal", injectionNode.details)
    assertEquals(HelidonServicesResolutionStatus.RESOLVED, lookupNode.status)
    assertTrue(lookupNode.parentId?.contains("FormalGreetingService") == true)
    assertEquals("name: formal", lookupNode.details)
  }

  fun testCollectsMetaAnnotatedServiceScopes() {
    addServiceRegistryStubs()
    myFixture.configureByText("Main.java", """
      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;
      import io.helidon.service.registry.Service;

      @Retention(RetentionPolicy.CLASS)
      @Target(ElementType.TYPE)
      @Service.Singleton
      @interface ApplicationService {
      }

      @ApplicationService
      class GreetingService {
      }
    """.trimIndent())

    val snapshot = HelidonServicesModel.collect(project)

    assertTrue(snapshot.nodes.any {
      it.kind == HelidonServicesNodeKind.SERVICE &&
        it.name == "GreetingService"
    })
    assertTrue(snapshot.nodes.none {
      it.kind == HelidonServicesNodeKind.SERVICE &&
        it.name == "ApplicationService"
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
    assertEquals(servicesOnly.modules, wrongModule.modules)
    assertTrue(wrongModule.nodes.isEmpty())
  }

  fun testSkipsGeneratedServiceImplementationArtifacts() {
    addServiceRegistryStubs()
    myFixture.configureByText("Main.java", """
      import io.helidon.common.Generated;
      import io.helidon.service.registry.Service;

      @Service.Contract
      interface Agent {
      }

      class MissingService {
      }

      @Service.Singleton
      class UserAgent implements Agent {
      }

      @Generated("io.helidon.service.codegen.ServiceRegistryCodegen")
      @Service.Singleton
      class UserAgent__Generated implements java.util.function.Supplier<Agent> {
        @Service.Inject
        MissingService missing;

        public Agent get() {
          return null;
        }
      }
    """.trimIndent())

    val snapshot = HelidonServicesModel.collect(project)
    val problemSnapshot = HelidonServicesModel.collect(project, HelidonServicesFilter(showOnlyProblems = true))

    assertTrue(snapshot.nodes.any { it.kind == HelidonServicesNodeKind.SERVICE && it.name == "UserAgent" })
    assertTrue(snapshot.nodes.none { it.name.contains("__Generated") })
    assertTrue(problemSnapshot.nodes.none { it.kind == HelidonServicesNodeKind.INJECTION_POINT && it.name == "missing" })
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
        it.name == "@Ai.Service" &&
        it.details == "key: assistant" &&
        it.packageName == "demo" &&
        it.ownerClassName == "AssistantService" &&
        it.ownerClassQualifiedName == "demo.AssistantService"
    })
    assertTrue(snapshot.nodes.any {
      it.kind == HelidonServicesNodeKind.LANGCHAIN4J_CONFIG &&
        it.name == "assistant" &&
        it.details == "langchain4j.services" &&
        it.groupName == "services" &&
        it.groupSortOrder == 0
    })

    val componentsOnly = HelidonServicesModel.collect(
      project,
      HelidonServicesFilter(kind = HelidonServicesNodeKind.LANGCHAIN4J_COMPONENT),
    )
    assertTrue(componentsOnly.nodes.any { it.kind == HelidonServicesNodeKind.LANGCHAIN4J_COMPONENT })
    assertTrue(componentsOnly.nodes.none { it.kind == HelidonServicesNodeKind.LANGCHAIN4J_CONFIG })

    val configOnly = HelidonServicesModel.collect(
      project,
      HelidonServicesFilter(kind = HelidonServicesNodeKind.LANGCHAIN4J_CONFIG),
    )
    assertTrue(configOnly.nodes.any { it.kind == HelidonServicesNodeKind.LANGCHAIN4J_CONFIG })
    assertTrue(configOnly.nodes.none { it.kind == HelidonServicesNodeKind.LANGCHAIN4J_COMPONENT })
  }

  fun testGroupsLangChain4jConfigBySectionInTreeModel() {
    val root = DefaultMutableTreeNode("Helidon Services")
    val nodes = listOf(
      langChain4jConfigNode("assistant-embedding-model", "models"),
      langChain4jConfigNode("cheap-model", "models"),
      langChain4jConfigNode("open-ai", "providers"),
      langChain4jConfigNode("mp-content-retriever", "content-retrievers"),
    )

    HelidonServicesTreeModelBuilder.rebuildTree(root, HelidonServicesSnapshot(listOf("app"), nodes))

    val moduleNode = singleChild(root)
    val configNode = singleChild(moduleNode)
    assertEquals("app", moduleNode.userObject)
    assertEquals(HelidonServicesNodeKind.LANGCHAIN4J_CONFIG.presentableName, configNode.userObject)

    val groups = children(configNode).associateBy { (it.userObject as GroupNode).name }
    assertEquals(listOf("models", "providers", "content-retrievers"), groups.keys.toList())
    assertEquals(listOf("assistant-embedding-model", "cheap-model"), serviceNodeNames(groups.getValue("models")))
    assertEquals(listOf("open-ai"), serviceNodeNames(groups.getValue("providers")))
    assertEquals(listOf("mp-content-retriever"), serviceNodeNames(groups.getValue("content-retrievers")))
  }

  fun testLangChain4jConfigUsesSectionIcons() {
    assertSame(HelidonIcons.AiGutter, HelidonServicesModel.icon(langChain4jConfigNode("assistant-model", "models")))
    assertSame(HelidonIcons.AiGutter, HelidonServicesModel.icon(langChain4jConfigNode("open-ai", "providers")))
    assertSame(HelidonIcons.GearGutter,
               HelidonServicesModel.icon(langChain4jConfigNode("mp-content-retriever", "content-retrievers")))
    assertSame(HelidonIcons.DataSourceGutter,
               HelidonServicesModel.icon(langChain4jConfigNode("mp-embedding-store", "embedding-stores")))
    assertSame(HelidonIcons.RobotGutter,
               HelidonServicesModel.icon(langChain4jConfigNode("mcp", "mcp-clients")))
  }

  fun testProvidesPackageAndOwnerClassGroupingMetadata() {
    addServiceRegistryStubs()
    myFixture.addClass("""
      package demo.alpha;

      import io.helidon.service.registry.Service;
      import io.helidon.service.registry.Services;

      @Service.Contract
      interface Greeting {
      }

      @Service.Singleton
      class GreetingService implements Greeting {
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

    assertTrue(snapshot.nodes.any {
      it.kind == HelidonServicesNodeKind.SERVICE &&
        it.name == "GreetingService" &&
        it.packageName == "demo.alpha" &&
        it.ownerClassName == "GreetingService" &&
        it.ownerClassQualifiedName == "demo.alpha.GreetingService"
    })
    assertTrue(snapshot.nodes.any {
      it.kind == HelidonServicesNodeKind.INJECTION_POINT &&
        it.name == "greeting" &&
        it.packageName == "demo.alpha" &&
        it.ownerClassName == "GreetingConsumer" &&
        it.ownerClassQualifiedName == "demo.alpha.GreetingConsumer"
    })
    assertTrue(snapshot.nodes.any {
      it.kind == HelidonServicesNodeKind.SERVICE_LOOKUP &&
        it.name == "Greeting.class" &&
        it.packageName == "demo.alpha" &&
        it.ownerClassName == "GreetingConsumer" &&
        it.ownerClassQualifiedName == "demo.alpha.GreetingConsumer"
    })
  }

  private fun addServiceRegistryStubs() {
    myFixture.addClass("""
      package io.helidon.common;

      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Retention(RetentionPolicy.CLASS)
      @Target(ElementType.TYPE)
      public @interface Generated {
        String value();
        Class<?> trigger() default Void.class;
      }
    """.trimIndent())
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
          String value() default "";
          String name() default "";
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

        public static <T> T getNamed(Class<T> contract, String name) {
          return null;
        }
      }
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.service.registry;

      public interface ServiceRegistry {
        <T> T get(Class<T> contract);

        <T> T getNamed(Class<T> contract, String name);
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

  private fun langChain4jConfigNode(name: String, section: String): HelidonServicesNode =
    HelidonServicesNode(
      id = "langchain4j-config:app:$section:$name",
      kind = HelidonServicesNodeKind.LANGCHAIN4J_CONFIG,
      moduleName = "app",
      sourceSet = HelidonServicesSourceSet.MAIN,
      name = name,
      details = "langchain4j.$section",
      groupName = section,
      groupSortOrder = langChain4jConfigSectionOrder(section),
    )

  private fun langChain4jConfigSectionOrder(section: String): Int =
    when (section) {
      "services" -> 0
      "agents" -> 1
      "models" -> 2
      "providers" -> 3
      "embedding-stores" -> 4
      "content-retrievers" -> 5
      "mcp-clients" -> 6
      else -> Int.MAX_VALUE
    }

  private fun serviceNodeNames(parent: DefaultMutableTreeNode): List<String> =
    children(parent).map { (it.userObject as HelidonServicesNode).name }

  private fun singleChild(parent: DefaultMutableTreeNode): DefaultMutableTreeNode {
    assertEquals(1, parent.childCount)
    return parent.getChildAt(0) as DefaultMutableTreeNode
  }

  private fun children(parent: DefaultMutableTreeNode): List<DefaultMutableTreeNode> {
    val children = ArrayList<DefaultMutableTreeNode>()
    val enumeration = parent.children()
    while (enumeration.hasMoreElements()) {
      children.add(enumeration.nextElement() as DefaultMutableTreeNode)
    }
    return children
  }
}

class HelidonServicesModelNoHelidonTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor(): LightProjectDescriptor =
    DefaultLightProjectDescriptor(IdeaTestUtil::getMockJdk21)

  fun testToolWindowApplicabilityDoesNotDependOnResolvedHelidonLibraries() {
    val future = ApplicationManager.getApplication().executeOnPooledThread<Boolean> {
      runBlocking {
        HelidonServicesToolWindowFactory().isApplicableAsync(project)
      }
    }

    assertTrue(future.get(10, TimeUnit.SECONDS))
  }

  fun testNoOpOutsideHelidonProjects() {
    myFixture.configureByText("Plain.java", "class Plain {}")

    val snapshot = HelidonServicesModel.collect(project)

    assertTrue(snapshot.modules.isEmpty())
    assertTrue(snapshot.nodes.isEmpty())
  }
}
