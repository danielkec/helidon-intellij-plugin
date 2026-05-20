// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.config.HELIDON_APPLICATION_YAML
import com.intellij.helidon.config.HelidonConfigFileModificationTracker
import com.intellij.helidon.config.HelidonConfigPlaceholderReference
import com.intellij.microservices.jvm.config.MetaConfigKeyReference
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.jetbrains.jsonSchema.extension.JsonWidgetSuppressor
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.util.*

@Suppress("SSBasedInspection")
@TestDataPath("\$CONTENT_ROOT/testData/config/yaml/")
class HelidonYamlConfigTest : HelidonHighlightingTestCase() {
  override fun getTestDirectory(): String = "/config/yaml/"

  fun testJsonWidgetIsSuppressed() {
    val virtualFile = myFixture.configureByText(HELIDON_APPLICATION_YAML, "anything").virtualFile!!
    val suppressors = JsonWidgetSuppressor.EXTENSION_POINT_NAME.extensions
    assertTrue(Arrays.stream(suppressors).anyMatch { s -> s.suppressSwitcherWidget(virtualFile, project) })
  }

  fun testInspectionAndAnnotatorHighlighting() {
    myFixture.enableInspections(HelidonYamlConfigInspection())
    val applicationYaml = myFixture.copyFileToProject("inspectionAndAnnotatorHighlighting.yml",
                                                      HELIDON_APPLICATION_YAML)
    myFixture.configureFromExistingVirtualFile(applicationYaml)
    myFixture.testHighlighting(true, true, true)
  }

  fun testParametrizedKeyHighlighting() {
    doHighlighting("""
      security:
        secrets:
          <info descr="REGULAR_ITALIC_ATTRIBUTES">dev</info>:
            name: "dev"
    """.trimIndent(), myFixture)
  }

  fun testConfigKeyReferenceResolve() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      server:
        shutdown-hook<caret>: false
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertEquals("shutdown-hook", reference.canonicalText)

    val configKeyReference = assertInstanceOf(reference, MetaConfigKeyReference::class.java)
    assertEquals("server.shutdown-hook: false", configKeyReference.referenceDisplayText)
    val resolve = configKeyReference.resolve()
    val configKeyDeclarationPsiElement = assertInstanceOf(resolve, PsiNamedElement::class.java)
    assertEquals("server.shutdown-hook", configKeyDeclarationPsiElement.name)
  }

  fun testConfigKeyReferenceRange() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      server:
        ho<caret>st: "localhost"
    """.trimIndent())

    val configKeyReference = assertInstanceOf(myFixture.getReferenceAtCaretPositionWithAssertion(), MetaConfigKeyReference::class.java)
    assertEquals(TextRange.create(0, 4), configKeyReference.rangeInElement)
  }

  fun testIndexedConfigKeyQualifiedName() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      items:
        - na<caret>me: first
    """.trimIndent())

    val keyValue = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), YAMLKeyValue::class.java)
    assertNotNull(keyValue)
    assertEquals("items[0].name", getQualifiedConfigKeyName(keyValue))
  }

  fun testMapValueSubKeyReferenceResolve() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      server:
        sockets:
          admin:
            po<caret>rt: 8080
    """.trimIndent())

    val reference = assertInstanceOf(myFixture.getReferenceAtCaretPositionWithAssertion(), MetaConfigKeyReference::class.java)
    val configKeyDeclarationPsiElement = assertInstanceOf(reference.resolve(), PsiNamedElement::class.java)
    assertEquals("port", configKeyDeclarationPsiElement.name)
  }

  fun testMapValueSubKeyCompletion() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      server:
        sockets:
          admin:
            p<caret>
    """.trimIndent())
    myFixture.completeBasic()

    assertContainsElements(myFixture.lookupElementStrings!!, "port")
  }

  fun testPlaceholderReferenceCompletion() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      my:
        host: "localhost"
      server:
        host: ${'$'}{<caret>}
    """.trimIndent())
    myFixture.completeBasic()

    assertContainsElements(myFixture.lookupElementStrings!!,
                           "my.host",)
  }

  fun testPlaceholderReferenceCompletionDoesNotOfferIndexedSequenceKeys() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      items:
        - name: first
      server:
        host: ${'$'}{<caret>}
    """.trimIndent())
    myFixture.completeBasic()

    val lookupStrings = myFixture.lookupElementStrings!!
    assertContainsElements(lookupStrings, "items")
    assertDoesntContain(lookupStrings, "items[0].name")
  }

  fun testPlaceholderReferenceCompletionWithNestedPrefix() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      server:
        concurrency-limit:
          fixed:
            permits: ${'$'}{proxy.<caret>}
      proxy:
        concurrency: 8
        read-timeout: PT5M
    """.trimIndent())
    myFixture.completeBasic()

    assertContainsElements(myFixture.lookupElementStrings!!,
                           "proxy.concurrency",
                           "proxy.read-timeout")
  }

  fun testPlaceholderReferenceCompletionInvalidatesAfterConfigFileChange() {
    val contributingFile = myFixture.addFileToProject("application-dev.yml", """
      old:
        key: before
    """.trimIndent())

    val applicationYaml = """
      server:
        host: ${'$'}{<caret>}
    """.trimIndent()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, applicationYaml)
    myFixture.completeBasic()

    var lookupStrings = myFixture.lookupElementStrings!!
    assertContainsElements(lookupStrings, "old.key")
    assertDoesntContain(lookupStrings, "new.key")

    val documentManager = PsiDocumentManager.getInstance(project)
    val document = documentManager.getDocument(contributingFile)!!
    WriteCommandAction.runWriteCommandAction(project) {
      document.setText("""
        new:
          key: after
      """.trimIndent())
    }
    documentManager.commitAllDocuments()

    myFixture.configureByText(HELIDON_APPLICATION_YAML, applicationYaml)
    myFixture.completeBasic()

    lookupStrings = myFixture.lookupElementStrings!!
    assertContainsElements(lookupStrings, "new.key")
    assertDoesntContain(lookupStrings, "old.key")
  }

  fun testPlaceholderReferenceCompletionDoesNotCacheYamlValuePresentation() {
    val contributingFile = myFixture.addFileToProject("application-dev.yml", """
      server:
        host: localhost
    """.trimIndent())

    val applicationYaml = """
      server:
        port: ${'$'}{<caret>}
    """.trimIndent()
    myFixture.configureByText(HELIDON_APPLICATION_YAML, applicationYaml)
    myFixture.completeBasic()
    assertNull(lookupPresentation("server.host").typeText)

    val documentManager = PsiDocumentManager.getInstance(project)
    val document = documentManager.getDocument(contributingFile)!!
    WriteCommandAction.runWriteCommandAction(project) {
      val valueStart = document.text.indexOf("localhost")
      document.replaceString(valueStart, valueStart + "localhost".length, "remotehost")
    }
    documentManager.commitAllDocuments()

    myFixture.configureByText(HELIDON_APPLICATION_YAML, applicationYaml)
    myFixture.completeBasic()
    assertNull(lookupPresentation("server.host").typeText)
  }

  fun testHelidonConfigFileModificationTrackerTracksOnlyConfigKeyChanges() {
    val javaFile = myFixture.addFileToProject("src/main/java/example/Main.java", """
      package example;

      import io.helidon.config.Config;

      class Main {
        void read(Config config) {
          config.get("${'$'}{server.host}");
        }
      }
    """.trimIndent())
    val tracker = HelidonConfigFileModificationTracker.getInstance(project)
    val documentManager = PsiDocumentManager.getInstance(project)
    val javaDocument = documentManager.getDocument(javaFile)!!
    val beforeJavaChange = tracker.modificationCount

    WriteCommandAction.runWriteCommandAction(project) {
      javaDocument.setText("""
        package example;

        import io.helidon.config.Config;

        class Main {
          void read(Config config) {
            config.get("${'$'}{server.port}");
          }
        }
      """.trimIndent())
    }
    documentManager.commitAllDocuments()

    assertEquals(beforeJavaChange, tracker.modificationCount)

    val configFile = myFixture.addFileToProject("application-dev.yml", """
      server:
        host: localhost
    """.trimIndent())
    tracker.track(configFile)
    val configDocument = documentManager.getDocument(configFile)!!
    val beforeValueChange = tracker.modificationCount

    WriteCommandAction.runWriteCommandAction(project) {
      val valueStart = configDocument.text.indexOf("localhost")
      configDocument.replaceString(valueStart, valueStart + "localhost".length, "remotehost")
    }
    documentManager.commitAllDocuments()

    assertEquals(beforeValueChange, tracker.modificationCount)

    val beforeKeyChange = tracker.modificationCount
    WriteCommandAction.runWriteCommandAction(project) {
      val keyStart = configDocument.text.indexOf("host")
      configDocument.replaceString(keyStart, keyStart + "host".length, "port")
    }
    documentManager.commitAllDocuments()

    assertTrue(tracker.modificationCount > beforeKeyChange)
  }

  private fun lookupPresentation(lookupString: String): LookupElementPresentation {
    val lookupElement = myFixture.lookupElements?.firstOrNull { it.lookupString == lookupString }
      ?: throw AssertionError("Expected lookup element '$lookupString', got ${myFixture.lookupElementStrings}")
    return LookupElementPresentation.renderElement(lookupElement)
  }

  fun testPlaceholderReferenceCompletionWithIncompleteNestedPrefix() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      server:
        concurrency-limit:
          fixed:
            permits: ${'$'}{proxy.<caret>
      proxy:
        concurrency: 8
        read-timeout: PT5M
    """.trimIndent())
    myFixture.completeBasic()

    assertContainsElements(myFixture.lookupElementStrings!!,
                           "proxy.concurrency",
                           "proxy.read-timeout")
  }

  fun testPlaceholderReferenceResolveToOtherKey() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      my:
        key: value
        ref:
          key: ${"$"}{my.<caret>key}
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    assertInstanceOf(reference, HelidonConfigPlaceholderReference::class.java)

    assertEquals("my.key", reference.canonicalText)
    val yamlKeyValue = assertInstanceOf(reference.resolve(), YAMLKeyValue::class.java)
    assertEquals("key", yamlKeyValue.keyText)
  }

  fun testNoPlaceholderReferenceInLiteralMultilineScalar() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      my:
        key: value
      server:
        host: |
          ${"$"}{my.<caret>key}
    """.trimIndent())

    assertNull(myFixture.getReferenceAtCaretPosition())
  }

  fun testNoPlaceholderReferenceInFoldedMultilineScalar() {
    myFixture.configureByText(HELIDON_APPLICATION_YAML, """
      my:
        key: value
      server:
        host: >
          ${"$"}{my.<caret>key}
    """.trimIndent())

    assertNull(myFixture.getReferenceAtCaretPosition())
  }

  companion object {
    fun doHighlighting(applicationYamlContent: String, codeInsightTestFixture: CodeInsightTestFixture) {
      codeInsightTestFixture.enableInspections(HelidonYamlConfigInspection())
      codeInsightTestFixture.configureByText(HELIDON_APPLICATION_YAML,
                                             applicationYamlContent.trimIndent())
      codeInsightTestFixture.testHighlighting(true, true, true)
    }
  }
}
