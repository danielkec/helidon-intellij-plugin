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
internal const val HELIDON_SE_FLAVOR = "se"
internal const val HELIDON_MP_FLAVOR = "mp"
internal const val HELIDON_QUICKSTART_APP_TYPE = "quickstart"
internal const val HELIDON_DATABASE_APP_TYPE = "database"
internal const val HELIDON_CUSTOM_APP_TYPE = "custom"
internal const val HELIDON_OCI_APP_TYPE = "oci"

internal data class HelidonStarterRequest(
  val groupId: String,
  val artifactId: String,
  val projectVersion: String,
  val packageName: String,
  val options: HelidonStarterOptions = HelidonStarterOptions()
)

internal data class HelidonStarterOptions(
  val flavor: String = HELIDON_SE_FLAVOR,
  val appType: String = HELIDON_QUICKSTART_APP_TYPE,
  val media: List<String> = listOf("json"),
  val jsonLibrary: String = "jsonp",
  val database: Boolean = false,
  val databaseServer: String = "h2",
  val jpaImplementation: String = "hibernate",
  val connectionPool: String = "hikaricp",
  val autoDdl: Boolean = false,
  val persistenceUnitName: String = "pu1",
  val dataSourceName: String = "ds1",
  val security: Boolean = false,
  val authenticationProviders: List<String> = emptyList(),
  val authorizationProviders: List<String> = emptyList(),
  val extras: List<String> = emptyList(),
  val metrics: Boolean = true,
  val metricsProvider: String = "microprofile",
  val metricsBuiltin: Boolean = true,
  val health: Boolean = true,
  val healthBuiltin: Boolean = true,
  val tracing: Boolean = false,
  val tracingProvider: String = "jaeger",
  val docker: Boolean = true,
  val dockerNativeImage: Boolean = true,
  val dockerJlinkImage: Boolean = true,
  val kubernetes: Boolean = true,
  val jpms: Boolean = false
)

internal fun helidonStarterAppTypes(flavor: String): List<String> =
  if (flavor == HELIDON_MP_FLAVOR) {
    listOf(HELIDON_QUICKSTART_APP_TYPE, HELIDON_DATABASE_APP_TYPE, HELIDON_CUSTOM_APP_TYPE, HELIDON_OCI_APP_TYPE)
  }
  else {
    listOf(HELIDON_QUICKSTART_APP_TYPE, HELIDON_DATABASE_APP_TYPE, HELIDON_CUSTOM_APP_TYPE)
  }

internal fun helidonStarterJsonLibraries(flavor: String): List<String> =
  if (flavor == HELIDON_MP_FLAVOR) {
    listOf("jackson", "jsonb")
  }
  else {
    listOf("jsonp", "jackson", "jsonb")
  }

internal fun helidonStarterDefaultJsonLibrary(flavor: String): String =
  if (flavor == HELIDON_MP_FLAVOR) "jsonb" else "jsonp"

internal fun helidonStarterDatabaseServers(flavor: String): List<String> =
  if (flavor == HELIDON_MP_FLAVOR) {
    listOf("h2", "mysql", "oracledb")
  }
  else {
    listOf("h2", "mysql", "oracledb", "mongodb")
  }

internal fun helidonStarterExtras(flavor: String): List<String> =
  if (flavor == HELIDON_MP_FLAVOR) {
    listOf("fault-tolerance", "cors", "coherence")
  }
  else {
    listOf("webclient", "fault-tolerance", "cors", "coherence")
  }

internal fun HelidonStarterOptions.withStarterPreset(
  selectedFlavor: String = flavor,
  selectedAppType: String = appType
): HelidonStarterOptions {
  val normalizedFlavor = if (selectedFlavor == HELIDON_MP_FLAVOR) HELIDON_MP_FLAVOR else HELIDON_SE_FLAVOR
  val normalizedAppType = selectedAppType.takeIf { it in helidonStarterAppTypes(normalizedFlavor) } ?: HELIDON_QUICKSTART_APP_TYPE
  val base = copy(
    flavor = normalizedFlavor,
    appType = normalizedAppType,
    jsonLibrary = jsonLibrary.takeIf { it in helidonStarterJsonLibraries(normalizedFlavor) }
      ?: helidonStarterDefaultJsonLibrary(normalizedFlavor),
    databaseServer = databaseServer.takeIf { it in helidonStarterDatabaseServers(normalizedFlavor) } ?: "h2",
    extras = extras.filter { it in helidonStarterExtras(normalizedFlavor) }
  )

  return when (normalizedAppType) {
    HELIDON_QUICKSTART_APP_TYPE -> base.starterSamplePreset(database = false)
    HELIDON_DATABASE_APP_TYPE -> base.starterSamplePreset(database = true)
    HELIDON_CUSTOM_APP_TYPE -> base.copy(
      media = listOf("json"),
      database = false,
      security = false,
      authenticationProviders = emptyList(),
      authorizationProviders = emptyList(),
      extras = emptyList(),
      metrics = false,
      health = false,
      tracing = false,
      docker = false,
      dockerNativeImage = false,
      dockerJlinkImage = false,
      kubernetes = false,
      jpms = false
    )
    HELIDON_OCI_APP_TYPE -> base.copy(
      media = emptyList(),
      database = false,
      security = false,
      authenticationProviders = emptyList(),
      authorizationProviders = emptyList(),
      extras = emptyList(),
      metrics = true,
      metricsBuiltin = true,
      health = true,
      healthBuiltin = true,
      tracing = false,
      docker = true,
      dockerNativeImage = false,
      dockerJlinkImage = false,
      kubernetes = true,
      jpms = false
    )
    else -> base
  }.normalizedForStarter()
}

internal fun HelidonStarterOptions.normalizedForStarter(): HelidonStarterOptions {
  val normalizedFlavor = if (flavor == HELIDON_MP_FLAVOR) HELIDON_MP_FLAVOR else HELIDON_SE_FLAVOR
  val normalizedAppType = appType.takeIf { it in helidonStarterAppTypes(normalizedFlavor) } ?: HELIDON_QUICKSTART_APP_TYPE
  val normalizedJsonLibrary = jsonLibrary.takeIf { it in helidonStarterJsonLibraries(normalizedFlavor) }
    ?: helidonStarterDefaultJsonLibrary(normalizedFlavor)
  val normalizedDatabaseServer = databaseServer.takeIf { it in helidonStarterDatabaseServers(normalizedFlavor) } ?: "h2"
  val normalizedExtras = extras.filter { it in helidonStarterExtras(normalizedFlavor) }

  val normalized = copy(
    flavor = normalizedFlavor,
    appType = normalizedAppType,
    jsonLibrary = normalizedJsonLibrary,
    databaseServer = normalizedDatabaseServer,
    extras = normalizedExtras
  )

  return when (normalizedAppType) {
    HELIDON_QUICKSTART_APP_TYPE -> normalized.starterSamplePreset(database = false)
    HELIDON_DATABASE_APP_TYPE -> normalized.starterSamplePreset(database = true)
    HELIDON_OCI_APP_TYPE -> normalized.copy(
      media = emptyList(),
      database = false,
      security = false,
      authenticationProviders = emptyList(),
      authorizationProviders = emptyList(),
      extras = emptyList(),
      metrics = true,
      metricsBuiltin = true,
      health = true,
      healthBuiltin = true,
      tracing = false,
      docker = true,
      dockerNativeImage = false,
      dockerJlinkImage = false,
      kubernetes = true,
      jpms = false
    )
    else -> normalized
  }
}

private fun HelidonStarterOptions.starterSamplePreset(database: Boolean): HelidonStarterOptions =
  copy(
    media = listOf("json"),
    database = database,
    security = false,
    authenticationProviders = emptyList(),
    authorizationProviders = emptyList(),
    extras = emptyList(),
    metrics = true,
    metricsBuiltin = true,
    health = true,
    healthBuiltin = true,
    tracing = false,
    docker = true,
    dockerNativeImage = true,
    dockerJlinkImage = true,
    kubernetes = true,
    jpms = false
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
    val normalizedRequest = request.copy(options = request.options.normalizedForStarter())
    HelidonStarterMetadata(metadata).validate(normalizedRequest.options, starterVersion)

    val zipBytes = readBytes(generateUrl(starterVersion, normalizedRequest))
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
    val query = request.parameters().joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
    return URI("${apiRoot.trimEnd('/')}/${encode(helidonVersion)}/generate?$query").toURL()
  }

  private fun HelidonStarterRequest.parameters(): List<Pair<String, String>> =
    buildList {
      add("flavor" to options.flavor)
      add("app-type" to options.appType)
      add("build-system" to "maven")
      add("groupId" to groupId)
      add("artifactId" to artifactId)
      add("version" to projectVersion)
      add("package" to packageName)
      addAll(options.parameters())
    }

  private fun HelidonStarterOptions.parameters(): List<Pair<String, String>> =
    buildList {
      if (appType == HELIDON_OCI_APP_TYPE) {
        add("db" to database.toString())
        add("docker" to docker.toString())
        add("docker.native-image" to dockerNativeImage.toString())
        add("docker.jlink-image" to dockerJlinkImage.toString())
        add("k8s" to kubernetes.toString())
        add("jpms" to jpms.toString())
      }
      else {
        addList("media", media)
        if ("json" in media) {
          add("media.json-lib" to jsonLibrary)
        }

        add("db" to database.toString())
        if (database) {
          if (flavor == HELIDON_MP_FLAVOR) {
            add("db.jpa-impl" to jpaImplementation)
            add("db.cp" to connectionPool)
            add("db.server" to databaseServer)
            add("db.auto-ddl" to autoDdl.toString())
            add("db.pu-name" to persistenceUnitName)
            add("db.ds-name" to dataSourceName)
          }
          else {
            add("db.server" to databaseServer)
          }
        }

        add("security" to security.toString())
        if (security) {
          addList("security.atn", authenticationProviders)
          addList("security.atz", authorizationProviders)
        }

        addList("extra", extras)
        add("metrics" to metrics.toString())
        if (metrics) {
          if (flavor == HELIDON_MP_FLAVOR) {
            add("metrics.provider" to metricsProvider)
          }
          add("metrics.builtin" to metricsBuiltin.toString())
        }
        add("health" to health.toString())
        if (health) {
          add("health.builtin" to healthBuiltin.toString())
        }
        add("tracing" to tracing.toString())
        if (tracing) {
          add("tracing.provider" to tracingProvider)
        }
        add("docker" to docker.toString())
        if (docker) {
          add("docker.native-image" to dockerNativeImage.toString())
          add("docker.jlink-image" to dockerJlinkImage.toString())
        }
        add("k8s" to kubernetes.toString())
        add("jpms" to jpms.toString())
      }
    }

  private fun MutableList<Pair<String, String>>.addList(name: String, values: List<String>) {
    values.forEach { add(name to it) }
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

  fun validate(options: HelidonStarterOptions, starterVersion: String) {
    if (!supportsBuildSystem("maven")) {
      throw HelidonStarterUnsupportedException("Helidon Starter $starterVersion does not expose Maven generation")
    }
    if (!findOption(root, "flavor", options.flavor)) {
      throw HelidonStarterUnsupportedException("Helidon Starter $starterVersion does not expose Helidon ${options.flavor.uppercase()}")
    }
    if (!supportsApplicationType(options.flavor, options.appType)) {
      throw HelidonStarterUnsupportedException(
        "Helidon Starter $starterVersion does not expose ${options.appType} application type for ${options.flavor.uppercase()}"
      )
    }
  }

  fun supportsBuildSystem(buildSystem: String): Boolean =
    findOption(root, "build-system", buildSystem)

  private fun supportsApplicationType(flavor: String, appType: String): Boolean {
    val flavorOption = findOptionObject(root, "flavor", flavor) ?: return false
    return findOption(flavorOption, "app-type", appType)
  }

  private fun findOption(element: JsonElement?, id: String, value: String): Boolean {
    return findOptionObject(element, id, value) != null
  }

  private fun findOptionObject(element: JsonElement?, id: String, value: String): JsonObject? {
    if (element == null || element.isJsonNull) {
      return null
    }
    if (element.isJsonObject) {
      val obj = element.asJsonObject
      if (obj.get("id")?.asStringSafe() == id) {
        val children = obj.getAsJsonArray("children") ?: return null
        children.forEach { child ->
          if (child.isJsonObject && child.asJsonObject.get("value")?.asStringSafe() == value) {
            return child.asJsonObject
          }
        }
      }
      obj.entrySet().forEach { entry ->
        findOptionObject(entry.value, id, value)?.let { return it }
      }
      return null
    }
    if (element.isJsonArray) {
      element.asJsonArray.forEach { child ->
        findOptionObject(child, id, value)?.let { return it }
      }
    }
    return null
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
