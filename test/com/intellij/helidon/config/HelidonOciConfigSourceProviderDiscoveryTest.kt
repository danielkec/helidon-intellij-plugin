// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import java.nio.file.Files

class HelidonOciConfigSourceProviderDiscoveryTest : HelidonHighlightingTestCase() {

  fun testDiscoversDirectProviderTypeConstant() {
    myFixture.addClass("""
      package com.oracle.helidon.oci.envconfig;

      public final class OciEnvConfigSourceProvider {
        static final String TYPE = "oci-env";
      }
    """.trimIndent())
    addProviderLibrary(
      "oci-env-provider",
      "com.oracle.helidon.oci.envconfig.OciEnvConfigSourceProvider",
      envConfigMetadata(),
    )

    val providers = HelidonOciConfigSourceProviderDiscovery.getProviderMetadata(module)

    assertContainsElements(providers.map { it.type }, "oci-env")
  }

  fun testDiscoversProviderTypeFromReferencedConstant() {
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
    addProviderLibrary(
      "oci-secret-service-provider",
      "com.oracle.helidon.oci.secret.config.SecretServiceConfigSourceProvider",
      secretServiceMetadata(),
      metadataPath = "META-INF/config-metadata.json",
    )

    val providers = HelidonOciConfigSourceProviderDiscovery.getProviderMetadata(module)

    assertContainsElements(providers.map { it.type }, "oci-secret-service")
  }

  fun testIgnoresNonOciProviderPackage() {
    myFixture.addClass("""
      package example;

      public final class ExampleConfigSourceProvider {
        static final String TYPE = "oci-example";
      }
    """.trimIndent())
    addProviderLibrary(
      "non-oci-provider",
      "example.ExampleConfigSourceProvider",
      envConfigMetadata(),
    )

    val providers = HelidonOciConfigSourceProviderDiscovery.getProviderMetadata(module)

    assertDoesntContain(providers.map { it.type }, "oci-example")
  }

  private fun addProviderLibrary(rootName: String,
                                 providerClass: String,
                                 metadata: String,
                                 metadataPath: String = "META-INF/helidon/config-metadata.json") {
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
}

internal fun envConfigMetadata(): String {
  return """
    [{
      "module": "helidon-oci-envconfig",
      "types": [{
        "type": "com.oracle.helidon.oci.envconfig.OciEnvConfig",
        "options": [{
          "key": "prefix",
          "description": "Prefix used for the published config keys",
          "defaultValue": "oci.env"
        }, {
          "key": "location-override",
          "type": "com.oracle.helidon.oci.envconfig.OciEnvLocationOverride",
          "description": "Optional location override"
        }, {
          "key": "dynamic-core-regions",
          "type": "com.oracle.helidon.oci.envconfig.OciEnvDynamicCoreRegions",
          "description": "Dynamic core-regions import settings"
        }, {
          "key": "use-physical-availability-domain",
          "type": "java.lang.Boolean",
          "description": "Whether to read the physical availability-domain file",
          "defaultValue": "false"
        }]
      }, {
        "type": "com.oracle.helidon.oci.envconfig.OciEnvLocationOverride",
        "options": [{
          "key": "region",
          "description": "Region override value"
        }]
      }, {
        "type": "com.oracle.helidon.oci.envconfig.OciEnvDynamicCoreRegions",
        "options": [{
          "key": "enabled",
          "type": "java.lang.Boolean",
          "description": "Whether dynamic core-regions import is enabled",
          "defaultValue": "true"
        }]
      }]
    }]
  """.trimIndent()
}

internal fun secretServiceMetadata(): String {
  return """
    [{
      "module": "helidon-oci-secret-service-config-source",
      "types": [{
        "type": "com.oracle.helidon.oci.secret.config.SecretServiceConfigSourceConfig",
        "options": [{
          "key": "prefix",
          "description": "Config key prefix used by this source",
          "defaultValue": "oci.ssv2"
        }, {
          "key": "cache-ttl",
          "type": "java.time.Duration",
          "description": "Source-level cache TTL for tracked secret values",
          "defaultValue": "PT5M"
        }, {
          "key": "client",
          "type": "com.oracle.helidon.oci.secret.config.Ssv2ClientConfig",
          "description": "SSv2 client settings"
        }]
      }, {
        "type": "com.oracle.helidon.oci.secret.config.Ssv2ClientConfig",
        "options": [{
          "key": "endpoint",
          "description": "SSv2 <endpoint> template & default",
          "defaultValue": "https://secret-service-ce.${'$'}{oci.env.iaas-domain-name}/v1"
        }, {
          "key": "retry-config",
          "type": "com.oracle.helidon.oci.secret.config.Ssv2RetryConfig",
          "description": "Retry configuration"
        }, {
          "key": "tls-config",
          "type": "com.oracle.helidon.oci.secret.config.Ssv2TlsConfig",
          "description": "TLS configuration"
        }]
      }, {
        "type": "com.oracle.helidon.oci.secret.config.Ssv2RetryConfig",
        "options": [{
          "key": "max-retries",
          "type": "java.lang.Integer",
          "description": "Maximum retry attempts",
          "defaultValue": "3"
        }]
      }, {
        "type": "com.oracle.helidon.oci.secret.config.Ssv2TlsConfig",
        "options": [{
          "key": "ca-bundle",
          "description": "Path to the CA bundle used by the SSv2 client"
        }]
      }]
    }]
  """.trimIndent()
}
