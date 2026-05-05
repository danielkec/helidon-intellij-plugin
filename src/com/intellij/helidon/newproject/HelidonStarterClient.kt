// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.helidon.newproject

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.starters.local.GeneratorAsset
import com.intellij.ide.starters.local.GeneratorEmptyDirectory
import com.intellij.ide.starters.local.GeneratorFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val HELIDON_STARTER_ROOT = "https://helidon.io/starter"
private const val HELIDON_STARTER_API = "https://helidon.io/api/starter"

internal data class HelidonStarterRequest(
  val groupId: String,
  val artifactId: String,
  val projectVersion: String,
  val packageName: String
)

internal data class HelidonStarterProject(
  val assets: List<GeneratorAsset>,
  val filesToOpen: List<String>
)

internal fun interface HelidonStarterProjectGenerator {
  fun generate(request: HelidonStarterRequest): HelidonStarterProject
}

internal object HelidonStarterProjectGeneratorProvider {
  var generator: HelidonStarterProjectGenerator = HelidonStarterClient()
}

internal class HelidonStarterClient(
  private val starterRoot: String = HELIDON_STARTER_ROOT,
  private val apiRoot: String = HELIDON_STARTER_API,
  private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : HelidonStarterProjectGenerator {

  override fun generate(request: HelidonStarterRequest): HelidonStarterProject {
    val starterVersion = resolveStarterVersion()
    val metadata = readText(metadataUrl(starterVersion))
    if (!HelidonStarterMetadata(metadata).supportsBuildSystem("maven")) {
      throw HelidonStarterUnsupportedException("Helidon Starter $starterVersion does not expose Maven generation")
    }

    val zipBytes = readBytes(generateUrl(starterVersion, request))
    val assets = HelidonStarterZipAssets.toAssets(zipBytes)
    return HelidonStarterProject(assets, filesToOpen(assets))
  }

  private fun resolveStarterVersion(): String {
    val url = URI(starterRoot.trimEnd('/')).toURL()
    val connection = openConnection(url, followRedirects = false)
    try {
      val responseCode = connection.responseCode
      if (responseCode in 300..399) {
        val location = connection.getHeaderField("Location")
          ?: throw IOException("Helidon Starter redirect from $url did not include a Location header")
        return extractStarterVersion(url, location)
      }
      throw IOException("Helidon Starter did not redirect $url to a versioned starter; HTTP $responseCode")
    }
    finally {
      connection.disconnect()
    }
  }

  private fun extractStarterVersion(baseUrl: URL, location: String): String {
    val resolved = URI(baseUrl.toString()).resolve(location)
    val segments = resolved.path.trim('/').split('/').filter(String::isNotBlank)
    if (segments.size < 2 || segments[segments.lastIndex - 1] != "starter") {
      throw IOException("Helidon Starter redirect target $resolved is not a versioned starter URL")
    }
    return segments.last()
  }

  private fun metadataUrl(helidonVersion: String): URL =
    URI("${apiRoot.trimEnd('/')}/${encode(helidonVersion)}").toURL()

  private fun generateUrl(helidonVersion: String, request: HelidonStarterRequest): URL {
    val query = linkedMapOf(
      "flavor" to "mp",
      "app-type" to "quickstart",
      "build-system" to "maven",
      "groupId" to request.groupId,
      "artifactId" to request.artifactId,
      "version" to request.projectVersion,
      "package" to request.packageName
    ).entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
    return URI("${apiRoot.trimEnd('/')}/${encode(helidonVersion)}/generate?$query").toURL()
  }

  private fun readText(url: URL): String =
    readBytes(url).toString(StandardCharsets.UTF_8)

  private fun readBytes(url: URL): ByteArray {
    val connection = openConnection(url, followRedirects = true)
    try {
      val responseCode = connection.responseCode
      if (responseCode !in 200..299) {
        val error = connection.errorStream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
        throw IOException("Helidon Starter returned HTTP $responseCode for $url${if (error.isBlank()) "" else ": $error"}")
      }
      return connection.inputStream.use { it.readBytes() }
    }
    finally {
      connection.disconnect()
    }
  }

  private fun openConnection(url: URL, followRedirects: Boolean): HttpURLConnection {
    val connection = connectionFactory(url)
    connection.connectTimeout = 10_000
    connection.readTimeout = 30_000
    connection.instanceFollowRedirects = followRedirects
    return connection
  }

  private fun filesToOpen(assets: List<GeneratorAsset>): List<String> {
    val paths = assets.map { it.relativePath }.toSet()
    val files = mutableListOf<String>()
    if ("pom.xml" in paths) {
      files.add("pom.xml")
    }
    val sourceFile = paths.firstOrNull { it.endsWith("/GreetResource.java") }
      ?: paths.firstOrNull { it.endsWith("/Main.java") }
      ?: paths.firstOrNull { it.startsWith("src/main/java/") && it.endsWith(".java") }
    sourceFile?.let { files.add(it) }
    return files
  }

  private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
}

internal class HelidonStarterUnsupportedException(message: String) : IOException(message)

internal class HelidonStarterMetadata(metadata: String) {
  private val root: JsonObject = JsonParser.parseString(metadata).asJsonObject

  fun supportsBuildSystem(buildSystem: String): Boolean =
    findOption(root, "build-system", buildSystem)

  private fun findOption(element: JsonElement?, id: String, value: String): Boolean {
    if (element == null || element.isJsonNull) {
      return false
    }
    if (element.isJsonObject) {
      val obj = element.asJsonObject
      if (obj.get("id")?.asStringSafe() == id) {
        val children = obj.getAsJsonArray("children") ?: return false
        return children.any { child ->
          child.isJsonObject && child.asJsonObject.get("value")?.asStringSafe() == value
        }
      }
      return obj.entrySet().any { findOption(it.value, id, value) }
    }
    if (element.isJsonArray) {
      return element.asJsonArray.any { findOption(it, id, value) }
    }
    return false
  }

  private fun JsonElement.asStringSafe(): String? =
    takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}

internal object HelidonStarterZipAssets {
  private val executablePermissions = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_EXECUTE
  )

  fun toAssets(zipBytes: ByteArray): List<GeneratorAsset> {
    val entries = readEntries(zipBytes)
    val root = commonRoot(entries.map { it.name })
    val assets = mutableListOf<GeneratorAsset>()
    val filePaths = mutableSetOf<String>()

    entries.filterNot { it.directory }.forEach { entry ->
      val relativePath = sanitize(entry.name.removeRoot(root)) ?: return@forEach
      if (isIdeaPath(relativePath)) {
        return@forEach
      }
      filePaths.add(relativePath)
      assets.add(GeneratorFile(relativePath, permissions(relativePath), entry.content))
    }

    entries.filter { it.directory }.forEach { entry ->
      val relativePath = sanitize(entry.name.removeRoot(root)) ?: return@forEach
      if (isIdeaPath(relativePath)) {
        return@forEach
      }
      if (filePaths.none { it.startsWith("$relativePath/") }) {
        assets.add(GeneratorEmptyDirectory(relativePath))
      }
    }

    return assets
  }

  private fun readEntries(zipBytes: ByteArray): List<ArchiveEntry> {
    val entries = mutableListOf<ArchiveEntry>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
      var entry: ZipEntry? = zip.nextEntry
      while (entry != null) {
        val name = entry.name
        if (!entry.isDirectory) {
          entries.add(ArchiveEntry(name, false, zip.readBytes()))
        }
        else {
          entries.add(ArchiveEntry(name.trimEnd('/'), true, ByteArray(0)))
        }
        zip.closeEntry()
        entry = zip.nextEntry
      }
    }
    return entries
  }

  private fun commonRoot(paths: List<String>): String? {
    val firstSegments = paths.mapNotNull { it.substringBefore('/', missingDelimiterValue = "").takeIf(String::isNotBlank) }.toSet()
    if (firstSegments.size != 1) {
      return null
    }
    val root = firstSegments.single()
    return if (paths.all { it == root || it.startsWith("$root/") }) root else null
  }

  private fun String.removeRoot(root: String?): String =
    if (root == null) this else removePrefix(root).removePrefix("/")

  private fun sanitize(path: String): String? {
    if (path.isBlank() || path.contains('\\')) {
      return null
    }
    val normalized = Paths.get(path).normalize()
    if (normalized.isAbsolute || normalized.startsWith("..")) {
      return null
    }
    return normalized.toString().replace(Path.of("").fileSystem.separator, "/").takeIf(String::isNotBlank)
  }

  private fun permissions(path: String): Set<PosixFilePermission> =
    if (path.endsWith("/mvnw") || path.endsWith("/gradlew") || path == "mvnw" || path == "gradlew") {
      executablePermissions
    }
    else {
      emptySet()
    }

  private fun isIdeaPath(path: String): Boolean =
    path == ".idea" || path.startsWith(".idea/")

  private data class ArchiveEntry(
    val name: String,
    val directory: Boolean,
    val content: ByteArray
  )
}
