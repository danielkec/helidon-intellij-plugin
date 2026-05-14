// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.helidon.HelidonIcons
import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML
import com.intellij.helidon.langchain4j.HelidonLangChain4jJavaLineMarkerProvider
import com.intellij.helidon.langchain4j.HelidonLangChain4jYamlLineMarkerProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import javax.swing.Icon

class HelidonYamlLangChain4jConfigReferenceTest : HelidonHighlightingTestCase() {
  fun testServiceKeyResolvesToAiServiceInterface() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        services:
          gree<caret>ter:
            chat-model: chat
    """.trimIndent())

    assertResolvesToNamedElement("GreetingAiService")
  }

  fun testAgentKeyResolvesToAiAgentInterfaceFromIntegrationsPackage() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        agents:
          plan<caret>ner:
            chat-model: chat
    """.trimIndent())

    assertResolvesToNamedElement("PlanningAgent")
  }

  fun testBlankAiServiceNameResolvesFromInterfaceFqnConfigKey() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        services:
          demo.DefaultNamed<caret>AiService:
            chat-model: chat
    """.trimIndent())

    assertResolvesToNamedElement("DefaultNamedAiService")
  }

  fun testModelKeyResolvesToAiChatModelUsingConstantAnnotationValue() {
    addLangChain4jStubs()
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      interface ModelNames {
        String EXPENSIVE = "expensive-model";
      }

      @Ai.ChatModel(ModelNames.EXPENSIVE)
      interface ConstantNamedChatModel {
      }
    """.trimIndent())
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        models:
          expensive-<caret>model:
            provider: openai
    """.trimIndent())

    assertResolvesToNamedElement("ConstantNamedChatModel")
  }

  fun testModelProviderValueResolvesToProviderConfigKey() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        providers:
          openai:
            type: open-ai
        models:
          chat:
            provider: open<caret>ai
    """.trimIndent())

    val keyValue = assertInstanceOf(resolveAtCaret(), YAMLKeyValue::class.java)
    assertEquals("openai", keyValue.keyText)
  }

  fun testContentRetrieverValuesResolveToModelAndEmbeddingStoreConfigKeys() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        models:
          embedding:
            provider: openai
        embedding-stores:
          pgvector:
            provider: pg
        content-retrievers:
          docs:
            embedding-model: emb<caret>edding
            embedding-store: pgvector
    """.trimIndent())

    val keyValue = assertInstanceOf(resolveAtCaret(), YAMLKeyValue::class.java)
    assertEquals("embedding", keyValue.keyText)

    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        models:
          embedding:
            provider: openai
        embedding-stores:
          pgvector:
            provider: pg
        content-retrievers:
          docs:
            embedding-model: embedding
            embedding-store: pgv<caret>ector
    """.trimIndent())

    val embeddingStoreKey = assertInstanceOf(resolveAtCaret(), YAMLKeyValue::class.java)
    assertEquals("pgvector", embeddingStoreKey.keyText)
  }

  fun testEmbeddingModelValueDoesNotResolveToAiChatModelComponent() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      @Ai.ChatModel("embedding")
      interface EmbeddingChatModel {
      }
    """.trimIndent())
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        models:
          embedding:
            provider: openai
        content-retrievers:
          docs:
            embedding-model: emb<caret>edding
    """.trimIndent())

    assertResolvesToConfigKey("langchain4j.models.embedding")
    assertDoesNotResolveToNamedElement("EmbeddingChatModel")
  }

  fun testMcpClientKeyValueResolvesToAiMcpClientsUsage() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        mcp-clients:
          filesystem:
            key: filesys<caret>tem
    """.trimIndent())

    assertResolvesToNamedElement("PlanningAgent")
  }

  fun testClassValuedToolReferenceResolvesToJavaClass() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        agents:
          planner:
            tools:
              - Calendar<caret>Tools
    """.trimIndent())

    assertResolvesToNamedElement("CalendarTools")
  }

  fun testLangChain4jServiceKeyHasRobotGutterNavigation() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        services:
          gree<caret>ter:
            chat-model: chat
    """.trimIndent())

    val keyValue = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), YAMLKeyValue::class.java)!!
    val markers = mutableListOf<RelatedItemLineMarkerInfo<*>>()
    HelidonLangChain4jYamlLineMarkerProvider().collectNavigationMarkers(listOf(keyValue), markers, true)

    assertSize(1, markers)
    assertSame(HelidonIcons.RobotGutter, markers.single().icon)
  }

  fun testLangChain4jYamlGutterNavigationIgnoresNonApplicationYaml() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText("pipeline.yaml", """
      langchain4j:
        services:
          gree<caret>ter:
            chat-model: chat
    """.trimIndent())

    val keyValue = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), YAMLKeyValue::class.java)!!
    val markers = mutableListOf<RelatedItemLineMarkerInfo<*>>()
    HelidonLangChain4jYamlLineMarkerProvider().collectNavigationMarkers(listOf(keyValue), markers, true)

    assertEmpty(markers)
  }

  fun testLangChain4jModelReferenceHasRobotGutterNavigation() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        agents:
          planner:
            chat-model: ch<caret>at
        models:
          chat:
            provider: openai
    """.trimIndent())

    assertLangChain4jScalarGutter(HelidonIcons.RobotGutter)
  }

  fun testLangChain4jAgentKeyHasRobotGutterNavigation() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        agents:
          plan<caret>ner:
            chat-model: chat
    """.trimIndent())

    val keyValue = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), YAMLKeyValue::class.java)!!
    val markers = mutableListOf<RelatedItemLineMarkerInfo<*>>()
    HelidonLangChain4jYamlLineMarkerProvider().collectNavigationMarkers(listOf(keyValue), markers, true)

    assertSize(1, markers)
    assertSame(HelidonIcons.RobotGutter, markers.single().icon)
  }

  fun testLangChain4jContentRetrieverKeyHasRobotGutterNavigation() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      @Ai.ContentRetriever("docs")
      interface DocsRetriever {
      }
    """.trimIndent())
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        content-retrievers:
          do<caret>cs:
            embedding-model: embedding
    """.trimIndent())

    val keyValue = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), YAMLKeyValue::class.java)!!
    val markers = mutableListOf<RelatedItemLineMarkerInfo<*>>()
    HelidonLangChain4jYamlLineMarkerProvider().collectNavigationMarkers(listOf(keyValue), markers, true)

    assertSize(1, markers)
    assertSame(HelidonIcons.RobotGutter, markers.single().icon)
  }

  fun testLangChain4jContentRetrieverReferenceHasRobotGutterNavigation() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        agents:
          planner:
            content-retriever: do<caret>cs
        content-retrievers:
          docs:
            embedding-model: embedding
    """.trimIndent())

    assertLangChain4jScalarGutter(HelidonIcons.RobotGutter)
  }

  fun testLangChain4jProviderReferenceKeepsNavigationWithoutGutter() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        providers:
          openai:
            type: open-ai
        models:
          chat:
            provider: open<caret>ai
    """.trimIndent())

    assertResolvesToConfigKey("langchain4j.providers.openai")
    assertLangChain4jScalarHasNoGutter()
  }

  fun testLangChain4jEmbeddingStoreReferenceKeepsNavigationWithoutGutter() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        embedding-stores:
          pgvector:
            provider: pg
        content-retrievers:
          docs:
            embedding-store: pgv<caret>ector
    """.trimIndent())

    assertResolvesToConfigKey("langchain4j.embedding-stores.pgvector")
    assertLangChain4jScalarHasNoGutter()
  }

  fun testLangChain4jEmbeddingModelReferenceHasAiGutterNavigation() {
    addLangChain4jStubs()
    addLangChain4jApplicationClasses()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        models:
          embedding:
            provider: openai
        content-retrievers:
          docs:
            embedding-model: emb<caret>edding
    """.trimIndent())

    assertLangChain4jScalarGutter(HelidonIcons.AiGutter)
  }

  fun testAiServiceAnnotationValueResolvesToServiceConfigKey() {
    addLangChain4jStubs()
    configureLangChain4jConfig()
    myFixture.configureByText("Main.java", """
      import io.helidon.extensions.langchain4j.Ai;

      @Ai.Service("assistant-<caret>service")
      interface AssistantService {
      }
    """.trimIndent())

    assertResolvesToConfigKey("langchain4j.services.assistant-service")
  }

  fun testAiAgentAnnotationValueResolvesToAgentConfigKey() {
    addLangChain4jStubs()
    configureLangChain4jConfig()
    myFixture.configureByText("Main.java", """
      import io.helidon.integrations.langchain4j.Ai;

      @Ai.Agent("helidon-se-<caret>expert")
      interface HelidonSeExpert {
      }
    """.trimIndent())

    assertResolvesToConfigKey("langchain4j.agents.helidon-se-expert")
  }

  fun testAiChatModelAnnotationValueResolvesToModelConfigKey() {
    addLangChain4jStubs()
    configureLangChain4jConfig()
    myFixture.configureByText("Main.java", """
      import io.helidon.extensions.langchain4j.Ai;

      @Ai.ChatModel("expensive-<caret>model")
      interface HelidonSeExpert {
      }
    """.trimIndent())

    assertResolvesToConfigKey("langchain4j.models.expensive-model")
  }

  fun testAiStreamingChatModelAnnotationValueResolvesToModelConfigKey() {
    addLangChain4jStubs()
    configureLangChain4jConfig()
    myFixture.configureByText("Main.java", """
      import io.helidon.integrations.langchain4j.Ai;

      @Ai.StreamingChatModel("expensive-<caret>model")
      interface HelidonSeExpert {
      }
    """.trimIndent())

    assertResolvesToConfigKey("langchain4j.models.expensive-model")
  }

  fun testAiChatModelAnnotationValueHasAiGutterNavigation() {
    addLangChain4jStubs()
    configureLangChain4jConfig()
    myFixture.configureByText("Main.java", """
      import io.helidon.extensions.langchain4j.Ai;

      @Ai.ChatModel("expensive-<caret>model")
      interface HelidonSeExpert {
      }
    """.trimIndent())

    assertLangChain4jJavaAnnotationGutter(HelidonIcons.AiGutter)
  }

  fun testAiStreamingChatModelAnnotationValueHasAiGutterNavigation() {
    addLangChain4jStubs()
    configureLangChain4jConfig()
    myFixture.configureByText("Main.java", """
      import io.helidon.integrations.langchain4j.Ai;

      @Ai.StreamingChatModel("expensive-<caret>model")
      interface HelidonSeExpert {
      }
    """.trimIndent())

    assertLangChain4jJavaAnnotationGutter(HelidonIcons.AiGutter)
  }

  fun testAiContentRetrieverAnnotationValueResolvesToRetrieverConfigKey() {
    addLangChain4jStubs()
    configureLangChain4jConfig()
    myFixture.configureByText("Main.java", """
      import io.helidon.extensions.langchain4j.Ai;

      @Ai.ContentRetriever("se-content-<caret>retriever")
      interface HelidonSeExpert {
      }
    """.trimIndent())

    assertResolvesToConfigKey("langchain4j.content-retrievers.se-content-retriever")
  }

  fun testAiMcpClientsAnnotationValueResolvesToMcpClientConfigKey() {
    addLangChain4jStubs()
    configureLangChain4jConfig()
    myFixture.configureByText("Main.java", """
      import io.helidon.integrations.langchain4j.Ai;

      @Ai.McpClients({"cli-<caret>tools", "filesystem"})
      interface HelidonSeExpert {
      }
    """.trimIndent())

    assertResolvesToConfigKey("langchain4j.mcp-clients.cli-tools")
  }

  fun testAiChatMemoryProviderAnnotationValueResolvesToConfigValue() {
    addLangChain4jStubs()
    configureLangChain4jConfig()
    myFixture.configureByText("Main.java", """
      import io.helidon.extensions.langchain4j.Ai;

      @Ai.ChatMemoryProvider("conversation-<caret>memory")
      interface HelidonSeExpert {
      }
    """.trimIndent())

    assertResolvesToConfigValue("conversation-memory")
  }

  fun testAiToolProviderAnnotationValueResolvesToConfigValue() {
    addLangChain4jStubs()
    configureLangChain4jConfig()
    myFixture.configureByText("Main.java", """
      import io.helidon.integrations.langchain4j.Ai;

      @Ai.ToolProvider("cli-tool-<caret>provider")
      interface HelidonSeExpert {
      }
    """.trimIndent())

    assertResolvesToConfigValue("cli-tool-provider")
  }

  private fun assertResolvesToNamedElement(name: String) {
    val target = resolveTargetsAtCaret()
      .filterIsInstance<PsiNamedElement>()
      .firstOrNull { it.name == name }
    assertNotNull("Expected named target '$name', got ${resolvedTargetsAtCaretText()}", target)
  }

  private fun assertDoesNotResolveToNamedElement(name: String) {
    val target = resolveTargetsAtCaret()
      .filterIsInstance<PsiNamedElement>()
      .firstOrNull { it.name == name }
    assertNull("Did not expect named target '$name', got ${resolvedTargetsAtCaretText()}", target)
  }

  private fun assertResolvesToConfigKey(qualifiedName: String) {
    val target = resolveTargetsAtCaret()
      .filterIsInstance<YAMLKeyValue>()
      .firstOrNull { getQualifiedConfigKeyName(it) == qualifiedName }
    assertNotNull("Expected config key '$qualifiedName', got ${resolvedTargetsAtCaretText()}", target)
  }

  private fun assertResolvesToConfigValue(value: String) {
    val target = resolveTargetsAtCaret()
      .filterIsInstance<YAMLScalar>()
      .firstOrNull { it.textValue == value }
    assertNotNull("Expected config value '$value', got ${resolvedTargetsAtCaretText()}", target)
  }

  private fun assertLangChain4jScalarGutter(icon: Icon) {
    val scalar = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), YAMLScalar::class.java)!!
    val markers = mutableListOf<RelatedItemLineMarkerInfo<*>>()
    HelidonLangChain4jYamlLineMarkerProvider().collectNavigationMarkers(listOf(scalar), markers, true)

    assertSize(1, markers)
    assertSame(icon, markers.single().icon)
  }

  private fun assertLangChain4jScalarHasNoGutter() {
    val scalar = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), YAMLScalar::class.java)!!
    val markers = mutableListOf<RelatedItemLineMarkerInfo<*>>()
    HelidonLangChain4jYamlLineMarkerProvider().collectNavigationMarkers(listOf(scalar), markers, true)

    assertEmpty(markers)
  }

  private fun assertLangChain4jJavaAnnotationGutter(icon: Icon) {
    val literal = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), PsiLiteralExpression::class.java)!!
    val anchor = literal.firstChild ?: literal
    val markers = mutableListOf<RelatedItemLineMarkerInfo<*>>()
    HelidonLangChain4jJavaLineMarkerProvider().collectNavigationMarkers(listOf(anchor), markers, true)

    assertSize(1, markers)
    assertSame(icon, markers.single().icon)
  }

  private fun resolveAtCaret(): Any? {
    return resolveTargetsAtCaret().firstOrNull()
  }

  private fun resolveTargetsAtCaret(): List<PsiElement> {
    val element = myFixture.file.findElementAt(myFixture.caretOffset) ?: return emptyList()
    return generateSequence(element) { it.parent }
      .flatMap { parent ->
        parent.references.asSequence().filter { reference -> reference.coversCaret() }
      }
      .flatMap { reference ->
        if (reference is PsiPolyVariantReference) {
          reference.multiResolve(false).asSequence().mapNotNull { it.element }
        }
        else {
          sequenceOf(reference.resolve()).filterNotNull()
        }
      }
      .distinct()
      .toList()
  }

  private fun resolvedTargetsAtCaretText(): String {
    val targets = resolveTargetsAtCaret()
    if (targets.isEmpty()) return "no targets"
    return targets.joinToString { target -> "${target.javaClass.simpleName} '${target.text}'" }
  }

  private fun PsiReference.coversCaret(): Boolean {
    val relativeOffset = myFixture.caretOffset - element.textRange.startOffset
    return rangeInElement.contains(relativeOffset)
  }

  private fun addLangChain4jStubs() {
    addLangChain4jAiStub("io.helidon.extensions.langchain4j")
    addLangChain4jAiStub("io.helidon.integrations.langchain4j")
  }

  private fun configureLangChain4jConfig() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      langchain4j:
        services:
          assistant-service:
            chat-model: expensive-model
            chat-memory-provider: conversation-memory
            tool-provider: cli-tool-provider
        agents:
          helidon-se-expert:
            chat-model: expensive-model
            chat-memory-provider: conversation-memory
            content-retriever: se-content-retriever
            tool-provider: cli-tool-provider
            mcp-clients:
              - cli-tools
        models:
          expensive-model:
            provider: openai
        providers:
          openai:
            type: open-ai
        content-retrievers:
          se-content-retriever:
            embedding-model: expensive-model
        mcp-clients:
          cli-tools:
            key: cli-tools
          filesystem:
            key: filesystem
    """.trimIndent())
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
          boolean autoDiscovery() default true;
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Agent {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ChatModel {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface StreamingChatModel {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ChatMemoryProvider {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ModerationModel {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ContentRetriever {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface RetrievalAugmentor {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface ToolProvider {
          String value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface McpClients {
          String[] value() default {};
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Tools {
          Class<?>[] value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.PARAMETER})
        public @interface Tool {
        }
      }
    """.trimIndent())
  }

  private fun addLangChain4jApplicationClasses() {
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      @Ai.Service("greeter")
      @Ai.ChatModel("chat")
      interface GreetingAiService {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.extensions.langchain4j.Ai;

      @Ai.Service
      interface DefaultNamedAiService {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      import io.helidon.integrations.langchain4j.Ai;

      @Ai.Agent("planner")
      @Ai.McpClients("filesystem")
      interface PlanningAgent {
      }
    """.trimIndent())
    myFixture.addClass("""
      package demo;

      class CalendarTools {
      }
    """.trimIndent())
  }
}
