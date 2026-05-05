// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.newproject

import com.intellij.ide.starters.local.GeneratorEmptyDirectory
import com.intellij.ide.starters.local.GeneratorFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelidonStarterClientTest {
  @Test
  fun generateReadsMetadataAndStarterZip() {
    val starterUrl = "https://starter.test/starter"
    val metadataUrl = "https://starter.test/api/9.9.9"
    val generateUrl = "https://starter.test/api/9.9.9/generate?flavor=se&app-type=quickstart&build-system=maven&groupId=com.example&artifactId=demo&version=1.0-SNAPSHOT&package=com.example.demo&media=json&media.json-lib=jsonp&db=false&security=false&metrics=true&metrics.builtin=true&health=true&health.builtin=true&tracing=false&docker=true&docker.native-image=true&docker.jlink-image=true&k8s=true&jpms=false"
    val responses = mapOf(
      starterUrl to Response.redirect("/starter/9.9.9"),
      metadataUrl to Response(200, starterMetadata().toByteArray(StandardCharsets.UTF_8)),
      generateUrl to Response(200, starterZip())
    )
    val requestedUrls = mutableListOf<String>()
    val client = HelidonStarterClient(
      starterRoot = starterUrl,
      apiRoot = "https://starter.test/api"
    ) { url ->
      requestedUrls.add(url.toString())
      FakeHttpURLConnection(url, responses.getValue(url.toString()))
    }

    val project = client.generate(
      HelidonStarterRequest(
        groupId = "com.example",
        artifactId = "demo",
        projectVersion = "1.0-SNAPSHOT",
        packageName = "com.example.demo"
      )
    )

    val paths = project.assets.map { it.relativePath }
    assertTrue(paths.contains("pom.xml"))
    assertTrue(paths.contains("src/main/java/com/example/demo/GreetResource.java"))
    assertTrue(paths.contains("mvnw"))
    assertTrue(project.assets.any { it is GeneratorEmptyDirectory && it.relativePath == "src/main/resources/empty" })
    assertFalse(paths.any { it.startsWith(".idea/") })
    assertEquals(listOf("pom.xml", "src/main/java/com/example/demo/GreetResource.java"), project.filesToOpen)

    val pom = project.assets.filterIsInstance<GeneratorFile>().single { it.relativePath == "pom.xml" }
    assertArrayEquals("starter pom".toByteArray(StandardCharsets.UTF_8), pom.content)
    val mvnw = project.assets.single { it.relativePath == "mvnw" }
    assertFalse(mvnw.permissions.isEmpty())
    assertEquals(listOf(starterUrl, metadataUrl, generateUrl), requestedUrls)
  }

  @Test
  fun generateAppliesDatabaseApplicationTypePreset() {
    val starterUrl = "https://starter.test/starter"
    val metadataUrl = "https://starter.test/api/9.9.9"
    val generateUrl = "https://starter.test/api/9.9.9/generate?flavor=se&app-type=database&build-system=maven&groupId=com.example&artifactId=demo&version=1.0-SNAPSHOT&package=com.example.demo&media=json&media.json-lib=jackson&db=true&db.server=mysql&security=false&metrics=true&metrics.builtin=true&health=true&health.builtin=true&tracing=false&docker=true&docker.native-image=true&docker.jlink-image=true&k8s=true&jpms=false"
    val responses = mapOf(
      starterUrl to Response.redirect("/starter/9.9.9"),
      metadataUrl to Response(200, starterMetadata().toByteArray(StandardCharsets.UTF_8)),
      generateUrl to Response(200, starterZip())
    )
    val requestedUrls = mutableListOf<String>()
    val client = HelidonStarterClient(
      starterRoot = starterUrl,
      apiRoot = "https://starter.test/api"
    ) { url ->
      requestedUrls.add(url.toString())
      FakeHttpURLConnection(url, responses.getValue(url.toString()))
    }

    client.generate(
      HelidonStarterRequest(
        groupId = "com.example",
        artifactId = "demo",
        projectVersion = "1.0-SNAPSHOT",
        packageName = "com.example.demo",
        options = HelidonStarterOptions(
          appType = HELIDON_DATABASE_APP_TYPE,
          database = false,
          databaseServer = "mysql",
          jsonLibrary = "jackson"
        )
      )
    )

    assertEquals(listOf(starterUrl, metadataUrl, generateUrl), requestedUrls)
  }

  @Test
  fun generateFiltersInvalidMpDatabaseOptions() {
    val starterUrl = "https://starter.test/starter"
    val metadataUrl = "https://starter.test/api/9.9.9"
    val generateUrl = "https://starter.test/api/9.9.9/generate?flavor=mp&app-type=database&build-system=maven&groupId=com.example&artifactId=demo&version=1.0-SNAPSHOT&package=com.example.demo&media=json&media.json-lib=jsonb&db=true&db.jpa-impl=eclipselink&db.cp=ucp&db.server=h2&db.auto-ddl=true&db.pu-name=inventory&db.ds-name=inventoryDs&security=false&metrics=true&metrics.provider=microprofile&metrics.builtin=true&health=true&health.builtin=true&tracing=false&docker=true&docker.native-image=true&docker.jlink-image=true&k8s=true&jpms=false"
    val responses = mapOf(
      starterUrl to Response.redirect("/starter/9.9.9"),
      metadataUrl to Response(200, starterMetadata().toByteArray(StandardCharsets.UTF_8)),
      generateUrl to Response(200, starterZip())
    )
    val requestedUrls = mutableListOf<String>()
    val client = HelidonStarterClient(
      starterRoot = starterUrl,
      apiRoot = "https://starter.test/api"
    ) { url ->
      requestedUrls.add(url.toString())
      FakeHttpURLConnection(url, responses.getValue(url.toString()))
    }

    client.generate(
      HelidonStarterRequest(
        groupId = "com.example",
        artifactId = "demo",
        projectVersion = "1.0-SNAPSHOT",
        packageName = "com.example.demo",
        options = HelidonStarterOptions(
          flavor = HELIDON_MP_FLAVOR,
          appType = HELIDON_DATABASE_APP_TYPE,
          jsonLibrary = "jsonp",
          databaseServer = "mongodb",
          jpaImplementation = "eclipselink",
          connectionPool = "ucp",
          autoDdl = true,
          persistenceUnitName = "inventory",
          dataSourceName = "inventoryDs",
          extras = listOf("webclient", "cors")
        )
      )
    )

    assertEquals(listOf(starterUrl, metadataUrl, generateUrl), requestedUrls)
  }

  @Test
  fun generateUsesOciApplicationTypePreset() {
    val starterUrl = "https://starter.test/starter"
    val metadataUrl = "https://starter.test/api/9.9.9"
    val generateUrl = "https://starter.test/api/9.9.9/generate?flavor=mp&app-type=oci&build-system=maven&groupId=com.example&artifactId=demo&version=1.0-SNAPSHOT&package=com.example.demo&db=false&docker=true&docker.native-image=false&docker.jlink-image=false&k8s=true&jpms=false"
    val responses = mapOf(
      starterUrl to Response.redirect("/starter/9.9.9"),
      metadataUrl to Response(200, starterMetadata().toByteArray(StandardCharsets.UTF_8)),
      generateUrl to Response(200, starterZip())
    )
    val requestedUrls = mutableListOf<String>()
    val client = HelidonStarterClient(
      starterRoot = starterUrl,
      apiRoot = "https://starter.test/api"
    ) { url ->
      requestedUrls.add(url.toString())
      FakeHttpURLConnection(url, responses.getValue(url.toString()))
    }

    client.generate(
      HelidonStarterRequest(
        groupId = "com.example",
        artifactId = "demo",
        projectVersion = "1.0-SNAPSHOT",
        packageName = "com.example.demo",
        options = HelidonStarterOptions(
          flavor = HELIDON_MP_FLAVOR,
          appType = HELIDON_OCI_APP_TYPE,
          media = listOf("json"),
          security = true,
          extras = listOf("cors"),
          dockerNativeImage = true,
          kubernetes = false
        )
      )
    )

    assertEquals(listOf(starterUrl, metadataUrl, generateUrl), requestedUrls)
  }

  @Test(expected = HelidonStarterUnsupportedException::class)
  fun generateRejectsMetadataWithoutMavenSupport() {
    val starterUrl = "https://starter.test/starter"
    val responses = mapOf(
      starterUrl to Response.redirect("https://starter.test/starter/9.9.9"),
      "https://starter.test/api/9.9.9" to Response(200, starterMetadata(buildSystem = "gradle").toByteArray(StandardCharsets.UTF_8))
    )
    val client = HelidonStarterClient(
      starterRoot = starterUrl,
      apiRoot = "https://starter.test/api"
    ) { url ->
      FakeHttpURLConnection(url, responses.getValue(url.toString()))
    }

    client.generate(
      HelidonStarterRequest(
        groupId = "com.example",
        artifactId = "demo",
        projectVersion = "1.0-SNAPSHOT",
        packageName = "com.example.demo"
      )
    )
  }

  @Test(expected = HelidonStarterUnsupportedException::class)
  fun generateRejectsMetadataWithoutSeSupport() {
    val starterUrl = "https://starter.test/starter"
    val responses = mapOf(
      starterUrl to Response.redirect("https://starter.test/starter/9.9.9"),
      "https://starter.test/api/9.9.9" to Response(200, starterMetadata(includeSe = false).toByteArray(StandardCharsets.UTF_8))
    )
    val client = HelidonStarterClient(
      starterRoot = starterUrl,
      apiRoot = "https://starter.test/api"
    ) { url ->
      FakeHttpURLConnection(url, responses.getValue(url.toString()))
    }

    client.generate(
      HelidonStarterRequest(
        groupId = "com.example",
        artifactId = "demo",
        projectVersion = "1.0-SNAPSHOT",
        packageName = "com.example.demo"
      )
    )
  }

  @Test
  fun zipAssetsRejectUnsafePaths() {
    val assets = HelidonStarterZipAssets.toAssets(zip {
      file("demo/pom.xml", "starter pom")
      file("demo/../unsafe.txt", "unsafe")
      file("demo/src\\main\\Bad.java", "bad")
    })

    assertEquals(listOf("pom.xml"), assets.map { it.relativePath })
  }

  @Test
  fun zipAssetsSkipIdeaDirectory() {
    val assets = HelidonStarterZipAssets.toAssets(zip {
      file("demo/pom.xml", "starter pom")
      dir("demo/.idea/")
      file("demo/.idea/runConfigurations/configuration.xml", "idea")
    })

    assertEquals(listOf("pom.xml"), assets.map { it.relativePath })
  }

  private fun starterMetadata(buildSystem: String = "maven", includeSe: Boolean = true): String {
    val seOption = if (includeSe) {
      """
      {
        "kind": "option",
        "name": "Helidon SE",
        "value": "se",
        "children": [
          {
            "kind": "inputs",
            "children": [
              {
                "kind": "enum",
                "id": "app-type",
                "children": [
                  {
                    "kind": "option",
                    "value": "quickstart"
                  },
                  {
                    "kind": "option",
                    "value": "database"
                  },
                  {
                    "kind": "option",
                    "value": "custom"
                  }
                ]
              }
            ]
          }
        ]
      },
      """.trimIndent()
    }
    else {
      ""
    }
    return """
    {
      "children": [
        {
          "kind": "step",
          "children": [
            {
              "kind": "inputs",
              "children": [
                {
                  "kind": "enum",
                  "id": "flavor",
                  "children": [
                    $seOption
                    {
                      "kind": "option",
                      "name": "Helidon MP",
                      "value": "mp",
                      "children": [
                        {
                          "kind": "inputs",
                          "children": [
                            {
                              "kind": "enum",
                              "id": "app-type",
                              "children": [
                                {
                                  "kind": "option",
                                  "value": "quickstart"
                                },
                                {
                                  "kind": "option",
                                  "value": "database"
                                },
                                {
                                  "kind": "option",
                                  "value": "custom"
                                },
                                {
                                  "kind": "option",
                                  "value": "oci"
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        },
        {
          "kind": "step",
          "children": [
            {
              "kind": "inputs",
              "children": [
                {
                  "kind": "enum",
                  "id": "build-system",
                  "children": [
                    {
                      "kind": "option",
                      "value": "$buildSystem"
                    }
                  ]
                }
              ]
            }
          ]
        }
      ]
    }
    """.trimIndent()
  }

  private fun starterZip(): ByteArray =
    zip {
      dir("demo/")
      file("demo/pom.xml", "starter pom")
      file("demo/src/main/java/com/example/demo/GreetResource.java", "class GreetResource {}")
      file("demo/.idea/runConfigurations/configuration.xml", "idea")
      file("demo/mvnw", "#!/bin/sh")
      dir("demo/src/main/resources/empty/")
    }

  private fun zip(builder: ZipBuilder.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      ZipBuilder(zip).builder()
    }
    return bytes.toByteArray()
  }

  private class ZipBuilder(private val zip: ZipOutputStream) {
    fun dir(path: String) {
      zip.putNextEntry(ZipEntry(path))
      zip.closeEntry()
    }

    fun file(path: String, content: String) {
      zip.putNextEntry(ZipEntry(path))
      zip.write(content.toByteArray(StandardCharsets.UTF_8))
      zip.closeEntry()
    }
  }

  private data class Response(
    val code: Int,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap()
  ) {
    companion object {
      fun redirect(location: String): Response =
        Response(302, headers = mapOf("Location" to location))
    }
  }

  private class FakeHttpURLConnection(url: URL, private val response: Response) : HttpURLConnection(url) {
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun connect() = Unit
    override fun getResponseCode(): Int = response.code
    override fun getInputStream(): InputStream = ByteArrayInputStream(response.body)
    override fun getHeaderField(name: String?): String? =
      response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
  }
}
