// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config.yaml

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.helidon.config.HELIDON_OCI_CONFIG_YAML
import com.intellij.helidon.config.HELIDON_CONFIG_METADATA
import com.intellij.helidon.config.crossProviderMetadata
import com.intellij.helidon.config.envConfigMetadata
import com.intellij.helidon.config.isHelidonConfigFile
import com.intellij.helidon.config.isHelidonOciConfigFile
import com.intellij.helidon.config.secretServiceMetadata
import com.intellij.helidon.config.sharedClientMetadata
import com.intellij.microservices.jvm.config.MetaConfigKeyReference
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.nio.file.Files

class HelidonYamlOciConfigTest : HelidonHighlightingTestCase() {

  fun testRecognizesOciConfigYamlInMainAndTestResources() {
    addOciMetadataAndProviders()

    val mainConfig = myFixture.addFileToProject("src/main/resources/$HELIDON_OCI_CONFIG_YAML", "")
    val testConfig = myFixture.addFileToProject("src/test/resources/$HELIDON_OCI_CONFIG_YAML", "")

    assertTrue(isHelidonConfigFile(mainConfig))
    assertTrue(isHelidonOciConfigFile(mainConfig))
    assertTrue(isHelidonConfigFile(testConfig))
    assertTrue(isHelidonOciConfigFile(testConfig))
  }

  fun testDoesNotTreatOciConfigPropertiesAsHelidonConfig() {
    addOciMetadataAndProviders()

    val propertiesFile = myFixture.addFileToProject("src/main/resources/oci-config.properties", "")

    assertFalse(isHelidonConfigFile(propertiesFile))
  }

  fun testCompletesOciRootsOnly() {
    addOciMetadataAndProviders()
    myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
      helidon:
        <caret>
    """.trimIndent())
    myFixture.completeBasic()

    val lookupStrings = myFixture.lookupElementStrings!!
    assertContainsElements(lookupStrings, "oci", "oci-env", "oci-secret-service")
    assertDoesntContain(lookupStrings, "server", "security")
  }

  fun testCompletesPublicOciAuthenticationMetadata() {
    addOciMetadataAndProviders()
    myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
      helidon:
        oci:
          authentication:
            config-file:
              <caret>
    """.trimIndent())
    myFixture.completeBasic()

    assertContainsElements(myFixture.lookupElementStrings!!, "profile")
  }

  fun testCompletesProviderMetadataUnderForcedRoot() {
    addOciMetadataAndProviders()
    myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
      helidon:
        oci-env:
          location-override:
            <caret>
    """.trimIndent())
    myFixture.completeBasic()

    assertContainsElements(myFixture.lookupElementStrings!!, "region")
  }

  fun testCompletesNestedProviderMetadataUnderForcedRoot() {
    addOciMetadataAndProviders()
    myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
      helidon:
        oci-secret-service:
          client:
            retry-config:
              <caret>
    """.trimIndent())
    myFixture.completeBasic()

    assertContainsElements(myFixture.lookupElementStrings!!, "max-retries")
  }

  fun testCompletesProviderMetadataNestedTypeFromSeparateMetadataFile() {
    addCrossMetadataAndProvider()
    myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
      helidon:
        oci-cross:
          shared-client:
            <caret>
    """.trimIndent())
    myFixture.completeBasic()

    assertContainsElements(myFixture.lookupElementStrings!!, "endpoint")
  }

  fun testDocumentsProviderMetadataInOciConfigYaml() {
    addOciMetadataAndProviders()
    myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
      helidon:
        oci-secret-service:
          client:
            end<caret>point: "https://example.com"
    """.trimIndent())

    val keyValue = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), YAMLKeyValue::class.java)
    val doc = HelidonYamlDocumentationProvider().generateDoc(keyValue!!, keyValue.key)

    assertTrue(doc!!.contains("SSv2 &lt;endpoint&gt; template &amp; default"))
    assertTrue(doc.contains("https://secret-service-ce.${'$'}{oci.env.iaas-domain-name}/v1"))
    assertTrue(doc.contains("<p>Default:</p></td><td valign='top'><pre>https://secret-service-ce.${'$'}{oci.env.iaas-domain-name}/v1</pre></td></tr></table>"))
  }

  fun testUnknownApplicationRootDoesNotResolveInOciConfigYaml() {
    addOciMetadataAndProviders()
    myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
      ser<caret>ver:
        port: 8080
    """.trimIndent())

    val keyValue = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), YAMLKeyValue::class.java)
    assertNotNull(keyValue)
    assertNull(MetaConfigKeyReference.getResolvedMetaConfigKey(keyValue!!))
  }

  fun testPlaceholderReferencesWorkInOciConfigYaml() {
    addOciMetadataAndProviders()
    myFixture.configureByText(HELIDON_OCI_CONFIG_YAML, """
      oci:
        env:
          iaas-domain-name: "oraclegovcloud.com"
      helidon:
        oci-secret-service:
          client:
            endpoint: "https://secret-service-ce.${'$'}{oci.env.iaas-domain-name<caret>}/v1"
    """.trimIndent())

    val reference = myFixture.getReferenceAtCaretPositionWithAssertion()
    val keyValue = assertInstanceOf(reference.resolve(), YAMLKeyValue::class.java)
    assertEquals("iaas-domain-name", keyValue.keyText)
  }

  private fun addOciMetadataAndProviders() {
    addOciConfigStubs()
    addLibrary("public-oci-metadata", publicOciMetadata())
    addProviderLibrary(
      "oci-env-provider",
      "com.oracle.helidon.oci.envconfig.OciEnvConfigSourceProvider",
      envConfigMetadata(),
    )
    addProviderLibrary(
      "oci-secret-service-provider",
      "com.oracle.helidon.oci.secret.config.SecretServiceConfigSourceProvider",
      secretServiceMetadata(),
      metadataPath = "META-INF/$HELIDON_CONFIG_METADATA",
    )
  }

  private fun addCrossMetadataAndProvider() {
    addCrossConfigStubs()
    addLibrary("oci-shared-metadata", sharedClientMetadata())
    addProviderLibrary(
      "oci-cross-provider",
      "com.oracle.helidon.oci.cross.CrossConfigSourceProvider",
      crossProviderMetadata(),
    )
  }

  private fun addOciConfigStubs() {
    myFixture.addClass("""
      package io.helidon.integrations.oci;

      public final class OciConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.integrations.oci;

      public final class OciAuthenticationConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package io.helidon.integrations.oci;

      public final class OciAuthenticationConfigFileConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.envconfig;

      public final class OciEnvConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.envconfig;

      public final class OciEnvLocationOverride {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.envconfig;

      public final class OciEnvDynamicCoreRegions {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.envconfig;

      public final class OciEnvConfigSourceProvider {
        static final String TYPE = "oci-env";
      }
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.secret.config;

      public final class SecretServiceConfigSourceConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.secret.config;

      public final class Ssv2ClientConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.secret.config;

      public final class Ssv2RetryConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.secret.config;

      public final class Ssv2TlsConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.secret.config;

      public final class SecretServiceConfigSource {
        static final String TYPE = "oci-secret-service";
      }
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.secret.config;

      import java.util.Set;

      public final class SecretServiceConfigSourceProvider {
        private static final Set<String> SUPPORTED_TYPES = Set.of(SecretServiceConfigSource.TYPE);

        public Set<String> supported() {
          return SUPPORTED_TYPES;
        }
      }
    """.trimIndent())
  }

  private fun addCrossConfigStubs() {
    myFixture.addClass("""
      package com.oracle.helidon.oci.cross;

      public final class CrossConfig {}
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.cross;

      public final class CrossConfigSourceProvider {
        static final String TYPE = "oci-cross";
      }
    """.trimIndent())
    myFixture.addClass("""
      package com.oracle.helidon.oci.shared;

      public final class SharedClientConfig {}
    """.trimIndent())
  }

  private fun addLibrary(rootName: String,
                         metadata: String,
                         metadataPath: String = "META-INF/helidon/$HELIDON_CONFIG_METADATA") {
    val libraryRootPath = Files.createTempDirectory(rootName)
    val libraryRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRootPath)!!
    VfsTestUtil.createFile(libraryRoot, metadataPath, metadata)
    PsiTestUtil.addLibrary(myFixture.testRootDisposable,
                           module,
                           rootName,
                           libraryRootPath.parent.toString(),
                           libraryRootPath.fileName.toString())
  }

  private fun addProviderLibrary(rootName: String,
                                 providerClass: String,
                                 metadata: String,
                                 metadataPath: String = "META-INF/helidon/$HELIDON_CONFIG_METADATA") {
    val libraryRootPath = Files.createTempDirectory(rootName)
    val libraryRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRootPath)!!
    VfsTestUtil.createFile(libraryRoot,
                           "META-INF/services/io.helidon.config.spi.ConfigSourceProvider",
                           providerClass)
    VfsTestUtil.createFile(libraryRoot, metadataPath, metadata)
    PsiTestUtil.addLibrary(myFixture.testRootDisposable,
                           module,
                           rootName,
                           libraryRootPath.parent.toString(),
                           libraryRootPath.fileName.toString())
  }

  private fun publicOciMetadata(): String {
    return """
      [{
        "module": "helidon-integrations-oci",
        "types": [{
          "type": "io.helidon.integrations.oci.OciConfig",
          "standalone": true,
          "prefix": "helidon.oci",
          "options": [{
            "key": "authentication-method",
            "description": "OCI authentication method",
            "defaultValue": "auto"
          }, {
            "key": "allowed-authentication-methods",
            "type": "java.lang.String",
            "kind": "LIST",
            "description": "Allowed OCI authentication methods"
          }, {
            "key": "authentication",
            "type": "io.helidon.integrations.oci.OciAuthenticationConfig",
            "description": "OCI authentication settings"
          }, {
            "key": "region",
            "description": "OCI region"
          }, {
            "key": "tenant-id",
            "description": "OCI tenant id"
          }]
        }, {
          "type": "io.helidon.integrations.oci.OciAuthenticationConfig",
          "options": [{
            "key": "config-file",
            "type": "io.helidon.integrations.oci.OciAuthenticationConfigFileConfig",
            "description": "Config-file authentication settings"
          }]
        }, {
          "type": "io.helidon.integrations.oci.OciAuthenticationConfigFileConfig",
          "options": [{
            "key": "profile",
            "description": "OCI config profile"
          }]
        }]
      }]
    """.trimIndent()
  }
}
