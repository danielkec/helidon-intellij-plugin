// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.config

import com.intellij.helidon.HelidonHighlightingTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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

  fun testDiscoversProviderTypeFromCompiledProviderWithoutWalkingCompiledPsi() {
    addCompiledProviderLibrary(
      "compiled-oci-secret-service-provider",
      "com.oracle.helidon.oci.secret.config.SecretServiceConfigSourceProvider",
      "oci-secret-service",
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

  private fun addCompiledProviderLibrary(rootName: String,
                                         providerClass: String,
                                         providerType: String,
                                         metadata: String,
                                         metadataPath: String = "META-INF/helidon/config-metadata.json") {
    val libraryRootPath = Files.createTempDirectory(rootName)
    val classFile = libraryRootPath.resolve(providerClass.replace('.', '/') + ".class")
    Files.createDirectories(classFile.parent)
    Files.write(classFile, compiledProviderClassBytes(providerClass.replace('.', '/'), providerType))

    val serviceFile = libraryRootPath.resolve("META-INF/services/io.helidon.config.spi.ConfigSourceProvider")
    Files.createDirectories(serviceFile.parent)
    Files.writeString(serviceFile, providerClass)
    val metadataFile = libraryRootPath.resolve(metadataPath)
    Files.createDirectories(metadataFile.parent)
    Files.writeString(metadataFile, metadata)

    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(libraryRootPath)!!
    PsiTestUtil.addLibrary(myFixture.testRootDisposable,
                           module,
                           rootName,
                           libraryRootPath.parent.toString(),
                           libraryRootPath.fileName.toString())
  }

  private fun compiledProviderClassBytes(internalName: String, providerType: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
      output.writeInt(0xCAFEBABE.toInt())
      output.writeShort(0)
      output.writeShort(52)
      output.writeShort(13)
      output.writeUtf8(internalName)
      output.writeByte(7)
      output.writeShort(1)
      output.writeUtf8("java/lang/Object")
      output.writeByte(7)
      output.writeShort(3)
      output.writeUtf8("<init>")
      output.writeUtf8("()V")
      output.writeUtf8("Code")
      output.writeByte(10)
      output.writeShort(4)
      output.writeShort(9)
      output.writeByte(12)
      output.writeShort(5)
      output.writeShort(6)
      output.writeUtf8("supported")
      output.writeUtf8("()Ljava/util/Set;")
      output.writeUtf8(providerType)

      output.writeShort(0x0031)
      output.writeShort(2)
      output.writeShort(4)
      output.writeShort(0)
      output.writeShort(0)

      output.writeShort(2)
      output.writeMethod(0x0001, 5, 6, byteArrayOf(0x2a, 0xb7.toByte(), 0x00, 0x08, 0xb1.toByte()))
      output.writeMethod(0x0001, 10, 11, byteArrayOf(0x01, 0xb0.toByte()))

      output.writeShort(0)
    }
    return bytes.toByteArray()
  }

  private fun DataOutputStream.writeUtf8(value: String) {
    writeByte(1)
    writeUTF(value)
  }

  private fun DataOutputStream.writeMethod(accessFlags: Int, nameIndex: Int, descriptorIndex: Int, code: ByteArray) {
    writeShort(accessFlags)
    writeShort(nameIndex)
    writeShort(descriptorIndex)
    writeShort(1)
    writeShort(7)
    writeInt(2 + 2 + 4 + code.size + 2 + 2)
    writeShort(1)
    writeShort(1)
    writeInt(code.size)
    write(code)
    writeShort(0)
    writeShort(0)
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

internal fun crossProviderMetadata(): String {
  return """
    [{
      "module": "helidon-oci-cross-provider",
      "types": [{
        "type": "com.oracle.helidon.oci.cross.CrossConfig",
        "options": [{
          "key": "shared-client",
          "type": "com.oracle.helidon.oci.shared.SharedClientConfig",
          "description": "Shared client settings"
        }]
      }]
    }]
  """.trimIndent()
}

internal fun sharedClientMetadata(): String {
  return """
    [{
      "module": "helidon-oci-shared-config",
      "types": [{
        "type": "com.oracle.helidon.oci.shared.SharedClientConfig",
        "options": [{
          "key": "endpoint",
          "description": "Shared client endpoint"
        }]
      }]
    }]
  """.trimIndent()
}
